package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.api.chat.llmprovider.providerSessionIdForScope
import com.ai.assistance.operit.core.agent.*
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A separate tool-using review after a foreground turn; its hidden chat is never searched as user history. */
object MemoryLearningCoordinator {
    const val ACTION = "memory_learning_action"
    const val FINISH = "memory_learning_finish"
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancellingJobs = ConcurrentHashMap<String, Job>()
    private val automaticJobs = ConcurrentHashMap<String, Job>()
    private val jobProfiles = ConcurrentHashMap<String,String>()
    private val deletedProfiles = ConcurrentHashMap.newKeySet<String>()
    private val lifecycleLocks = ConcurrentHashMap<String,Mutex>()
    private fun lifecycle(profile: String) = lifecycleLocks.computeIfAbsent(profile) { Mutex() }
    private data class Prepared(val profile: String, val iterations: Int)
    private val prepared = ConcurrentHashMap<Pair<String,String>,Prepared>()
    private val foreground = ConcurrentHashMap.newKeySet<String>()
    private val manualClaims = ConcurrentHashMap.newKeySet<String>()
    fun cancelAutomaticReviews() { automaticJobs.values.forEach { it.cancel() } }
    suspend fun deleteSpace(context: Context, profile: String) {
        lifecycle(profile).withLock {
            deletedProfiles.add(profile)
            prepared.entries.removeAll { it.value.profile==profile }
        }
        val targets=jobProfiles.filterValues { it==profile }.keys.flatMap {
            listOfNotNull(jobs[it],cancellingJobs[it])
        }.distinct()
        targets.forEach { it.cancel() }
        targets.forEach { it.join() }
        lifecycle(profile).withLock {
            withContext(Dispatchers.IO) { MemoryLearningJournal.deleteSpace(context,profile) }
        }
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private class Session(val actions: MemoryLearningActions) : AgentRunObserver {
        override val capabilityTools = setOf(ACTION,FINISH)
        val lock = Mutex()
        val requests = AtomicInteger()
        val calls = AtomicInteger()
        var finished = false
        val failures = mutableListOf<String>()
        var persistProgress: suspend () -> Unit = {}
        var recordModelRound: () -> Unit = {}
        val evidenceBytes = AtomicLong()
        var evidenceBudget: Long = Long.MAX_VALUE
        lateinit var job: Job
        override fun onModelRequest() {
            if (evidenceBytes.get() > evidenceBudget) {
                val reason = "Learning context budget reached; stopped before another model request"
                failures.add(reason)
                error(reason)
            }
            if (requests.incrementAndGet()>LEARNING_ROUND_LIMIT) {
                // Recorded like the context-budget stop so the extraction log says why the batch ended
                // instead of only reporting a bare limit message. The source range stays pending.
                val reason = "Learning round limit reached; the batch was discarded and its source will be reviewed again"
                failures.add(reason)
                error(reason)
            }
            recordModelRound()
        }
        override suspend fun beforeToolBatch(tools: List<AITool>) {
            check(tools.all { it.name in capabilityTools })
        }
        fun roundNotice(): String? = learningRoundNotice(LEARNING_ROUND_LIMIT - requests.get())
    }
    fun foregroundStarted(chatId: String?) {
        chatId?.let { id ->
            foreground.add(id)
            prepared.keys.removeAll { it.first==id }
            cancelReview(id)
        }
    }
    private fun cancelReview(id: String) {
        jobs.remove(id)?.let { job ->
            cancellingJobs[id] = job
            job.invokeOnCompletion { cancellingJobs.remove(id, job) }
            job.cancel()
        }
    }
    fun foregroundEnded(context: Context, chatId: String?) {
        if (chatId != null) {
            foreground.remove(chatId)
            prepared.keys.removeAll { it.first==chatId }
            resumePending(context)
        }
    }
    suspend fun manualReview(context: Context, profileId: String, chatId: String) {
        check(manualClaims.add(chatId)) { "A manual review is already running" }
        try { runManualReview(context,profileId,chatId) }
        finally {
            manualClaims.remove(chatId)
            resumePending(context)
        }
    }
    private suspend fun runManualReview(context: Context, profileId: String, chatId: String) = coroutineScope {
        check(!foreground.contains(chatId)) { "Wait for the current response before manually reviewing it" }
        cancelReview(chatId)
        cancellingJobs[chatId]?.join()
        val task = async(start=CoroutineStart.LAZY) {
            lifecycle(profileId).withLock {
                check(profileId !in deletedProfiles) { "Memory space was deleted" }
                val journal=MemoryLearningJournal(context,profileId,chatId)
                journal.export()
                journal.enqueue(true,true,AppDatabase.getDatabase(context).chatContentDao().learningSourceHorizon(chatId),
                    restart=true)
            }
            // An explicit review waits for its backlog, rather than reporting a three-batch prefix
            // as a complete review. Cancellation/time limits retain completed batch checkpoints.
            withTimeout(30*60_000L) {
                do {
                    review(context,profileId,chatId,manual=true)
                    val remaining=MemoryLearningJournal(context,profileId,chatId)
                } while (remaining.pending("notes") || remaining.pending("skills"))
            }
        }
        jobs[chatId]=task
        jobProfiles[chatId]=profileId
        task.invokeOnCompletion { if (jobs.remove(chatId,task)) jobProfiles.remove(chatId,profileId) }
        if (foreground.contains(chatId)) task.cancel()
        task.start()
        try { task.await() } finally {
            jobs.remove(chatId,task)
        }
    }

    suspend fun prepareReview(context: Context, profileId: String, chatId: String, turnKey: String?,
                              toolIterations: Int = 0) {
        if (profileId in deletedProfiles || !ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return
        if (turnKey != null) prepared[chatId to turnKey]=Prepared(profileId,toolIterations)
    }

    /** Called only after the matching foreground turn's final messages have been persisted. */
    suspend fun sourceCommitted(context: Context, chatId: String, turnKey: String, revisedTimestamp: Long? = null) {
        val metadata=prepared.remove(chatId to turnKey) ?: return
        val profileId=metadata.profile
        val settings = MemorySearchSettingsPreferences(context,profileId)
        cancellingJobs[chatId]?.join()
        val tick = settings.advanceLearningCadence(chatId, metadata.iterations)
        val dao=AppDatabase.getDatabase(context).chatContentDao()
        val horizon=dao.learningSourceHorizon(chatId)
        val revisedId=revisedTimestamp?.let { dao.learningMessageId(chatId,it) }
        lifecycle(profileId).withLock {
            if (profileId in deletedProfiles) return
            val journal=MemoryLearningJournal(context,profileId,chatId)
            revisedId?.let { journal.rewind(it) }
            if (!tick.notes && !tick.skills && !journal.pending("notes") && !journal.pending("skills")) return
            journal.enqueue(tick.notes,tick.skills,horizon)
        }
        foreground.remove(chatId)
        schedule(context,profileId,chatId)
    }

    fun resumePending(context: Context) {
        scope.launch {
            MemoryLearningJournal.pending(context).forEach { (profile,chat) ->
                if (!foreground.contains(chat) && !jobs.containsKey(chat)) schedule(context,profile,chat)
            }
        }
    }

    private fun schedule(context: Context, profileId: String, chatId: String) {
        if (profileId in deletedProfiles || chatId in manualClaims) return
        val settings = MemorySearchSettingsPreferences(context,profileId)
        val job = scope.launch(start=CoroutineStart.LAZY) {
            try {
                do {
                    // A bounded run processes at most three batches, then yields another idle period.
                    delay(settings.learningDelayMinutes() * 60_000L)
                    if (profileId in deletedProfiles || foreground.contains(chatId) ||
                        !ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return@launch
                    val journal=MemoryLearningJournal(context,profileId,chatId)
                    val notes=journal.pending("notes") && settings.shouldExtractNewMemory()
                    val skills=journal.pending("skills") && settings.shouldExtractSkills()
                    journal.export()
                    if (!notes && !skills) return@launch
                    settings.consumePendingLearning(chatId)
                    review(context.applicationContext,profileId,chatId,reviewNotes=notes,reviewSkills=skills)
                    val remaining=MemoryLearningJournal(context,profileId,chatId)
                } while (remaining.pending("notes") && settings.shouldExtractNewMemory() ||
                    remaining.pending("skills") && settings.shouldExtractSkills())
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { AppLogger.e("MemoryLearning","Background review failed",e) }
        }
        if (jobs.putIfAbsent(chatId,job)!=null) { job.cancel(); return }
        jobProfiles[chatId]=profileId
        automaticJobs[chatId] = job
        job.invokeOnCompletion {
            if (jobs.remove(chatId,job)) jobProfiles.remove(chatId,profileId)
            automaticJobs.remove(chatId,job)
        }
        if (foreground.contains(chatId) || chatId in manualClaims) { job.cancel(); return }
        job.start()
    }

    suspend fun review(context: Context, profileId: String, chatId: String, manual: Boolean = false,
                       reviewNotes: Boolean = true, reviewSkills: Boolean = true) {
        if (!manual && !ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return
        val db = AppDatabase.getDatabase(context)
        val chat = db.chatDao().getChatById(chatId)
        require(chat!=null && !chat.isHidden && chat.parentChatId==null && chat.chatKind=="NORMAL")
        val settings = MemorySearchSettingsPreferences(context,profileId)
        val notes = manual || (reviewNotes && settings.shouldExtractNewMemory())
        val skills = manual || (reviewSkills && settings.shouldExtractSkills())
        if (!notes && !skills) return
        val journal=MemoryLearningJournal(context,profileId,chatId)
        if (manual) journal.enqueue(notes,skills,db.chatContentDao().learningSourceHorizon(chatId))
        journal.export()
        MemoryLearningSource(context,db.chatContentDao(),chatId,journal.horizon(),
            settings.shouldIncludeThinking()).use { source ->
            repeat(3) {
                val paths=listOfNotNull("notes".takeIf { notes && journal.pending(it) },
                    "skills".takeIf { skills && journal.pending(it) })
                if (paths.isEmpty()) return
                val first=paths.minBy { journal.cursor(it).messageId }
                val selected=paths.filter { journal.cursor(it)==journal.cursor(first) }
                val instructions=buildMemoryLearningInstructions(chatId,"notes" in selected,"skills" in selected,FINISH)
                val config=MemoryLearningSnapshot.build(context,emptyList(),instructions.toByteArray().size,
                    settings.shouldIncludeThinking())
                val batch=source.next(journal.cursor(first),
                    MemoryLearningSnapshot.sourceBudget(config.contextWindow,instructions.toByteArray().size))
                if (batch.text.isBlank()) {
                    journal.complete(selected,batch.next,batch.more,emptyList())
                    journal.export()
                } else reviewBatch(context,profileId,chatId,selected,batch,config.contextWindow,journal)
            }
        }
    }

    private suspend fun reviewBatch(context: Context, profileId: String, chatId: String,
        paths: List<String>, batch: MemoryLearningSource.Batch, contextWindow: Int, journal: MemoryLearningJournal) {
        val notes="notes" in paths
        val skills="skills" in paths
        val logRepo = MemoryExtractionLogRepository(context,profileId)
        val log = MemoryExtractionLog(sourceChatId=chatId,graph=false,notes=notes,skills=skills)
        var created = 0
        val staged=mutableListOf<MemoryReviewChange>()
        val session = Session(MemoryLearningActions(context,profileId,chatId,notes,skills,true,staged))
        var childId: String? = null
        var runId = ""
        fun snapshot(status: String = "running", finishedAt: Long = 0, detail: String = session.failures.joinToString("\n")) =
            log.copy(status=status,finishedAt=finishedAt,proposals=created,detail=detail,
                runId=runId,childChatId=childId.orEmpty(),modelRounds=session.requests.get(),toolCalls=session.calls.get())
        session.persistProgress = { logRepo.save(snapshot()) }
        logRepo.save(log)
        try {
            // This budget includes reasoning and all model rounds, not just tool execution.
            withTimeout(10 * 60_000L) {
                session.job = currentCoroutineContext().job
                val instructions = buildMemoryLearningInstructions(chatId, notes, skills, FINISH)
                // Conservative byte accounting bounds repeated history/skill reads as well as SOURCE.
                // Leave room for the scoped tool schema, model output and protocol overhead.
                session.evidenceBudget = MemoryLearningSnapshot.evidenceBudget(contextWindow).toLong()
                session.evidenceBytes.set((batch.text + instructions).toByteArray(Charsets.UTF_8).size.toLong())
                val result = SubagentCoordinator.getInstance(context).runTask(SubagentTaskRequest(
                    parentChatId=chatId,parentToolCallId=null,parentAgentName=null,
                    title=context.getString(R.string.memory_learning_run),prompt="$instructions\n\nSOURCE BATCH:\n${batch.text}",
                    subagentType="memory-learning",functionType=FunctionType.MEMORY,
                    profileOverride=AgentProfile("memory-learning","Memory learning","",AgentMode.SUBAGENT,instructions,hidden=true),
                    isolatedToolPrompts=prompts(notes, skills),terminalToolNames=setOf(FINISH),
                    // Every batch of one conversation asks under one identity so a provider that caches
                    // prompt prefixes reuses what a sibling batch already warmed instead of paying a cold
                    // prefix on each of them.
                    providerSessionId=providerSessionIdForScope("memory_learning:$chatId"),
                    promptHooksEnabled=false,disableSummary=false,childHidden=true,
                    childHiddenReason="MEMORY_LEARNING",externalOwnerType="memory-learning",externalOwnerId=log.id,
                    onRunCreated={ run ->
                        childId=run.childChatId
                        runId=run.id
                        logRepo.save(snapshot())
                        session.recordModelRound = {
                            runBlocking {
                                com.ai.assistance.operit.data.repository.SubagentRunRepository.getInstance(context)
                                    .incrementModelRoundCountByChildChatId(run.childChatId)
                            }
                        }
                        sessions[run.childChatId]=session
                        AgentRunObservers.register(run.childChatId,session)
                    }
                ))
                check(result is SubagentTaskResult.Completed) { "Learning task did not complete" }
                check(session.finished) { "Learning review did not confirm batch completion; source progress was retained" }
            }
            currentCoroutineContext().ensureActive()
            journal.complete(paths,batch.next,batch.more,staged)
            created=staged.size
            session.failures.addAll(journal.export())
            logRepo.save(snapshot(status=if (batch.more && session.failures.isEmpty()) "batch_complete"
                else memoryLearningFinalStatus(null,created,session.failures.size),
                finishedAt=System.currentTimeMillis()))
        } catch(e: Exception) {
            withContext(NonCancellable) {
                logRepo.save(snapshot(finishedAt=System.currentTimeMillis(),
                    status=memoryLearningFinalStatus(e,created,session.failures.size),
                    detail=(session.failures+learningFailureDetail("run",e)).joinToString("\n")))
            }
            throw e
        } finally {
            childId?.let { sessions.remove(it); AgentRunObservers.unregister(it) }
        }
    }

    fun execute(tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        val caller = runtime?.callerChatId
        val session = caller?.let { sessions[it] } ?: return ToolResult(
            toolName=tool.name,success=false,result=StringResultData(""),error="No active learning capability")
        return runBlocking(Dispatchers.IO + session.job + ToolExecutionManager.toolRuntimeContextElement(runtime)) {
        session.lock.withLock {
            currentCoroutineContext().ensureActive()
            check(!session.finished) { "This review is already finished" }
            if (session.calls.incrementAndGet()>LEARNING_TOOL_CALL_LIMIT) {
                val reason = "Learning tool-call limit reached; the batch was discarded and its source will be reviewed again"
                session.failures.add(reason)
                error(reason)
            }
            session.evidenceBytes.addAndGet(tool.parameters.sumOf {
                it.value.toByteArray(Charsets.UTF_8).size.toLong()
            })
            try {
                if(tool.name==FINISH) {
                    session.finished=true
                    ToolResult(toolName=tool.name,success=true,result=StringResultData("Review finished"))
                } else {
                    val args = tool.parameters.associate { it.name to it.value }
                    val json = JSONObject(args["arguments"].orEmpty().ifBlank { "{}" })
                    val params = json.keys().asSequence().associateWith { json.get(it).toString() }
                    val result = session.actions.execute(args["action"].orEmpty(),params)
                    session.roundNotice()?.let { result.put("notice",it) }
                    val resultText = result.toString()
                    session.evidenceBytes.addAndGet(resultText.toByteArray(Charsets.UTF_8).size.toLong())
                    ToolResult(toolName=tool.name,success=true,result=StringResultData(resultText))
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                session.failures.add(learningFailureDetail(tool.parameters.find { it.name=="action" }?.value.orEmpty(),e))
                // A run of rejected tool calls is exactly when the reviewer needs to know the budget is
                // nearly gone, so the pacing hint rides on the error too.
                val errorText = e.message.orEmpty().let { message ->
                    session.roundNotice()?.let { message+" $it" } ?: message
                }
                ToolResult(toolName=tool.name,success=false,result=StringResultData(""),error=errorText)
            } finally { session.persistProgress() }
        }
        }
    }
    fun prompts(notes: Boolean = true, skills: Boolean = true) = listOf(
        ToolPrompt(name=ACTION,description=memoryLearningActionDescription(notes, skills),parametersStructured=listOf(
            ToolParameterSchema("action","string","Operation",true),
            ToolParameterSchema("arguments","string","JSON argument object",false)
        )),
        ToolPrompt(name=FINISH,description="Finish this learning review.",parametersStructured=emptyList())
    )
}

internal fun memoryLearningFinalStatus(error: Throwable?, proposals: Int, toolErrors: Int): String = when {
    error is TimeoutCancellationException -> "timeout"
    error is CancellationException -> "cancelled"
    error != null -> if (proposals > 0) "partial" else "failed"
    toolErrors > 0 -> "warnings"
    else -> "success"
}

internal fun learningFailureDetail(action: String, error: Throwable): String =
    "$action: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}"

/**
 * Pacing hint attached to a tool result while a batch approaches its round limit. The batch is
 * discarded when the limit is hit, so the reviewer is told to reserve a round to submit and confirm.
 */
internal fun learningRoundNotice(remainingRounds: Int, finish: String = MemoryLearningCoordinator.FINISH): String? = when {
    // No rounds left means the next model request is refused, so finish is no longer reachable.
    remainingRounds<=0 -> "No model rounds left; this batch is discarded and its source reviewed again."
    remainingRounds<=2 -> "$remainingRounds model round${if (remainingRounds==1) "" else "s"} left: stop exploring, " +
        "submit the best complete change you already have and call $finish; an unfinished batch is discarded."
    else -> null
}

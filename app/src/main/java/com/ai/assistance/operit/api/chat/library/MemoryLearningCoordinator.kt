package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
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
    fun cancelAutomaticReviews() { automaticJobs.values.forEach { it.cancel() } }
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
            check(requests.incrementAndGet()<=12) { "Learning round limit reached" }
            recordModelRound()
        }
        override suspend fun beforeToolBatch(tools: List<AITool>) {
            check(tools.all { it.name in capabilityTools })
        }
    }
    fun foregroundStarted(chatId: String?) {
        chatId?.let { id ->
            jobs.remove(id)?.let { job ->
                cancellingJobs[id] = job
                job.invokeOnCompletion { cancellingJobs.remove(id, job) }
                job.cancel()
            }
        }
    }
    suspend fun manualReview(context: Context, profileId: String, chatId: String) = coroutineScope {
        foregroundStarted(chatId)
        cancellingJobs[chatId]?.join()
        val task = async(start=CoroutineStart.LAZY) { review(context,profileId,chatId,manual=true) }
        jobs[chatId]=task
        task.invokeOnCompletion { jobs.remove(chatId,task) }
        task.start()
        task.await()
    }

    suspend fun replyCompleted(context: Context, profileId: String, chatId: String, snapshot: List<Pair<String, String>>,
                               toolIterations: Int = 0) {
        if (!ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return
        val settings = MemorySearchSettingsPreferences(context,profileId)
        if (!settings.shouldExtractNewMemory() && !settings.shouldExtractSkills()) return
        foregroundStarted(chatId)
        // Cancellation writes the interrupted review's pending flags before the next tick reads them.
        cancellingJobs[chatId]?.join()
        val tick = settings.advanceLearningCadence(chatId, toolIterations)
        if (!tick.notes && !tick.skills) return
        val job = scope.launch(start=CoroutineStart.LAZY) {
            var startedPaths: Pair<Boolean, Boolean>? = null
            try {
                // New foreground activity cancels this job; its next reply schedules the latest snapshot.
                delay(settings.learningDelayMinutes() * 60_000L)
                if (!ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return@launch
                val (notes, skills) = settings.consumePendingLearning(chatId)
                startedPaths = notes to skills
                if (notes || skills) review(context.applicationContext,profileId,chatId,snapshot=snapshot,
                    reviewNotes=notes,reviewSkills=skills)
            }
            catch(e: CancellationException) {
                startedPaths?.let { settings.restorePendingLearning(chatId, it.first, it.second) }
                throw e
            }
            catch(e: Exception) { AppLogger.e("MemoryLearning","Background review failed",e) }
        }
        jobs[chatId] = job
        automaticJobs[chatId] = job
        job.invokeOnCompletion { jobs.remove(chatId,job); automaticJobs.remove(chatId,job) }
        job.start()
    }

    suspend fun review(context: Context, profileId: String, chatId: String, manual: Boolean = false,
                       snapshot: List<Pair<String, String>>? = null, reviewNotes: Boolean = true, reviewSkills: Boolean = true) {
        if (!manual && !ApiPreferences.getInstance(context).enableMemoryAutoUpdateFlow.first()) return
        val db = AppDatabase.getDatabase(context)
        val chat = db.chatDao().getChatById(chatId)
        require(chat!=null && !chat.isHidden && chat.parentChatId==null && chat.chatKind=="NORMAL")
        val settings = MemorySearchSettingsPreferences(context,profileId)
        val notes = manual || (reviewNotes && settings.shouldExtractNewMemory())
        val skills = manual || (reviewSkills && settings.shouldExtractSkills())
        if (!notes && !skills) return
        val logRepo = MemoryExtractionLogRepository(context,profileId)
        val log = MemoryExtractionLog(sourceChatId=chatId,graph=false,notes=notes,skills=skills)
        var created = 0
        val session = Session(MemoryLearningActions(context,profileId,chatId,notes,skills,true) { created++ })
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
                val recent = MemoryLearningSnapshot.build(context, snapshot ?:
                    db.chatContentDao().getMessagesForChatDesc(chatId,48).asReversed()
                        .filter { it.sender in setOf("user","ai","summary") }
                        .map { (if (it.sender == "summary") "SUMMARY" else it.sender) to it.content },
                    instructions.toByteArray(Charsets.UTF_8).size)
                // Conservative byte accounting bounds repeated history/skill reads as well as SOURCE.
                // Leave room for the scoped tool schema, model output and protocol overhead.
                session.evidenceBudget = (recent.contextWindow * 0.75).toLong()
                session.evidenceBytes.set((recent.text + instructions).toByteArray(Charsets.UTF_8).size.toLong())
                val result = SubagentCoordinator.getInstance(context).runTask(SubagentTaskRequest(
                    parentChatId=chatId,parentToolCallId=null,parentAgentName=null,
                    title=context.getString(R.string.memory_learning_run),prompt="$instructions\n\nSOURCE:\n${recent.text}",
                    subagentType="memory-learning",functionType=FunctionType.MEMORY,
                    profileOverride=AgentProfile("memory-learning","Memory learning","",AgentMode.SUBAGENT,instructions,hidden=true),
                    isolatedToolPrompts=prompts(notes, skills),terminalToolNames=setOf(FINISH),
                    promptHooksEnabled=false,disableSummary=true,childHidden=true,
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
            }
            logRepo.save(snapshot(status=memoryLearningFinalStatus(null,created,session.failures.size),
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
            check(!session.finished && session.calls.incrementAndGet()<=40)
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
                    val resultText = result.toString()
                    session.evidenceBytes.addAndGet(resultText.toByteArray(Charsets.UTF_8).size.toLong())
                    ToolResult(toolName=tool.name,success=true,result=StringResultData(resultText))
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                session.failures.add(learningFailureDetail(tool.parameters.find { it.name=="action" }?.value.orEmpty(),e))
                ToolResult(toolName=tool.name,success=false,result=StringResultData(""),error=e.message.orEmpty())
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

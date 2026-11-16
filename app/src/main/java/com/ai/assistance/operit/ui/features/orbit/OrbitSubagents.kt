package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import com.ai.assistance.operit.ui.features.chat.components.part.parsePersistedToolExecutions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One tool a subagent called, as a line of its record. */
internal data class OrbitSubagentStep(val tool: String, val ok: Boolean, val line: String)

/** A subagent that worked for a piece of Orbit's work: who it is, how it is doing, what it did. */
internal data class OrbitSubagentRecord(
    val run: SubagentRunEntity,
    /** Its place in the tree, like /root/browse_github. */
    val path: String,
    /** The last steps it took, oldest first. */
    val steps: List<OrbitSubagentStep>,
    val stepCount: Int,
    /** The first line of what it last said; empty while it has said nothing. */
    val result: String,
    /**
     * For a chat turn's record: whether it works on a request made during that turn now, the way
     * the activity list counts it. Null elsewhere, where any listed agent still running counts.
     */
    val workingHere: Boolean? = null,
) {
    val name: String get() = path.substringAfterLast('/').ifBlank { run.title }
    /** Who started a nested agent, like browse_github for /root/browse_github/read_issue; empty for /root's own. */
    val startedBy: String get() = path.removePrefix("/root/").takeIf { path.startsWith("/root/") }
        ?.substringBeforeLast('/', "").orEmpty()
    val running: Boolean get() = run.status in OrbitSubagents.LIVE
    /** Still doing this work, which keeps the record page "going on". */
    val busyHere: Boolean get() = workingHere ?: running
    val failed: Boolean get() = run.status == SubagentRunStatus.FAILED.name
    val stopped: Boolean get() =
        run.status == SubagentRunStatus.CANCELLED.name || run.status == SubagentRunStatus.INTERRUPTED.name

    fun process() = OrbitProcess.Subagent(run.childChatId, path, name, run.createdAt, running)
}

internal object OrbitSubagents {
    val LIVE = setOf(SubagentRunStatus.CREATED.name, SubagentRunStatus.QUEUED.name, SubagentRunStatus.RUNNING.name)
    private const val SHOWN_STEPS = 6
    private const val LINE_LENGTH = 80
    private const val MESSAGE_BATCH_SIZE = 64
    /** Runs started by Orbit's work: v1 task calls and v2 agents, not reviewers or memory helpers. */
    private val OWNERS = setOf(null, com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.OWNER_TYPE)
    private val envelope = Regex("^Message ID: [^\\n]*\\n(?:[A-Za-z ]+: [^\\n]*\\n)*Payload:\\n", RegexOption.MULTILINE)

    /**
     * The subagents behind a piece of work, in the order they were started. A background run or a
     * subagent owns every agent its chat started, and the agents those started.
     *
     * A chat turn owns the agents it started or handed more work to, the agents those started
     * while the turn was the latest, and — as the activity list counts them — any agent of the
     * chat's tree whose latest work was asked for during the turn (a follow-up between siblings).
     * Only those last, while running, keep the turn going on; see [OrbitSubagentRecord.workingHere].
     */
    suspend fun of(context: Context, process: OrbitProcess, messages: List<ChatMessage>, cache: Cache): List<OrbitSubagentRecord> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).subagentRunDao()
            val parentChatId = when (process) {
                is OrbitProcess.ChatTurn -> process.chatId
                is OrbitProcess.LiveTurn -> process.chatId
                is OrbitProcess.TaskRun -> process.chatId
                is OrbitProcess.Subagent -> process.chatId
                is OrbitProcess.Learning -> return@withContext emptyList()
            }
            val manager = ChatHistoryManager.getInstance(context)
            val runs = dao.getByParentChatId(parentChatId).filter(::isWorker)
            val window = turnWindow(AppDatabase.getDatabase(context).messageDao(), process, messages)
            if (window == null) {
                return@withContext (runs + descendants(dao, runs)).sortedBy { it.createdAt }
                    .map { run -> cache.record(manager, run) }
            }
            val collaboration = com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context)
            // Asked for during this turn; for v1 tasks, which keep no request time, when they began.
            fun askedHere(run: SubagentRunEntity) =
                (collaboration.workSince(run.childChatId) ?: run.startedAt ?: run.createdAt) in window
            fun workingHere(run: SubagentRunEntity) = run.status in LIVE && askedHere(run)
            val executions = messages.asSequence().filter { it.sender == "ai" }
                .flatMap { parsePersistedToolExecutions(it.content).values }.toList()
            val calls = executions.mapNotNull { it.callId?.takeIf(String::isNotBlank) }.toSet()
            // A follow-up names the chat it reached, which may sit deeper in the tree.
            val reached = executions.asSequence()
                .filter { it.success && it.toolName == "followup_task" }
                .mapNotNull { com.ai.assistance.operit.ui.features.chat.components.part.collaborationCallAgentId(it.resultText) }
                .toSet()
            val byChat = runs.associateBy { it.childChatId }
            val owned = LinkedHashMap<String, SubagentRunEntity>()
            runs.filter { it.parentToolCallId in calls }.forEach { owned[it.childChatId] = it }
            reached.filterNot { it in owned }.forEach { chat ->
                (byChat[chat] ?: dao.getByChildChatId(chat)?.takeIf(::isWorker))?.let { owned[chat] = it }
            }
            collaboration.chatsWorkingSince(parentChatId, window).filterNot { it in owned }.forEach { chat ->
                (byChat[chat] ?: dao.getByChildChatId(chat)?.takeIf(::isWorker))?.let { owned[chat] = it }
            }
            if (owned.isEmpty()) return@withContext emptyList()
            // What they started belongs here when it was started during the turn or asked for in it.
            val nested = descendants(dao, owned.values.toList(), owned.keys.toHashSet())
                .filter { it.createdAt in window || askedHere(it) }
            (owned.values + nested).sortedBy { it.createdAt }
                .map { run -> cache.record(manager, run).copy(workingHere = workingHere(run)) }
        }

    /**
     * The time a chat turn holds: from its first row until the next listed turn began, the way the
     * activity list bounds it. Without one known, until the user next wrote; quiet automatic turns
     * (a notice Orbit let pass) are not listed there and do not end it here either. Null for other
     * work, which owns its whole chat.
     */
    private suspend fun turnWindow(
        messageDao: com.ai.assistance.operit.data.dao.MessageDao,
        process: OrbitProcess,
        messages: List<ChatMessage>,
    ): LongRange? {
        val (chatId, from, last) = when (process) {
            // A live turn's rows include what the user added while it ran, so look past the last of them.
            is OrbitProcess.LiveTurn -> Triple(
                process.chatId, process.since, maxOf(process.since, process.time, messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE),
            )
            is OrbitProcess.ChatTurn -> Triple(
                process.chatId, process.timestamps.minOrNull() ?: process.time, process.timestamps.maxOrNull() ?: process.time,
            )
            else -> return null
        }
        val next = (process as? OrbitProcess.ChatTurn)?.nextTurnAt ?: messageDao.getNextTypedUserTimestamp(chatId, last)
        val until = next?.minus(1) ?: Long.MAX_VALUE
        return from..until
    }

    /** The chat a run's tree began in: a talk chat or a background run's chat. The tree is shallow. */
    private suspend fun topChat(dao: com.ai.assistance.operit.data.dao.SubagentRunDao, run: SubagentRunEntity): String {
        var top = run
        repeat(MAX_DEPTH) {
            top = dao.getByChildChatId(top.parentChatId)?.takeIf(::isWorker) ?: return top.parentChatId
        }
        return top.parentChatId
    }

    /**
     * The latest state of [records], for a record page that is still running. An agent still
     * working can start agents of its own meanwhile; those join the list.
     */
    suspend fun refresh(context: Context, records: List<OrbitSubagentRecord>, cache: Cache): List<OrbitSubagentRecord> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).subagentRunDao()
            val manager = ChatHistoryManager.getInstance(context)
            val updated = records.map { old ->
                dao.getById(old.run.id)?.let { cache.record(manager, it) } ?: old
            }
            val known = updated.mapTo(HashSet()) { it.run.childChatId }
            // Also look under a run that just ended, or one woken and finished again between two
            // reads (its run row changed): it may have started agents meanwhile.
            val working = updated.zip(records).filter { (now, old) -> now.running || old.running || now.run != old.run }
                .map { it.first }
            val added = descendants(dao, working.map { it.run }, known)
            if (added.isEmpty()) updated
            else (updated + added.map { cache.record(manager, it) }).sortedBy { it.run.createdAt }
        }

    /** The agents [roots] started, and the ones those started, skipping chats in [known]. */
    private suspend fun descendants(
        dao: com.ai.assistance.operit.data.dao.SubagentRunDao,
        roots: List<SubagentRunEntity>,
        known: MutableSet<String> = roots.mapTo(HashSet()) { it.childChatId },
    ): List<SubagentRunEntity> {
        val found = mutableListOf<SubagentRunEntity>()
        var parents = roots.map { it.childChatId }
        var depth = 0
        while (parents.isNotEmpty() && depth++ < MAX_DEPTH) {
            val next = parents.flatMap { parent ->
                dao.getByParentChatId(parent).filter { isWorker(it) && known.add(it.childChatId) }
            }
            found += next
            parents = next.map { it.childChatId }
        }
        return found
    }

    /** Page-local summaries survive polling and returning from a child's record. */
    class Cache {
        private data class Summary(
            val steps: List<OrbitSubagentStep> = emptyList(),
            val stepCount: Int = 0,
            val result: String = "",
        ) {
            fun append(reply: ChatMessage): Summary {
                val executions = parsePersistedToolExecutions(reply.content).toSortedMap()
                val latest = executions.values.asSequence()
                    .drop((executions.size - SHOWN_STEPS).coerceAtLeast(0))
                    .map { OrbitSubagentStep(it.toolName.ifBlank { "tool" }, it.success, line(it.resultText)) }
                    .toList()
                val said = OrbitSpeech.words(reply.content, "")
                return Summary(
                    (steps + latest).takeLast(SHOWN_STEPS),
                    stepCount + executions.size,
                    if (said.isNotBlank()) line(said) else result,
                )
            }
        }

        private data class Entry(
            val record: OrbitSubagentRecord,
            val beforeLastReply: Summary,
            val lastReplyTimestamp: Long?,
            val throughTimestamp: Long,
        )

        private val entries = mutableMapOf<String, Entry>()

        suspend fun record(manager: ChatHistoryManager, run: SubagentRunEntity): OrbitSubagentRecord {
            val old = entries[run.id]
            if (old != null && !old.record.running && old.record.run == run) return old.record
            // Streaming rewrites the last assistant row in place. Rebuild that row from its
            // preceding summary, so updated tool results neither disappear nor count twice.
            var summary = old?.beforeLastReply ?: Summary()
            var beforeLastReply = summary
            var lastReplyTimestamp = old?.lastReplyTimestamp
            var after = lastReplyTimestamp?.minus(1L) ?: old?.throughTimestamp ?: Long.MIN_VALUE
            while (true) {
                val messages = manager.loadChatMessagesAscAfter(
                    run.childChatId, after, MESSAGE_BATCH_SIZE, throwOnError = true,
                )
                for (reply in messages) {
                    if (reply.sender != "ai") continue
                    beforeLastReply = summary
                    summary = summary.append(reply)
                    lastReplyTimestamp = reply.timestamp
                }
                if (messages.isNotEmpty()) after = messages.last().timestamp
                if (messages.size < MESSAGE_BATCH_SIZE) break
            }
            val record = OrbitSubagentRecord(run, pathOf(run), summary.steps, summary.stepCount, summary.result)
            entries[run.id] = Entry(record, beforeLastReply, lastReplyTimestamp, after)
            return record
        }
    }

    private fun isWorker(run: SubagentRunEntity) = run.externalOwnerType in OWNERS &&
        run.agentProfileId != com.ai.assistance.operit.core.agent.AgentProfileRepository.PERMISSION_REVIEWER_ID

    /**
     * Where a subagent still working was started from: the top chat of its tree and when the main
     * agent asked for the work it is doing (see CollaborationAgent.workSince). Each live agent counts
     * on its own, since a follow-up can set any agent of the tree going from a later turn.
     */
    data class LiveOwner(val chatId: String, val startedAt: Long)

    /**
     * Every subagent still working, traced up to the chat its tree began in (a talk chat or a
     * background run's chat), so work whose own answer is done still counts as going on.
     */
    suspend fun liveOwners(context: Context): List<LiveOwner> = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getDatabase(context).subagentRunDao()
        val live = dao.getByStatuses(LIVE.toList()).filter(::isWorker)
        if (live.isEmpty()) return@withContext emptyList()
        val collaboration = com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator.getInstance(context)
        live.map { run ->
            val since = collaboration.workSince(run.childChatId) ?: run.startedAt ?: run.createdAt
            LiveOwner(topChat(dao, run), since)
        }.distinct()
    }

    /** Its place in the tree, like /root/browse_github, or the run's title for a v1 task. */
    fun pathOf(run: SubagentRunEntity): String =
        run.externalOwnerId?.takeIf { it.startsWith("/") } ?: run.title.removePrefix("v2 ").trim()

    fun nameOf(run: SubagentRunEntity): String = pathOf(run).substringAfterLast('/').ifBlank { run.title }

    /** Every subagent working under [chatIds], their own subagents included, newest first. */
    suspend fun underChats(context: Context, chatIds: List<String>): List<SubagentRunEntity> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).subagentRunDao()
            val seen = chatIds.toHashSet()
            val found = mutableListOf<SubagentRunEntity>()
            var parents = chatIds.distinct()
            var depth = 0
            while (parents.isNotEmpty() && depth++ < MAX_DEPTH) {
                parents = parents.flatMap { parent ->
                    dao.getByParentChatId(parent).filter { isWorker(it) && seen.add(it.childChatId) }
                }.also { found += it }.map { it.childChatId }
            }
            found.sortedByDescending { it.createdAt }
        }

    private const val MAX_DEPTH = 6

    /** The automatic permission reviews run for [chatIds], newest first, at most [limit]. */
    suspend fun reviewsUnder(context: Context, chatIds: List<String>, limit: Int = 50): List<SubagentRunEntity> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).subagentRunDao()
            chatIds.distinct().flatMap { parent ->
                dao.getByParentChatId(parent).filter {
                    it.agentProfileId == com.ai.assistance.operit.core.agent.AgentProfileRepository.PERMISSION_REVIEWER_ID
                }
            }.sortedByDescending { it.createdAt }.take(limit)
        }

    /** The words of an agent message (a task handed over) without its envelope. */
    fun payload(content: String): String =
        envelope.find(content)?.let { content.substring(it.range.last + 1) } ?: content

    private fun line(text: String): String =
        OrbitWork.listLine(text).let { if (it.length > LINE_LENGTH) it.take(LINE_LENGTH).trimEnd() + "…" else it }
}

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
) {
    val name: String get() = path.substringAfterLast('/').ifBlank { run.title }
    val running: Boolean get() = run.status in OrbitSubagents.LIVE
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
     * The subagents behind a piece of work, in the order they were started. A chat turn owns the
     * agents its own spawn calls started; a background run or a subagent owns every agent its chat
     * started.
     */
    suspend fun of(context: Context, process: OrbitProcess, messages: List<ChatMessage>, cache: Cache): List<OrbitSubagentRecord> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).subagentRunDao()
            val parentChatId = when (process) {
                is OrbitProcess.ChatTurn -> process.chatId
                is OrbitProcess.TaskRun -> process.chatId
                is OrbitProcess.Subagent -> process.chatId
                is OrbitProcess.Learning -> return@withContext emptyList()
            }
            val runs = dao.getByParentChatId(parentChatId).filter(::isWorker)
            if (runs.isEmpty()) return@withContext emptyList()
            val owned = if (process is OrbitProcess.ChatTurn) {
                val calls = messages.asSequence().filter { it.sender == "ai" }
                    .flatMap { parsePersistedToolExecutions(it.content).values }
                    .mapNotNull { it.callId?.takeIf(String::isNotBlank) }.toSet()
                runs.filter { it.parentToolCallId in calls }
            } else runs
            val manager = ChatHistoryManager.getInstance(context)
            owned.sortedBy { it.createdAt }.map { run -> cache.record(manager, run) }
        }

    /** The latest state of [records], for a record page that is still running. */
    suspend fun refresh(context: Context, records: List<OrbitSubagentRecord>, cache: Cache): List<OrbitSubagentRecord> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).subagentRunDao()
            val manager = ChatHistoryManager.getInstance(context)
            records.map { old ->
                dao.getById(old.run.id)?.let { cache.record(manager, it) } ?: old
            }
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

    /** The words of an agent message (a task handed over) without its envelope. */
    fun payload(content: String): String =
        envelope.find(content)?.let { content.substring(it.range.last + 1) } ?: content

    private fun line(text: String): String =
        OrbitWork.listLine(text).let { if (it.length > LINE_LENGTH) it.take(LINE_LENGTH).trimEnd() + "…" else it }
}

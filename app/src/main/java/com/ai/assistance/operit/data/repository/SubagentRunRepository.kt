package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import java.util.UUID
import kotlinx.coroutines.flow.Flow

data class CreateSubagentRunRequest(
    val parentChatId: String,
    val parentToolCallId: String?,
    val agentProfileId: String,
    val title: String,
    val agentConfigSnapshot: String?,
    val modelConfigIdSnapshot: String?,
    val modelIndexSnapshot: Int?,
    /** child 聊天隐藏标记（阅读伴侣后台/插件路径隐藏 child；对话内路径 false）。 */
    val childHidden: Boolean = false,
    /** child 聊天隐藏原因（仅 childHidden=true 时使用）。 */
    val childHiddenReason: String? = null,
    /** 跨库弱关联所有者类型（阅读伴侣：reading_companion_run）。 */
    val externalOwnerType: String? = null,
    /** 跨库弱关联所有者 ID（阅读伴侣：reading run id 字符串）。 */
    val externalOwnerId: String? = null,
)

data class SubagentRunWithChat(
    val run: SubagentRunEntity,
    val childChat: ChatEntity,
)

/**
 * Owns the durable parent/run/child relationship.
 *
 * The child chat and its run are created in one Room transaction so a task id can never point at
 * a missing transcript. Prompt and result content continue to live only in the child transcript.
 */
class SubagentRunRepository internal constructor(
    private val database: AppDatabase,
) {
    private val chatDao = database.chatDao()
    private val runDao = database.subagentRunDao()

    suspend fun createSubagentChatAndRun(
        request: CreateSubagentRunRequest,
    ): SubagentRunWithChat =
        database.withTransaction {
            require(request.title.isNotBlank()) { "Subagent title must not be blank" }
            val parent =
                requireNotNull(chatDao.getChatById(request.parentChatId)) {
                    "Parent chat does not exist: ${request.parentChatId}"
                }
            val now = System.currentTimeMillis()
            val child =
                ChatEntity(
                    id = UUID.randomUUID().toString(),
                    title = request.title,
                    createdAt = now,
                    updatedAt = now,
                    folderId = parent.folderId,
                    displayOrder = -now,
                    workspace = parent.workspace,
                    workspaceEnv = parent.workspaceEnv,
                    parentChatId = parent.id,
                    chatKind = ChatKind.SUBAGENT.name,
                    characterCardName = parent.characterCardName,
                    characterGroupId = parent.characterGroupId,
                    isHidden = request.childHidden,
                    hiddenReason = request.childHiddenReason,
                )
            val run =
                SubagentRunEntity(
                    id = UUID.randomUUID().toString(),
                    parentChatId = parent.id,
                    childChatId = child.id,
                    parentToolCallId = request.parentToolCallId,
                    agentProfileId = request.agentProfileId,
                    title = request.title,
                    status = SubagentRunStatus.CREATED.name,
                    createdAt = now,
                    agentConfigSnapshot = request.agentConfigSnapshot,
                    modelConfigIdSnapshot = request.modelConfigIdSnapshot,
                    modelIndexSnapshot = request.modelIndexSnapshot,
                    externalOwnerType = request.externalOwnerType,
                    externalOwnerId = request.externalOwnerId,
                )
            chatDao.insertChat(child)
            runDao.insert(run)
            SubagentRunWithChat(run = run, childChat = child)
        }

    suspend fun getById(taskId: String): SubagentRunEntity? = runDao.getById(taskId)

    fun observeById(taskId: String): Flow<SubagentRunEntity?> = runDao.observeById(taskId)

    suspend fun getByChildChatId(childChatId: String): SubagentRunEntity? =
        runDao.getByChildChatId(childChatId)

    fun observeByChildChatId(childChatId: String): Flow<SubagentRunEntity?> =
        runDao.observeByChildChatId(childChatId)

    suspend fun getByParentChatId(parentChatId: String): List<SubagentRunEntity> =
        runDao.getByParentChatId(parentChatId)

    fun observeByParentChatId(parentChatId: String): Flow<List<SubagentRunEntity>> =
        runDao.observeByParentChatId(parentChatId)

    fun observeByParentToolCallId(
        parentChatId: String,
        callId: String,
        excludedAgentProfileId: String,
    ): Flow<SubagentRunEntity?> =
        runDao.observeByParentToolCallId(parentChatId, callId, excludedAgentProfileId)

    suspend fun updateStatus(
        taskId: String,
        status: SubagentRunStatus,
        startedAt: Long? = null,
        completedAt: Long? = null,
        error: String? = null,
    ): Boolean {
        val allowedFrom = SubagentRunStateMachine.allowedOrigins(status)
        val updated =
            runDao.updateStatus(
                taskId = taskId,
                allowedFromStatuses = allowedFrom.map { it.name },
                status = status.name,
                startedAt = startedAt,
                completedAt = completedAt,
                error = error,
            )
        if (updated == 1) return true
        check(runDao.getById(taskId) != null) {
            "Subagent task does not exist: $taskId"
        }
        return false
    }

    suspend fun reconcileIncompleteRuns(createdBeforeOrAt: Long): Int =
        runDao.markIncompleteAsInterrupted(
            incompleteStatuses =
                listOf(
                    SubagentRunStatus.CREATED.name,
                    SubagentRunStatus.QUEUED.name,
                    SubagentRunStatus.RUNNING.name,
                ),
            createdBeforeOrAt = createdBeforeOrAt,
            interruptedStatus = SubagentRunStatus.INTERRUPTED.name,
            completedAt = System.currentTimeMillis(),
            error = "The app stopped before this Subagent task reached a terminal state.",
        )

    suspend fun countChildren(parentChatId: String): Int =
        runDao.countByParentChatId(parentChatId)

    suspend fun incrementToolInvocationCountByChildChatId(childChatId: String): Boolean =
        runDao.incrementToolInvocationCountByChildChatId(childChatId) == 1

    suspend fun incrementModelRoundCountByChildChatId(childChatId: String): Boolean =
        runDao.incrementModelRoundCountByChildChatId(childChatId) == 1

    suspend fun getByExternalOwner(ownerType: String, ownerId: String) =
        runDao.getByExternalOwner(ownerType, ownerId)

    suspend fun getByExternalOwnerType(ownerType: String) =
        runDao.getByExternalOwnerType(ownerType)

    /** 补链/清理解绑后由对账逻辑调用；不会删除 run 本身。 */
    suspend fun clearExternalOwner(ownerType: String, ownerId: String): Int =
        runDao.clearExternalOwner(ownerType, ownerId)

    suspend fun setArchived(taskId: String, archived: Boolean): Boolean =
        runDao.updateArchivedAt(
            taskId = taskId,
            archivedAt = if (archived) System.currentTimeMillis() else null,
        ) == 1

    suspend fun deleteChildChat(childChatId: String): Boolean =
        database.withTransaction {
            val run = runDao.getByChildChatId(childChatId) ?: return@withTransaction false
            deleteChatSubtreeInternal(run.childChatId)
        }

    /**
     * Deletes child chats first so the child foreign key cascades each run before the parent row is
     * removed. The parent foreign key intentionally uses NO_ACTION to make bypassing this order fail.
     */
    suspend fun deleteParentChatAndChildren(parentChatId: String): Boolean =
        database.withTransaction {
            if (chatDao.getChatById(parentChatId) == null) {
                return@withTransaction false
            }
            deleteChatSubtreeInternal(parentChatId)
        }

    /**
     * Returns the ids of [chatId] and every chat transitively reachable through
     * `chats.parentChatId` (SUBAGENT and BRANCH descendants). Empty when [chatId] does not exist.
     *
     * Callers pass the result to [SubagentCoordinator.withChatDeletionsPrepared] so every running
     * task in the subtree is cancelled before the deletion transaction runs, then hand the same
     * set to [deleteChatSubtree] as [expectedChatIds] for a transactional re-verification.
     */
    suspend fun getChatSubtreeChatIds(chatId: String): List<String> =
        database.withTransaction {
            val allChats = chatDao.getAllChatsDirectly()
            if (allChats.none { it.id == chatId }) {
                return@withTransaction emptyList()
            }
            subtreeIds(chatId, childrenById(allChats)).toList()
        }

    /**
     * Computes the full descendant closure of [chatId] over the `chats.parentChatId` graph and
     * deletes every unlocked chat child-first in one transaction.
     *
     * A chat can be the parent of both SUBAGENT runs (via `subagent_runs.parentChatId`, NO_ACTION)
     * and BRANCH chats (via `chats.parentChatId`). Both can nest to arbitrary depth, so any
     * single-level deletion either fails on the NO_ACTION foreign key or leaves orphaned branches.
     * Deleting the whole closure child-first satisfies both constraints.
     *
     * Returns false (and deletes nothing) when the chat does not exist or any chat in the subtree
     * is locked; locked chats protect their entire descendant graph.
     *
     * @param expectedChatIds the subtree ids captured before cancellation; the transaction aborts
     * if the actual subtree changed, matching the folder-deletion verification pattern.
     */
    suspend fun deleteChatSubtree(
        chatId: String,
        expectedChatIds: Set<String>,
    ): Boolean =
        database.withTransaction {
            if (chatDao.getChatById(chatId) == null) {
                return@withTransaction false
            }
            deleteChatSubtreeInternal(chatId, expectedChatIds)
        }

    private suspend fun deleteChatSubtreeInternal(
        chatId: String,
        expectedChatIds: Set<String>? = null,
    ): Boolean {
        val allChats = chatDao.getAllChatsDirectly()
        val byId = allChats.associateBy { it.id }
        val subtree = subtreeIds(chatId, childrenById(allChats))
        if (expectedChatIds != null) {
            check(subtree == expectedChatIds) {
                "Chat deletion candidates changed while preparing deletion"
            }
        }
        if (subtree.any { byId.getValue(it).locked }) {
            return false
        }
        ChatDeletionGraphPolicy.orderChildFirst(subtree.map { byId.getValue(it) })
            .forEach { chat -> chatDao.deleteChat(chat.id) }
        return true
    }

    private fun childrenById(allChats: List<ChatEntity>): Map<String?, List<String>> =
        allChats.groupBy(keySelector = { it.parentChatId }, valueTransform = { it.id })

    private fun subtreeIds(
        rootId: String,
        childrenById: Map<String?, List<String>>,
    ): LinkedHashSet<String> =
        ChatDeletionGraphPolicy.descendantClosure(rootId, childrenById)

    companion object {
        @Volatile
        private var INSTANCE: SubagentRunRepository? = null

        fun getInstance(context: Context): SubagentRunRepository =
            INSTANCE
                ?: synchronized(this) {
                    INSTANCE
                        ?: SubagentRunRepository(
                                AppDatabase.getDatabase(context.applicationContext)
                            )
                            .also { INSTANCE = it }
                }
    }
}

internal object SubagentRunStateMachine {
    private val active =
        setOf(
            SubagentRunStatus.CREATED,
            SubagentRunStatus.QUEUED,
            SubagentRunStatus.RUNNING,
        )
    private val terminal = SubagentRunStatus.entries.toSet() - active

    fun allowedOrigins(target: SubagentRunStatus): Set<SubagentRunStatus> =
        when (target) {
            SubagentRunStatus.CREATED -> emptySet()
            SubagentRunStatus.QUEUED ->
                setOf(SubagentRunStatus.CREATED) + terminal
            SubagentRunStatus.RUNNING ->
                setOf(SubagentRunStatus.CREATED, SubagentRunStatus.QUEUED) + terminal
            SubagentRunStatus.COMPLETED ->
                setOf(SubagentRunStatus.RUNNING)
            SubagentRunStatus.FAILED,
            SubagentRunStatus.CANCELLED,
            SubagentRunStatus.INTERRUPTED -> active
        }
}

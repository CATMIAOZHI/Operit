package com.ai.assistance.operit.data.repository

import androidx.room.withTransaction
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import java.util.UUID

internal data class ChatBranchCopyResult(
    val branch: ChatEntity,
    val copiedMessageCount: Int,
    val copiedSubagentCount: Int,
)

/**
 * Copies one persisted conversation branch together with the Subagent chats referenced by the
 * copied transcript.
 *
 * Tool call IDs are scoped by parent chat, so they remain unchanged in the copied messages and
 * runs. Chat and run IDs are always regenerated to keep the branch graph independent.
 */
internal class ChatBranchRepository(
    private val database: AppDatabase,
) {
    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()
    private val messageVariantDao = database.messageVariantDao()
    private val subagentRunDao = database.subagentRunDao()

    suspend fun copyBranch(
        sourceChatId: String,
        branch: ChatEntity,
        upToTimestampInclusive: Long?,
    ): ChatBranchCopyResult =
        database.withTransaction {
            requireNotNull(chatDao.getChatById(sourceChatId)) {
                "Source chat does not exist: $sourceChatId"
            }
            chatDao.insertChat(branch)

            val copiedMessageCount =
                messageDao.countMessagesForChatUpToTimestamp(
                    sourceChatId,
                    upToTimestampInclusive,
                )
            if (copiedMessageCount > 0) {
                copyMessagesAndVariants(
                    sourceChatId = sourceChatId,
                    targetChatId = branch.id,
                    upToTimestampInclusive = upToTimestampInclusive,
                )
            }
            val copiedSubagentCount =
                cloneReferencedSubagentTree(
                    sourceParentChatId = sourceChatId,
                    targetParentChatId = branch.id,
                    upToTimestampInclusive = upToTimestampInclusive,
                    copiedSourceChatIds = mutableSetOf(sourceChatId),
                )
            chatDao.recalculateLastMessageAt(branch.id)

            ChatBranchCopyResult(
                branch = requireNotNull(chatDao.getChatById(branch.id)),
                copiedMessageCount = copiedMessageCount,
                copiedSubagentCount = copiedSubagentCount,
            )
        }

    private suspend fun cloneReferencedSubagentTree(
        sourceParentChatId: String,
        targetParentChatId: String,
        upToTimestampInclusive: Long?,
        copiedSourceChatIds: MutableSet<String>,
    ): Int {
        val referencedCallIds =
            extractPersistedToolCallIds(
                buildList {
                    addAll(
                        messageDao
                            .getMessagesForChatInRangeAsc(
                                chatId = sourceParentChatId,
                                afterTimestampExclusive = null,
                                beforeTimestampExclusive = null,
                                upToTimestampInclusive = upToTimestampInclusive,
                            )
                            .map { it.content }
                    )
                    addAll(
                        messageVariantDao
                            .getVariantsForChat(sourceParentChatId)
                            .asSequence()
                            .filter {
                                upToTimestampInclusive == null ||
                                    it.messageTimestamp <= upToTimestampInclusive
                            }
                            .map { it.content }
                    )
                }
            )
        if (referencedCallIds.isEmpty()) return 0

        var copiedCount = 0
        subagentRunDao
            .getByParentChatId(sourceParentChatId)
            .filter { run -> run.parentToolCallId in referencedCallIds }
            .forEach { sourceRun ->
                val sourceChild = requireNotNull(chatDao.getChatById(sourceRun.childChatId)) {
                    "Subagent child chat does not exist: ${sourceRun.childChatId}"
                }
                if (!copiedSourceChatIds.add(sourceChild.id)) {
                    return@forEach
                }

                val childCopy =
                    sourceChild.copy(
                        id = UUID.randomUUID().toString(),
                        parentChatId = targetParentChatId,
                        chatKind = ChatKind.SUBAGENT.name,
                    )
                chatDao.insertChat(childCopy)
                copyMessagesAndVariants(
                    sourceChatId = sourceChild.id,
                    targetChatId = childCopy.id,
                    upToTimestampInclusive = null,
                )
                chatDao.recalculateLastMessageAt(childCopy.id)
                subagentRunDao.insert(
                    sourceRun.copyForBranch(
                        branchParentChatId = targetParentChatId,
                        branchChildChatId = childCopy.id,
                        snapshotAt = System.currentTimeMillis(),
                    )
                )
                copiedCount++
                copiedCount +=
                    cloneReferencedSubagentTree(
                        sourceParentChatId = sourceChild.id,
                        targetParentChatId = childCopy.id,
                        upToTimestampInclusive = null,
                        copiedSourceChatIds = copiedSourceChatIds,
                    )
            }
        return copiedCount
    }

    private suspend fun copyMessagesAndVariants(
        sourceChatId: String,
        targetChatId: String,
        upToTimestampInclusive: Long?,
    ) {
        messageDao.copyMessagesToChat(
            sourceChatId = sourceChatId,
            targetChatId = targetChatId,
            upToTimestampInclusive = upToTimestampInclusive,
        )
        messageVariantDao.copyVariantsToChat(
            sourceChatId = sourceChatId,
            targetChatId = targetChatId,
            upToTimestampInclusive = upToTimestampInclusive,
        )
    }
}

private val ACTIVE_SUBAGENT_RUN_STATUS_NAMES =
    setOf(
        SubagentRunStatus.CREATED.name,
        SubagentRunStatus.QUEUED.name,
        SubagentRunStatus.RUNNING.name,
    )

private const val BRANCH_SNAPSHOT_INTERRUPTED_ERROR =
    "This Subagent run was interrupted because its parent chat was branched."

internal fun SubagentRunEntity.copyForBranch(
    branchParentChatId: String,
    branchChildChatId: String,
    snapshotAt: Long,
): SubagentRunEntity {
    val activeSnapshot = status in ACTIVE_SUBAGENT_RUN_STATUS_NAMES
    return copy(
        id = UUID.randomUUID().toString(),
        parentChatId = branchParentChatId,
        childChatId = branchChildChatId,
        status = if (activeSnapshot) SubagentRunStatus.INTERRUPTED.name else status,
        completedAt = if (activeSnapshot) snapshotAt else completedAt,
        error = if (activeSnapshot) BRANCH_SNAPSHOT_INTERRUPTED_ERROR else error,
    )
}

private val persistedToolCallIdAttribute =
    Regex(
        """\bcall_id\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

internal fun extractPersistedToolCallIds(contents: Iterable<String>): Set<String> =
    contents
        .asSequence()
        .flatMap { content ->
            persistedToolCallIdAttribute
                .findAll(content)
                .mapNotNull { match -> match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank) }
        }
        .toSet()

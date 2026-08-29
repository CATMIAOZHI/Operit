package com.ai.assistance.operit.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SubagentRunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
}

@Entity(
    tableName = "subagent_runs",
    indices = [
        Index(value = ["parentChatId"]),
        Index(value = ["childChatId"], unique = true),
        Index(value = ["parentChatId", "parentToolCallId"]),
        Index(value = ["status"]),
        Index(value = ["externalOwnerType", "externalOwnerId"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentChatId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["childChatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SubagentRunEntity(
    @PrimaryKey val id: String,
    val parentChatId: String,
    val childChatId: String,
    val parentToolCallId: String? = null,
    val agentProfileId: String,
    val title: String,
    val status: String = SubagentRunStatus.CREATED.name,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val error: String? = null,
    val agentConfigSnapshot: String? = null,
    val modelConfigIdSnapshot: String? = null,
    val modelIndexSnapshot: Int? = null,
    val toolInvocationCount: Int = 0,
    /** 外部跨库弱关联所有者类型（阅读伴侣：reading_companion_run）；可空表示普通任务。 */
    val externalOwnerType: String? = null,
    /** 外部跨库弱关联所有者 ID（阅读伴侣：reading companion run id 字符串）。 */
    val externalOwnerId: String? = null,
    /** 已执行的模型轮次计数（原子递增，作为审计展示；不是总量上限）。 */
    val modelRoundCount: Int = 0,
    val archivedAt: Long? = null,
)

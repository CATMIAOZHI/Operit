package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 旧 DataStore 累计键清理 outbox operation（阶段 5 P1 闭环）。
 *
 * 跨存储删除的**唯一线性化点**是 Room 删除事务：同一事务内解析展示组成员
 * 快照、写 tombstone、删事件、删 baseline，并且**只对旧系统迁移身份**
 * （configId 为空串，其累计键按 provider:model 共享且是 baseline 的迁移源）
 * 持久化本表 operation + [TokenStatCleanupItemEntity] 不可变快照
 * （operationId + identityId + provider + model）。事务读取失败整体回滚，
 * 不会产生半删除或丢失清理信号。
 *
 * 状态机（PENDING → APPLIED）：
 * - PENDING：Room 删除已提交，DataStore 累计键尚未清理（或尚未确认）；
 * - APPLIED：drain 已在 DataStore 单次 edit 内清键并写入 marker 后 ACK。
 * 失败保持 PENDING，下次冷启动（baseline 导入之前 / pending restore 之前）
 * 与删除后立即重试排空。历史行不删除：作为导入 fence（快照 marker 校验）
 * 与备份 lineage 使用。
 *
 * scope：
 * - [SCOPE_DISPLAY_GROUP]：按展示分组删除（targetRef = displayModelId），
 *   items 精确到该组 configId 为空的成员；
 * - [SCOPE_MODEL]：按 provider:model 重置（targetRef = provider:model），
 *   items 精确到匹配的 configId 为空成员；
 * - [SCOPE_ALL]：全量删除（targetRef 为空串），无 items，
 *   apply 时清除全部旧累计键（不触碰价格等配置）。
 */
@Entity(tableName = "token_stat_cleanup_operations")
data class TokenStatCleanupOperationEntity(
    @PrimaryKey @ColumnInfo(name = "operationId") val operationId: String,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "targetRef") val targetRef: String,
    @ColumnInfo(name = "deleteBaselines") val deleteBaselines: Boolean,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "createdAtMs") val createdAtMs: Long,
) {
    companion object {
        const val SCOPE_DISPLAY_GROUP = "DISPLAY_GROUP"
        const val SCOPE_MODEL = "MODEL"
        const val SCOPE_ALL = "ALL"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPLIED = "APPLIED"
    }
}

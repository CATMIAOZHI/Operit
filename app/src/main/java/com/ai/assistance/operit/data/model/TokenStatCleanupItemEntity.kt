package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * legacy cleanup operation 的不可变成员快照（阶段 5 P1 闭环）。
 *
 * 在创建 operation 的**同一个 Room 删除事务**内从 identity 全表快照解析，
 * 只包含 configId 为空串的旧系统迁移身份；provider/model 为不可变快照
 * （成员后续移动/删除不影响已登记的清理目标）。外键级联保证 operation
 * 删除时 items 跟随清理（生产流程保留历史，不主动删除）。
 */
@Entity(
    tableName = "token_stat_cleanup_items",
    primaryKeys = ["operationId", "identityId"],
    foreignKeys = [
        ForeignKey(
            entity = TokenStatCleanupOperationEntity::class,
            parentColumns = ["operationId"],
            childColumns = ["operationId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["operationId"])],
)
data class TokenStatCleanupItemEntity(
    @ColumnInfo(name = "operationId") val operationId: String,
    @ColumnInfo(name = "identityId") val identityId: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "model") val model: String,
)

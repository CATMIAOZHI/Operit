package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已应用的备份恢复 generation（受控补导的幂等锚点）。
 *
 * 真实备份恢复流程（RawSnapshotBackupManager 成功路径）会写一个 pending
 * 标记（noBackupFilesDir，不随恢复覆盖）；下次冷启动消费该标记时，在同一
 * Room 事务内执行受控强制补导并把 generation 写入本表。相同 generation
 * 已存在则跳过补导（崩溃后标记未清除时重放安全）。
 */
@Entity(tableName = "token_stat_restore_generations")
data class TokenStatRestoreGenerationEntity(
    @PrimaryKey @ColumnInfo(name = "generation") val generation: String,
    @ColumnInfo(name = "appliedAtMs") val appliedAtMs: Long,
)

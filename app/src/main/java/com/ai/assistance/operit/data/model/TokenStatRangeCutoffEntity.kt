package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 统计时间范围删除 tombstone（阶段 5）—— 范围删除与 spool 排空的一致同步边界。
 *
 * - 每次“删除时间范围事件”在同一 Room 事务内写入一行（generation 为主键：
 *   由 [com.ai.assistance.operit.data.dao.TokenStatsDao.currentResetGeneration]
 *   跨 reset/range 两表统一递增，任意两次删除/重置都不会撞 generation）。
 * - 排空插入事件时（[com.ai.assistance.operit.data.dao.TokenStatsDao.insertEventIfNotResetCovered]），
 *   `acceptedGeneration < generation && startedAtMs ∈ [startMs, endMs)` 的事件跳过，
 *   不复活已删除范围的数据；删除后新接受的事件（acceptedGeneration ≥ generation）
 *   即使落在同一范围内也正常入账。
 * - 范围删除**只**删除有时间戳的事件，绝不触碰 baseline/身份/分组/价格覆盖。
 * - 行数 = 用户范围删除次数（每行 24 字节级），全量重置时随其他 tombstone 一并清理。
 */
@Entity(tableName = "token_stat_range_cutoffs")
data class TokenStatRangeCutoffEntity(
    @PrimaryKey @ColumnInfo(name = "generation") val generation: Long,
    @ColumnInfo(name = "startMs") val startMs: Long,
    @ColumnInfo(name = "endMs") val endMs: Long,
)

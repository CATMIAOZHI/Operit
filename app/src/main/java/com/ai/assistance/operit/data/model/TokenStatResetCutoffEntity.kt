package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 统计重置 tombstone（reset cutoff）—— reset 与 spool 排空的一致同步边界（P1-3）。
 *
 * - FULL 行（kind = "FULL"，provider/model 为空串）：最近一次全量重置 generation。
 *   排空插入事件时，事件 acceptedGeneration < FULL.generation 则跳过（不复活）。
 * - MODEL 行（kind = "MODEL"）：最近一次按模型重置时刻，每 (provider, model)
 *   至多一行（REPLACE 覆盖）。
 * - 写入与删除在同一 Room 事务提交（见
 *   [com.ai.assistance.operit.data.dao.TokenStatsDao.resetAllStatisticsTx] /
 *   [com.ai.assistance.operit.data.dao.TokenStatsDao.resetModelTx]）；排空插入在
 *   同一事务内检查 tombstone（[com.ai.assistance.operit.data.dao.TokenStatsDao.insertEventIfNotResetCovered]）。
 *   SQLite 事务串行化保证“检查-插入”与“写 tombstone-删除”不交错：并发中已接受
 *   但未入 Room 的事件在 reset 后不会复活。
 * generation 由 Room 事务从所有 tombstone 的最大值递增产生，跨重启持久且不受
 * 同毫秒事件或设备时间回拨影响。
 */
@Entity(
    tableName = "token_stat_reset_cutoffs",
    primaryKeys = ["kind", "provider", "model"],
)
data class TokenStatResetCutoffEntity(
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "generation") val generation: Long,
) {
    companion object {
        const val KIND_FULL = "FULL"
        const val KIND_MODEL = "MODEL"
    }
}

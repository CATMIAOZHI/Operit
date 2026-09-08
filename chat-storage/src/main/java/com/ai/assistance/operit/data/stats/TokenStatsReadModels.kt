package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity

/**
 * Fixed small-table portion of a range snapshot. Events are delivered page-by-page by
 * [com.ai.assistance.operit.data.dao.TokenStatsDao.loadRangeSnapshotPaged] in the same Room
 * transaction, so the caller never retains the full range event list.
 */
data class TokenStatsRangeRead(
    val identitiesById: Map<String, TokenStatIdentityEntity>,
    val displayModelsById: Map<String, TokenStatDisplayModelEntity>,
    val overrides: List<TokenStatPriceOverrideEntity>,
)

/**
 * 生命周期快照的固定小表部分（P1-2/P2-1）：identity/display model/价格覆盖/
 * baseline 在同一事务内一次读取；事件不实体化——由
 * [com.ai.assistance.operit.data.dao.TokenStatsDao.loadLifetimeSnapshot] 按
 * `(startedAtMs, eventId)` 键集分页逐页回调增量累加器（每页至多 [pageSize]），
 * 避免整表实体化的内存峰值，且分页与事务同界（页面间快照一致）。
 */
data class TokenStatsLifetimeRead(
    val identitiesById: Map<String, TokenStatIdentityEntity>,
    val displayModelsById: Map<String, TokenStatDisplayModelEntity>,
    val overrides: List<TokenStatPriceOverrideEntity>,
    val baselines: List<TokenStatBaselineEntity>,
    val totalEvents: Long,
)

/**
 * 分组元数据快照（阶段 4 P1 修复）：全量身份 + 展示模型行在**同一个 Room 事务**内
 * 固定读取（[com.ai.assistance.operit.data.dao.TokenStatsDao.loadGroupMetadataSnapshot]），
 * 与统计筛选无关；事务外由设置管理器构建 分组展示模型。
 * 并发分组变更要么整体可见要么整体不可见。
 */
data class TokenStatsGroupMetadataSnapshot(
    val identities: List<TokenStatIdentityEntity>,
    val displayModels: List<TokenStatDisplayModelEntity>,
)

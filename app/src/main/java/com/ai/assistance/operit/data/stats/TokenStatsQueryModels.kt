package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity

/**
 * 统计查询领域模型（阶段 3，供阶段 4 UI/ViewModel 直接复用）。
 *
 * unknown 与 0 的约定贯穿全部聚合结果：
 * - token/价格/成本为 null 表示“未知”，**不得静默当作 0**；
 * - 0 表示 provider 确认该分量为 0（如确认无缓存读取）。
 * 聚合层用 [TokenStatsTokenAggregate]/[TokenStatsCostSummary] 显式携带
 * known/unknown 计数，部分未知可表达，绝不伪装成精确 0。
 */

/** 费用口径：事件历史快照（账单价）或当前分层价格重估。 */
enum class TokenStatsCostMode {
    /** 使用事件保存的“发生时”价格快照与原币成本（默认口径）。 */
    HISTORICAL,

    /** 使用当前分层价格（覆盖 > 旧系统价格 > 内置默认价）× 事件用量重算。 */
    REVALUED,
}

/**
 * 聚合参数。汇率只由用户手动设置；未设置时调用方传入默认估算值
 * [TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE] 并标记 [rateIsEstimated] = true
 * （界面必须显示估算提示）。修改汇率只改变统一币种换算，不改变历史原币成本。
 */
data class TokenStatsQueryParams(
    /** 总计展示目标币种。 */
    val targetCurrency: PricingCurrency = PricingCurrency.CNY,
    /** 当前手动 USD→CNY 汇率；必须为正。 */
    val manualRate: Double = TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE,
    /** true = 汇率是默认估算值（非用户手动设置），界面必须标记估算。 */
    val rateIsEstimated: Boolean = true,
    /** 费用口径：历史快照（默认）或当前价格重估。 */
    val mode: TokenStatsCostMode = TokenStatsCostMode.HISTORICAL,
    /** 展示模型筛选（identity.displayModelId）；null = 全部。 */
    val displayModelIds: Set<String>? = null,
    /** 业务分类筛选；null = 全部分类。 */
    val categories: Set<TokenStatCategory>? = null,
    /** 请求状态筛选（阶段 4）；null = 全部状态。 */
    val statuses: Set<TokenStatStatus>? = null,
) {
    init {
        require(manualRate > 0.0) { "manual rate must be positive" }
    }
}

/**
 * 费用合计。已知部分按目标币种汇总（BigDecimal 累加，无 Double 漂移），
 * 原币金额按币种分别保存（供 UI 按原币/模型堆叠）；无法定价的贡献
 * （unknown）计数保留，[isFullyKnown] = false 表示结果为部分未知。
 */
data class TokenStatsCostSummary(
    /** 目标展示币种。 */
    val currency: PricingCurrency,
    /** 已知贡献合计（目标币种，BigDecimal 累加结果）。 */
    val knownAmount: Double,
    /** 无法定价（unknown）的贡献条数；不为 0 时结果是 partial，不能当精确账单。 */
    val unknownContributionCount: Long,
    /** 参与合计的贡献条数（事件数或 baseline 行数）。 */
    val totalContributionCount: Long,
    /** 本次换算使用的 USD→CNY 汇率。 */
    val rateUsed: Double,
    /** true = 汇率是默认估算值。 */
    val rateIsEstimated: Boolean,
    /** 费用口径。baseline 无论参数如何都按冻结快照（HISTORICAL）展示。 */
    val mode: TokenStatsCostMode,
    /** 原币已知合计（仅含 > 0 币种，供堆叠/分币种展示）。 */
    val originalCurrencyAmounts: Map<PricingCurrency, Double>,
) {
    val isFullyKnown: Boolean
        get() = unknownContributionCount == 0L

    val hasAnyContribution: Boolean
        get() = totalContributionCount > 0L
}

/**
 * 单分量 token 合计：已知事件求和（Long 饱和加法，绝不回绕为负），
 * 未知事件单独计数，区分“全部已知”与“部分未知”。
 */
data class TokenStatsTokenAggregate(
    /** 已知分量的和（饱和加法，上限 Long.MAX_VALUE）。 */
    val knownSum: Long,
    /** 该分量已知的事件数。 */
    val knownEventCount: Long,
    /** 该分量未知（provider 未上报）的事件数；0 与未知严格区分。 */
    val unknownEventCount: Long,
    /** 参与合计的事件总数。 */
    val totalEventCount: Long,
) {
    val isFullyKnown: Boolean
        get() = unknownEventCount == 0L
}

/**
 * 时长聚合（首 Token 延迟 / 生成时长）。
 * 无效时长（时间戳缺失、结束早于开始、负数时间戳）一律记为 unknown，
 * 不进平均；knownCount == 0 时 [averageMs] 为 0（调用方应展示“无数据”而非均值）。
 */
data class TokenStatsDurationAggregate(
    val knownCount: Long,
    val unknownCount: Long,
    /** 已知时长总和（毫秒，饱和加法）。 */
    val totalMs: Long,
    /** 平均时长（毫秒）；knownCount == 0 时为 0.0。 */
    val averageMs: Double,
) {
    val hasData: Boolean
        get() = knownCount > 0L
}

/** 性能指标：首 Token 延迟（TTFT）与生成时长（首个 token 到结束）。 */
data class TokenStatsPerformance(
    val ttft: TokenStatsDurationAggregate,
    val generationDuration: TokenStatsDurationAggregate,
)

/** 一组事件（或一个分组）的完整合计。 */
data class TokenStatsTotals(
    val requests: Long,
    val uncachedInput: TokenStatsTokenAggregate,
    val cachedInput: TokenStatsTokenAggregate,
    val cacheWrite: TokenStatsTokenAggregate,
    val totalInput: TokenStatsTokenAggregate,
    val output: TokenStatsTokenAggregate,
    val reasoning: TokenStatsTokenAggregate,
    val cost: TokenStatsCostSummary,
)

/** 旧数据迁移 baseline 的生命周期合计（费用按迁移时冻结快照，恒为估算口径）。 */
data class TokenStatsBaselineTotals(
    /** baseline 行数（身份数）。 */
    val identityCount: Long,
    val requests: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val cost: TokenStatsCostSummary,
    /** 任一行估算标记为 true 即 true（baseline 本身就是估算，正常恒为 true）。 */
    val anyEstimated: Boolean,
)

/** 生命周期累计总览（独立于时间/模型/分类筛选；事件 + baseline）。 */
data class TokenStatsLifetimeOverview(
    val eventTotals: TokenStatsTotals,
    val baselineTotals: TokenStatsBaselineTotals,
    /** 事件 + baseline 请求数合计（饱和加法）。 */
    val combinedRequests: Long,
)

/** 单个图表桶：半开区间 [bucketStartMs, bucketEndMs)，含按展示模型拆分。 */
data class TokenStatsTrendBucket(
    val bucketStartMs: Long,
    val bucketEndMs: Long,
    val totals: TokenStatsTotals,
    /** displayModelId -> 桶内该展示模型的合计（费用堆叠按模型/原币）。 */
    val byModel: Map<String, TokenStatsModelBucket>,
    val performance: TokenStatsPerformance,
)

/** 桶内单个展示模型的合计（token 为已知分量和，unknown 事件单独计数）。 */
data class TokenStatsModelBucket(
    val requests: Long,
    val uncachedInput: Long,
    val cachedInput: Long,
    val cacheWrite: Long,
    val output: Long,
    val reasoning: Long,
    /** 任一核心 token 分量（uncached/cached/output）未知的事件数。 */
    val unknownTokenEventCount: Long,
    val cost: TokenStatsCostSummary,
)

/** 单价的展示信息：历史口径取该身份最近事件的价格快照；重估口径取当前解析。 */
data class TokenStatsPricingInfo(
    val billingMode: BillingMode,
    val currency: PricingCurrency,
    val inputPricePerMillion: Double?,
    val cachedInputPricePerMillion: Double?,
    val cacheWritePricePerMillion: Double?,
    val outputPricePerMillion: Double?,
    val pricePerRequest: Double?,
    val source: PricingSource,
    val known: Boolean,
)

/** 单个统计身份（configId+provider+model）的展开明细。 */
data class TokenStatsIdentityBreakdown(
    val identityId: String,
    val configId: String,
    val provider: String,
    val model: String,
    val totals: TokenStatsTotals,
    val pricing: TokenStatsPricingInfo?,
)

/** 展示模型分组明细：默认规范化同名归组 + 用户手动别名（identity.displayModelId）。 */
data class TokenStatsDisplayModelBreakdown(
    val displayModelId: String,
    val displayName: String,
    val normalizedModel: String,
    val totals: TokenStatsTotals,
    val identities: List<TokenStatsIdentityBreakdown>,
)

/**
 * 展示分组完整元数据（阶段 4 P1 修复）：与统计筛选（时间/模型/分类/状态）完全
 * 无关的分组成员/合并目标事实来源。identity.displayModelId 是分组的单一事实
 * 来源；成员 = 全量身份按 displayModelId 分组（事件存在与否不影响成员身份），
 * 组名取 display_models 行（缺失时回退 displayModelId）。筛选范围明细
 * （[TokenStatsDisplayModelBreakdown]）只包含当前筛选下有事件的身份/分组，
 * 不得作为分组操作的成员或目标依据。
 */
data class TokenStatsGroupModelInfo(
    val displayModelId: String,
    val displayName: String,
    /** 该分组下的全部身份 id（完整归属，非当前筛选范围所见）。 */
    val memberIdentityIds: List<String>,
)

/** 业务分类合计。 */
data class TokenStatsCategoryBreakdown(
    val category: TokenStatCategory,
    val totals: TokenStatsTotals,
)

/** 请求状态合计。 */
data class TokenStatsStatusBreakdown(
    val status: TokenStatStatus,
    val totals: TokenStatsTotals,
)

/**
 * 指定时间范围的完整查询结果：范围总计、性能、趋势桶（补齐空桶、
 * 桶合计 == 范围总计）、展示模型/身份、分类、状态明细。
 * baseline 无时间分布，永不进入范围数据。
 */
data class TokenStatsRangeData(
    val range: TokenStatsTimeRange,
    val granularity: TokenStatsGranularity,
    val eventCount: Long,
    val summary: TokenStatsTotals,
    val performance: TokenStatsPerformance,
    val buckets: List<TokenStatsTrendBucket>,
    val displayModels: List<TokenStatsDisplayModelBreakdown>,
    val categories: List<TokenStatsCategoryBreakdown>,
    val statuses: List<TokenStatsStatusBreakdown>,
)

/**
 * 范围查询的同事务只读快照（P1-2）：identity/display model/价格覆盖/事件由
 * [com.ai.assistance.operit.data.dao.TokenStatsDao.loadRangeSnapshot] 在**同一个
 * Room 事务**内固定读取（SQLite 事务内快照一致），事务外由聚合器纯函数消费。
 * 并发写入要么整体可见要么整体不可见，杜绝“summary 有事件但模型桶缺失”的
 * 拆分状态；查询期间不重复取 DAO。
 */
data class TokenStatsQuerySnapshot(
    val events: List<TokenStatEventEntity>,
    val identitiesById: Map<String, TokenStatIdentityEntity>,
    val displayModelsById: Map<String, TokenStatDisplayModelEntity>,
    val overrides: List<TokenStatPriceOverrideEntity>,
    val baselines: List<TokenStatBaselineEntity>,
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
 * 与统计筛选无关；事务外由设置管理器构建 [TokenStatsGroupModelInfo]。
 * 快照一致性原则同 [TokenStatsQuerySnapshot]（并发分组变更要么整体可见要么
 * 整体不可见）。
 */
data class TokenStatsGroupMetadataSnapshot(
    val identities: List<TokenStatIdentityEntity>,
    val displayModels: List<TokenStatDisplayModelEntity>,
)

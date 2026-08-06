package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.ApiPreferences
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统计查询服务（阶段 3）：同事务只读快照 + [TokenStatsAggregator] 单遍聚合。
 *
 * 查询策略（防 N+1 / 一致快照）：
 * - 组成响应的**全部 Room 读取**（identity/display model/价格覆盖/事件/baseline）
 *   在**同一个 Room 事务**内固定读取（[TokenStatsDao.loadRangeSnapshot] /
 *   [TokenStatsDao.loadLifetimeSnapshot]），事务外纯聚合：并发写入要么整体可见
 *   要么整体不可见，杜绝“summary 有事件但模型桶缺失”的拆分状态（P1-2）；
 * - 展示模型筛选在快照事务内走 JOIN 单条 IN 查询，模型数超过 900（SQLite 变量
 *   上限留余量）时在**同一事务**内分块合并（P2-2）；null = 全部、空 = 无事件；
 * - 生命周期总览不整表实体化：事件按 `(startedAtMs, eventId)` 键集分页
 *   （每页 [lifetimeEventPageSize] 条）在同事务内喂给增量累加器（P2-1）；
 * - 重估口径的旧系统价格（DataStore）**先读一次快照**（
 *   [ApiPreferences.allLegacyPriceSettings]），再进入 Room 快照事务；Room 事务
 *   不能挂起 DataStore，先后顺序明确——价格只影响重估，不影响事件结构（P1-2）。
 *
 * 线程（P2-3）：所有公共入口显式 [withContext] 到 [queryDispatcher]（默认
 * [Dispatchers.IO]，测试可注入），阶段 4 Main 调用不阻塞。
 *
 * 汇率与币种：聚合接收当前手动 USD/CNY 汇率（默认
 * [TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE] = 7.0 并标记 estimated），
 * 按目标币种换算；事件原币成本永远不变。
 */
object TokenStatsQueryService {

    internal var databaseProvider: ((Context) -> AppDatabase)? = null

    /**
     * 旧系统价格注入缝（一次快照读取整表）：生产走
     * [ApiPreferences.allLegacyPriceSettings]（单次 DataStore 读取）；测试注入桩，
     * 避免触碰真实 DataStore。
     */
    internal var legacyPricesProvider: (suspend (Context) -> Map<String, LegacyPriceSettings?>)? = null

    /** Room 查询 + 聚合的执行线程（P2-3）：生产默认 IO，测试可注入记录线程的调度器。 */
    internal var queryDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** 生命周期事件分页大小（P2-1）：固定批次读取 + 增量聚合，避免整表实体化峰值。 */
    internal var lifetimeEventPageSize: Int = 1_000

    // ==== 核心查询（DAO 直连，生产与测试共用） ====

    /** 生命周期累计总览（事件 + baseline，独立于筛选；事件分页增量聚合，不整表实体化）。 */
    suspend fun lifetimeOverview(
        dao: TokenStatsDao,
        params: TokenStatsQueryParams,
        legacyPrices: Map<String, LegacyPriceSettings?> = emptyMap(),
    ): TokenStatsLifetimeOverview {
        val accumulator = TokenStatsAggregator.TokenStatsEventTotalsAccumulator(legacyPrices, params)
        val read =
            dao.loadLifetimeSnapshot(
                includeOverrides = params.mode == TokenStatsCostMode.REVALUED,
                pageSize = lifetimeEventPageSize,
                onEventsPage = { page, identities, overrides ->
                    accumulator.addPage(page, identities, overrides)
                },
            )
        return TokenStatsAggregator.lifetimeFrom(
            eventsTotals = accumulator.totals(),
            baselines = read.baselines,
            params = params,
        )
    }

    /**
     * 指定时间范围的完整查询（汇总 + 趋势桶 + 模型/分类/状态明细）。
     * 全部 Room 读取在 [TokenStatsDao.loadRangeSnapshot] 同一事务快照内；
     * 粒度按范围时长由 [TokenStatsTimeRanges.granularityFor] 选择。
     */
    suspend fun rangeData(
        dao: TokenStatsDao,
        range: TokenStatsTimeRange,
        params: TokenStatsQueryParams,
        zone: ZoneId,
        legacyPrices: Map<String, LegacyPriceSettings?> = emptyMap(),
    ): TokenStatsRangeData {
        val snapshot = dao.loadRangeSnapshot(
            startMs = range.startMs,
            endMs = range.endMs,
            displayModelIds = params.displayModelIds?.toList(),
            includeOverrides = params.mode == TokenStatsCostMode.REVALUED,
        )
        return TokenStatsAggregator.rangeData(
            events = snapshot.events,
            identitiesById = snapshot.identitiesById,
            displayModelsById = snapshot.displayModelsById,
            overrides = snapshot.overrides,
            legacyPrices = legacyPrices,
            range = range,
            granularity = TokenStatsTimeRanges.granularityFor(range),
            zone = zone,
            params = params,
        )
    }

    /** 时间范围内是否存在事件（初始回退探测，每条都是索引 EXISTS 短路查询）。 */
    suspend fun rangeHasEvents(dao: TokenStatsDao, range: TokenStatsTimeRange): Boolean =
        dao.rangeHasEvents(range.startMs, range.endMs)

    /**
     * 首次进入的初始回退建议：按 `5h -> 12h -> 24h -> 7d -> 30d` 顺序返回
     * 最近有实际事件的范围；全部为空时返回 5h。
     * “用户手选后不再自动跳转”由调用方（阶段 4 UI/ViewModel）持久化，
     * 本函数只计算首次建议，不改变任何状态。
     */
    suspend fun initialPresetWithData(
        dao: TokenStatsDao,
        zone: ZoneId,
        nowMs: Long,
    ): TokenStatsPreset {
        for (preset in TokenStatsPreset.INITIAL_FALLBACK_ORDER) {
            val range = TokenStatsTimeRanges.rangeFor(preset, nowMs, zone)
            if (dao.rangeHasEvents(range.startMs, range.endMs)) return preset
        }
        return TokenStatsPreset.LAST_5H
    }

    // ==== 生产入口（Context 解析数据库/旧价格；汇率由调用方在 params 中提供） ====

    suspend fun lifetimeOverview(context: Context, params: TokenStatsQueryParams): TokenStatsLifetimeOverview =
        withDatabaseAndLegacyPrices(context, params) { dao, legacyPrices ->
            lifetimeOverview(dao, params, legacyPrices)
        }

    suspend fun rangeData(
        context: Context,
        range: TokenStatsTimeRange,
        params: TokenStatsQueryParams,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TokenStatsRangeData =
        withDatabaseAndLegacyPrices(context, params) { dao, legacyPrices ->
            rangeData(dao, range, params, zone, legacyPrices)
        }

    suspend fun presetRangeData(
        context: Context,
        preset: TokenStatsPreset,
        params: TokenStatsQueryParams,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): TokenStatsRangeData =
        withDatabaseAndLegacyPrices(context, params) { dao, legacyPrices ->
            rangeData(
                dao,
                TokenStatsTimeRanges.rangeFor(preset, nowMs, zone),
                params,
                zone,
                legacyPrices,
            )
        }

    suspend fun initialPresetWithData(
        context: Context,
        zone: ZoneId = ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): TokenStatsPreset =
        withContext(queryDispatcher) {
            initialPresetWithData(daoOf(context), zone, nowMs)
        }

    /**
     * 生产入口统一骨架（P1-2/P2-3）：
     * 1. 显式切到 [queryDispatcher] 执行 Room + 聚合（Main 不阻塞）；
     * 2. **先**读旧价格一次快照（DataStore，Room 事务内不能挂起 DataStore）；
     * 3. **再**解析数据库一次并进入 Room 快照事务；
     * 4. 事务外纯聚合。
     */
    private suspend fun <T> withDatabaseAndLegacyPrices(
        context: Context,
        params: TokenStatsQueryParams,
        block: suspend (TokenStatsDao, Map<String, LegacyPriceSettings?>) -> T,
    ): T {
        val appContext = context.applicationContext
        return withContext(queryDispatcher) {
            val legacyPrices = readLegacyPrices(appContext, params)
            val database =
                databaseProvider?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
            block(database.tokenStatsDao(), legacyPrices)
        }
    }

    private suspend fun daoOf(context: Context): TokenStatsDao {
        val appContext = context.applicationContext
        val injected = databaseProvider
        return (injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)).tokenStatsDao()
    }

    /**
     * 重估口径的旧系统价格：**单次快照读取**整表（不逐 identity 反复读 DataStore）。
     * 顺序契约：必须在 Room 快照事务之前读取（见 [withDatabaseAndLegacyPrices]）。
     */
    private suspend fun readLegacyPrices(
        context: Context,
        params: TokenStatsQueryParams,
    ): Map<String, LegacyPriceSettings?> {
        if (params.mode != TokenStatsCostMode.REVALUED) return emptyMap()
        val injected = legacyPricesProvider
        return if (injected != null) {
            injected(context)
        } else {
            ApiPreferences.getInstance(context).allLegacyPriceSettings()
        }
    }
}

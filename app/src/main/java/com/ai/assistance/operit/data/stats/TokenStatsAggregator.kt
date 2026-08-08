package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import java.math.BigDecimal
import java.math.MathContext
import java.time.ZoneId
import java.util.EnumMap

/**
 * 统计聚合器（阶段 3 核心）：纯函数、对事件列表单遍聚合。
 *
 * - **unknown 与 0 严格区分**：token/成本字段为 null = 未知（provider 未上报），
 *   0 = provider 确认该分量为 0。聚合结果显式携带 known/unknown 计数，
 *   部分未知可表达（[TokenStatsCostSummary.isFullyKnown]），绝不静默当作 0。
 * - **费用**：HISTORICAL 用事件价格快照原币成本（[TokenStatEventEntity.costInPricingCurrency]），
 *   REVALUED 用当前分层价格 × 事件用量重算（[TokenCostCalculator] 同一计费语义，
 *   缓存写入/推理包含边界一致，不重复收费）；原币按币种分别累计，再按当前手动
 *   汇率换算到目标币种（BigDecimal 累加防漂移，Double 只出现在边界）。
 * - **展示模型**：identity.displayModelId 是分组的单一事实来源（默认规范化同名 +
 *   用户手动别名都落在这里）；合并模型合计 == 各身份合计之和 == 范围总计。
 * - **性能**：时长由时间戳安全计算（非负校验 + 结束≥开始），无效样本记为
 *   unknown 排除平均；token 合计用饱和加法，绝不因 Long 溢出出现负数。
 * - 传入的事件列表由调用方（[TokenStatsQueryService]）用同事务快照取回，本层不
 *   执行任何 IO；生命周期总览支持 [TokenStatsEventTotalsAccumulator] 分页增量累加
 *   （P2-1），与单列表路径共用同一数学。
 */
object TokenStatsAggregator {

    private val MC = MathContext.DECIMAL64

    // ==== 生命周期累计总览（事件 + baseline，独立于时间/筛选） ====

    fun lifetime(
        events: List<TokenStatEventEntity>,
        baselines: List<TokenStatBaselineEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        overrides: List<TokenStatPriceOverrideEntity>,
        legacyPrices: Map<String, LegacyPriceSettings?>,
        params: TokenStatsQueryParams,
    ): TokenStatsLifetimeOverview {
        val accumulator = TokenStatsEventTotalsAccumulator(legacyPrices, params)
        accumulator.addPage(events, identitiesById, overrides)
        return lifetimeFrom(accumulator.totals(), baselines, params)
    }

    /**
     * 生命周期总览的增量路径（P2-1）：事件合计由 [TokenStatsEventTotalsAccumulator]
     * 分页累加（DAO 同事务分页喂入，不整表实体化），baseline 在事务外纯聚合。
     * 与单列表路径 [lifetime] 共用同一数学，结果必须逐字段一致。
     */
    fun lifetimeFrom(
        eventsTotals: TokenStatsTotals,
        baselines: List<TokenStatBaselineEntity>,
        params: TokenStatsQueryParams,
    ): TokenStatsLifetimeOverview {
        val baselineTotals = baselineTotalsOf(baselines, params)
        return TokenStatsLifetimeOverview(
            eventTotals = eventsTotals,
            baselineTotals = baselineTotals,
            combinedRequests =
                TokenCostCalculator.saturatedAdd(eventsTotals.requests, baselineTotals.requests),
        )
    }

    /**
     * 生命周期事件合计的增量累加器（P2-1）：DAO 分页回调逐页喂入 [addPage]，
     * 事务结束后 [totals] 产出与单列表聚合完全一致的 [TokenStatsTotals]。
     * 内部沿用 [eventCost]/[buildCostSummary] 的同一计费与换算语义。
     */
    internal class TokenStatsEventTotalsAccumulator(
        private val legacyPrices: Map<String, LegacyPriceSettings?>,
        private val params: TokenStatsQueryParams,
    ) {
        private var pricing: PricingContext? = null
        private var identitiesById: Map<String, TokenStatIdentityEntity> = emptyMap()
        private var requests = 0L
        private val uncached = TokenComponentAccumulator()
        private val cached = TokenComponentAccumulator()
        private val cacheWrite = TokenComponentAccumulator()
        private val totalInput = TokenComponentAccumulator()
        private val output = TokenComponentAccumulator()
        private val reasoning = TokenComponentAccumulator()
        private val totalTokens = TokenComponentAccumulator()
        private val originalCosts = EnumMap<PricingCurrency, BigDecimal>(PricingCurrency::class.java)
        private var costUnknownCount = 0L

        fun addPage(
            events: List<TokenStatEventEntity>,
            identities: Map<String, TokenStatIdentityEntity>,
            overrides: List<TokenStatPriceOverrideEntity>,
        ) {
            if (pricing == null) pricing = PricingContext(overrides, legacyPrices, params)
            identitiesById = identities
            for (event in events) {
                requests = TokenCostCalculator.saturatedAdd(requests, 1L)
                uncached.accept(event.uncachedInputTokens)
                cached.accept(event.cachedInputTokens)
                cacheWrite.accept(event.cacheWriteTokens)
                totalInput.accept(event.totalInputTokens)
                output.accept(event.outputTokens)
                reasoning.accept(independentlyBilledReasoning(event))
                totalTokens.accept(canonicalTotalTokens(event))
                val (amount, currency) =
                    eventCost(event, identities[event.statIdentityId], pricing!!, params)
                if (amount == null || !amount.isFinite()) {
                    costUnknownCount += 1
                } else {
                    originalCosts.merge(currency, BigDecimal(amount)) { left, right -> left.add(right) }
                }
            }
        }

        fun totals(): TokenStatsTotals =
            TokenStatsTotals(
                requests = requests,
                uncachedInput = uncached.aggregate(requests),
                cachedInput = cached.aggregate(requests),
                cacheWrite = cacheWrite.aggregate(requests),
                totalInput = totalInput.aggregate(requests),
                output = output.aggregate(requests),
                reasoning = reasoning.aggregate(requests),
                totalTokens = totalTokens.aggregate(requests),
                cost =
                    buildCostSummary(
                        original = originalCosts,
                        unknownCount = costUnknownCount,
                        totalCount = requests,
                        params = params,
                    ),
            )

        private class TokenComponentAccumulator {
            private var knownSum = 0L
            private var knownCount = 0L
            private var unknownCount = 0L

            fun accept(value: Long?) {
                if (value == null) {
                    unknownCount += 1
                } else {
                    knownCount += 1
                    knownSum = TokenCostCalculator.saturatedAdd(knownSum, value)
                }
            }

            fun aggregate(totalEventCount: Long): TokenStatsTokenAggregate =
                TokenStatsTokenAggregate(
                    knownSum = knownSum,
                    knownEventCount = knownCount,
                    unknownEventCount = unknownCount,
                    totalEventCount = totalEventCount,
                )
        }
    }

    // ==== 时间范围数据（汇总 + 趋势桶 + 明细） ====

    fun rangeData(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        displayModelsById: Map<String, TokenStatDisplayModelEntity>,
        overrides: List<TokenStatPriceOverrideEntity>,
        legacyPrices: Map<String, LegacyPriceSettings?>,
        range: TokenStatsTimeRange,
        granularity: TokenStatsGranularity,
        zone: ZoneId,
        params: TokenStatsQueryParams,
    ): TokenStatsRangeData {
        // 分类/状态筛选在聚合入口统一应用：汇总、桶、明细反映同一筛选结果
        val filtered = filterByCategory(events, params).filterByStatus(params)
        val pricing = pricingContext(overrides, legacyPrices, params)

        val summary = totalsOf(filtered, identitiesById, pricing, params)
        val performance = performanceOf(filtered)

        val buckets = buildBuckets(
            filtered, identitiesById, pricing, params, range, granularity, zone,
        )

        val displayModels = displayModelBreakdowns(filtered, identitiesById, displayModelsById, pricing, params)
        val categories = categoryBreakdowns(filtered, identitiesById, pricing, params)
        val statuses = statusBreakdowns(filtered, identitiesById, pricing, params)

        return TokenStatsRangeData(
            range = range,
            granularity = granularity,
            eventCount = filtered.size.toLong(),
            summary = summary,
            performance = performance,
            buckets = buckets,
            displayModels = displayModels,
            categories = categories,
            statuses = statuses,
        )
    }

    // ==== 桶构建 ====

    private fun buildBuckets(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
        range: TokenStatsTimeRange,
        granularity: TokenStatsGranularity,
        zone: ZoneId,
    ): List<TokenStatsTrendBucket> {
        val bucketStarts = TokenStatsTimeRanges.bucketStarts(range, granularity, zone)
        val bucketEnds =
            bucketStarts.indices.map { index ->
                TokenStatsTimeRanges.bucketEndMs(bucketStarts, index, granularity, zone)
            }
        // 单遍分摊：每个事件恰好落入一个桶（补齐空桶由固定桶骨架保证）
        val bucketEvents = Array(bucketStarts.size) { mutableListOf<TokenStatEventEntity>() }
        for (event in events) {
            val index =
                TokenStatsTimeRanges.bucketIndexOf(event.startedAtMs, bucketStarts, granularity, zone)
                    ?: continue
            bucketEvents[index].add(event)
        }
        return bucketStarts.indices.map { index ->
            val bucketEventsForIndex = bucketEvents[index]
            TokenStatsTrendBucket(
                bucketStartMs = bucketStarts[index],
                bucketEndMs = bucketEnds[index],
                totals = totalsOf(bucketEventsForIndex, identitiesById, pricing, params),
                byModel = modelBucketsOf(bucketEventsForIndex, identitiesById, pricing, params),
                performance = performanceOf(bucketEventsForIndex),
            )
        }
    }

    private fun modelBucketsOf(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): Map<String, TokenStatsModelBucket> {
        val byDisplay = LinkedHashMap<String, MutableList<TokenStatEventEntity>>()
        for (event in events) {
            val identity = identitiesById[event.statIdentityId] ?: continue
            byDisplay.getOrPut(identity.displayModelId) { mutableListOf() }.add(event)
        }
        return byDisplay.mapValues { (_, modelEvents) ->
            TokenStatsModelBucket(
                requests = modelEvents.size.toLong(),
                uncachedInput = sumKnownTokens(modelEvents) { it.uncachedInputTokens },
                cachedInput = sumKnownTokens(modelEvents) { it.cachedInputTokens },
                cacheWrite = sumKnownTokens(modelEvents) { it.cacheWriteTokens },
                output = sumKnownTokens(modelEvents) { it.outputTokens },
                reasoning = sumKnownTokens(modelEvents, ::independentlyBilledReasoning),
                totalTokens = sumKnownTokens(modelEvents, ::canonicalTotalTokens),
                totalTokensUnknownEventCount =
                    modelEvents.count { canonicalTotalTokens(it) == null }.toLong(),
                unknownTokenEventCount =
                    modelEvents.count {
                        it.uncachedInputTokens == null || it.cachedInputTokens == null || it.outputTokens == null
                    }.toLong(),
                cost = costSummaryOf(modelEvents, identitiesById, pricing, params),
            )
        }
    }

    // ==== 明细 ====

    private fun displayModelBreakdowns(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        displayModelsById: Map<String, TokenStatDisplayModelEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): List<TokenStatsDisplayModelBreakdown> {
        val byDisplay = LinkedHashMap<String, MutableList<TokenStatEventEntity>>()
        for (event in events) {
            val identity = identitiesById[event.statIdentityId] ?: continue
            byDisplay.getOrPut(identity.displayModelId) { mutableListOf() }.add(event)
        }
        return byDisplay.map { (displayModelId, modelEvents) ->
            val display = displayModelsById[displayModelId]
            val byIdentity = LinkedHashMap<String, MutableList<TokenStatEventEntity>>()
            for (event in modelEvents) {
                byIdentity.getOrPut(event.statIdentityId) { mutableListOf() }.add(event)
            }
            val identities =
                byIdentity.map { (identityId, identityEvents) ->
                    val identity = identitiesById.getValue(identityId)
                    TokenStatsIdentityBreakdown(
                        identityId = identityId,
                        configId = identity.configId,
                        provider = identity.provider,
                        model = identity.model,
                        totals = totalsOf(identityEvents, identitiesById, pricing, params),
                        pricing = pricingInfoFor(identity, identityEvents, pricing, params),
                    )
                }.sortedWith(compareByDescending<TokenStatsIdentityBreakdown> { it.totals.requests })
            TokenStatsDisplayModelBreakdown(
                displayModelId = displayModelId,
                displayName = display?.displayName ?: displayModelId,
                normalizedModel = display?.normalizedModel ?: displayModelId,
                totals = totalsOf(modelEvents, identitiesById, pricing, params),
                identities = identities,
            )
        }.sortedWith(
            compareByDescending<TokenStatsDisplayModelBreakdown> { it.totals.requests }
                .thenBy { it.displayName.lowercase() }
        )
    }

    private fun categoryBreakdowns(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): List<TokenStatsCategoryBreakdown> =
        events.groupBy { TokenStatCategory.fromName(it.category) }
            .map { (category, categoryEvents) ->
                TokenStatsCategoryBreakdown(
                    category = category,
                    totals = totalsOf(categoryEvents, identitiesById, pricing, params),
                )
            }
            .sortedWith(
                compareByDescending<TokenStatsCategoryBreakdown> { it.totals.requests }
                    .thenBy { it.category.name }
            )

    private fun statusBreakdowns(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): List<TokenStatsStatusBreakdown> =
        TokenStatStatus.entries.mapNotNull { status ->
            val statusEvents = events.filter { TokenStatStatus.fromName(it.status) == status }
            if (statusEvents.isEmpty()) {
                null
            } else {
                TokenStatsStatusBreakdown(
                    status = status,
                    totals = totalsOf(statusEvents, identitiesById, pricing, params),
                )
            }
        }

    // ==== 合计 ====

    private fun totalsOf(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): TokenStatsTotals =
        TokenStatsTotals(
            requests = events.size.toLong(),
            uncachedInput = tokenAggregateOf(events) { it.uncachedInputTokens },
            cachedInput = tokenAggregateOf(events) { it.cachedInputTokens },
            cacheWrite = tokenAggregateOf(events) { it.cacheWriteTokens },
            totalInput = tokenAggregateOf(events) { it.totalInputTokens },
            output = tokenAggregateOf(events) { it.outputTokens },
            reasoning = tokenAggregateOf(events, ::independentlyBilledReasoning),
            totalTokens = tokenAggregateOf(events, ::canonicalTotalTokens),
            cost = costSummaryOf(events, identitiesById, pricing, params),
        )

    private fun tokenAggregateOf(
        events: List<TokenStatEventEntity>,
        pick: (TokenStatEventEntity) -> Long?,
    ): TokenStatsTokenAggregate {
        var sum = 0L
        var known = 0L
        for (event in events) {
            val value = pick(event) ?: continue
            known += 1
            sum = TokenCostCalculator.saturatedAdd(sum, value)
        }
        return TokenStatsTokenAggregate(
            knownSum = sum,
            knownEventCount = known,
            unknownEventCount = events.size.toLong() - known,
            totalEventCount = events.size.toLong(),
        )
    }

    private fun sumKnownTokens(
        events: List<TokenStatEventEntity>,
        pick: (TokenStatEventEntity) -> Long?,
    ): Long =
        events.fold(0L) { acc, event ->
            val value = pick(event) ?: return@fold acc
            TokenCostCalculator.saturatedAdd(acc, value)
        }

    /**
     * 推理 token 只在该计费被输出计数排除（[TokenStatEventEntity.reasoningIncludedInOutput]
     * == false）时才独立累计；provider 输出已含推理（OpenAI/Gemini/Anthropic）时再相加
     * 会双重计数。与活动聚合（[TokenActivityModels]）口径一致。
     */
    private fun independentlyBilledReasoning(event: TokenStatEventEntity): Long? =
        if (event.reasoningIncludedInOutput == false) event.reasoningTokens else null

    // ==== 费用 ====

    private fun costSummaryOf(
        events: List<TokenStatEventEntity>,
        identitiesById: Map<String, TokenStatIdentityEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): TokenStatsCostSummary {
        val original = EnumMap<PricingCurrency, BigDecimal>(PricingCurrency::class.java)
        var unknownCount = 0L
        for (event in events) {
            val (amount, currency) = eventCost(event, identitiesById[event.statIdentityId], pricing, params)
            if (amount == null || !amount.isFinite()) {
                unknownCount += 1
                continue
            }
            original.merge(currency, BigDecimal(amount)) { left, right -> left.add(right) }
        }
        return buildCostSummary(original, unknownCount, events.size.toLong(), params)
    }

    /**
     * 单事件原币费用贡献：
     * - HISTORICAL：事件保存的“发生时”快照（null = 未知，不猜测、不当作 0）；
     * - REVALUED：当前分层价格 × 事件用量重算（同一 [TokenCostCalculator] 语义，
     *   缓存写入独立计费与推理包含边界与落账时一致，不重复收费）。
     *   **未知定价（identity 缺失或 [ResolvedPricing.known] = false）一律贡献 null
     *   （P1-1）**：即使默认价表给出全 0 价格，也不能把“无法定价”伪装成 0 元。
     */
    private fun eventCost(
        event: TokenStatEventEntity,
        identity: TokenStatIdentityEntity?,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): Pair<Double?, PricingCurrency> =
        if (params.mode == TokenStatsCostMode.REVALUED) {
            val resolved = identity?.let { pricing.pricingFor(it) }
            if (resolved == null || !resolved.known) {
                null to parseCurrency(event.pricingCurrency)
            } else {
                val result = TokenCostCalculator.computeCost(event.toUsageInput(), resolved)
                result.amount to result.currency
            }
        } else {
            event.costInPricingCurrency to parseCurrency(event.pricingCurrency)
        }

    private fun baselineTotalsOf(
        baselines: List<TokenStatBaselineEntity>,
        params: TokenStatsQueryParams,
    ): TokenStatsBaselineTotals {
        var requests = 0L
        var inputTokens = 0L
        var cachedInputTokens = 0L
        var outputTokens = 0L
        var anyEstimated = false
        val original = EnumMap<PricingCurrency, BigDecimal>(PricingCurrency::class.java)
        var unknownCount = 0L
        for (baseline in baselines) {
            requests = TokenCostCalculator.saturatedAdd(requests, baseline.requestCount)
            inputTokens = TokenCostCalculator.saturatedAdd(inputTokens, baseline.inputTokens)
            cachedInputTokens =
                TokenCostCalculator.saturatedAdd(cachedInputTokens, baseline.cachedInputTokens)
            outputTokens = TokenCostCalculator.saturatedAdd(outputTokens, baseline.outputTokens)
            anyEstimated = anyEstimated || baseline.isEstimated
            val amount = baseline.costInPricingCurrency
            if (amount == null || !amount.isFinite()) {
                unknownCount += 1
            } else {
                original.merge(
                    parseCurrency(baseline.pricingCurrency),
                    BigDecimal(amount),
                ) { left, right -> left.add(right) }
            }
        }
        return TokenStatsBaselineTotals(
            identityCount = baselines.size.toLong(),
            requests = requests,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            cost =
                buildCostSummary(
                    original = original,
                    unknownCount = unknownCount,
                    totalCount = baselines.size.toLong(),
                    params = params,
                    modeOverride = TokenStatsCostMode.HISTORICAL,
                ),
            anyEstimated = anyEstimated,
        )
    }

    /** 原币合计 → 目标币种（BigDecimal 换算，边界才转 Double）。 */
    private fun buildCostSummary(
        original: EnumMap<PricingCurrency, BigDecimal>,
        unknownCount: Long,
        totalCount: Long,
        params: TokenStatsQueryParams,
        modeOverride: TokenStatsCostMode? = null,
    ): TokenStatsCostSummary {
        val usd = original[PricingCurrency.USD] ?: BigDecimal.ZERO
        val cny = original[PricingCurrency.CNY] ?: BigDecimal.ZERO
        val convertedUsd = convertTo(usd, PricingCurrency.USD, params.targetCurrency, params.manualRate)
        val convertedCny = convertTo(cny, PricingCurrency.CNY, params.targetCurrency, params.manualRate)
        val amounts =
            mapOf(
                PricingCurrency.USD to usd,
                PricingCurrency.CNY to cny,
            ).filterValues { it.signum() != 0 }
                .mapValues { (_, value) -> value.toDouble() }
        return TokenStatsCostSummary(
            currency = params.targetCurrency,
            knownAmount = convertedUsd.add(convertedCny, MC).toDouble(),
            unknownContributionCount = unknownCount,
            totalContributionCount = totalCount,
            rateUsed = params.manualRate,
            rateIsEstimated = params.rateIsEstimated,
            mode = modeOverride ?: params.mode,
            originalCurrencyAmounts = amounts,
        )
    }

    private fun convertTo(
        amount: BigDecimal,
        from: PricingCurrency,
        to: PricingCurrency,
        rate: Double,
    ): BigDecimal {
        if (from == to) return amount
        return if (from == PricingCurrency.USD) {
            amount.multiply(BigDecimal(rate), MC)
        } else {
            amount.divide(BigDecimal(rate), MC)
        }
    }

    // ==== 性能 ====

    /** 性能聚合（internal 供测试直接调用）。 */
    internal fun performanceOf(events: List<TokenStatEventEntity>): TokenStatsPerformance {
        var ttftKnown = 0L
        var ttftTotal = 0L
        var ttftUnknown = 0L
        var generationKnown = 0L
        var generationTotal = 0L
        var generationUnknown = 0L
        for (event in events) {
            val started = event.startedAtMs
            val first = event.firstTokenAtMs
            val ended = event.endedAtMs
            // 时长安全计算：非负时间戳 + 结束≥开始；无效样本记为 unknown 排除平均
            if (first != null && started >= 0 && first >= started) {
                ttftKnown += 1
                ttftTotal = TokenCostCalculator.saturatedAdd(ttftTotal, first - started)
            } else {
                ttftUnknown += 1
            }
            if (first != null && ended >= 0 && first >= 0 && ended >= first) {
                generationKnown += 1
                generationTotal = TokenCostCalculator.saturatedAdd(generationTotal, ended - first)
            } else {
                generationUnknown += 1
            }
        }
        return TokenStatsPerformance(
            ttft = durationAggregate(ttftKnown, ttftUnknown, ttftTotal),
            generationDuration = durationAggregate(generationKnown, generationUnknown, generationTotal),
        )
    }

    private fun durationAggregate(known: Long, unknown: Long, totalMs: Long): TokenStatsDurationAggregate =
        TokenStatsDurationAggregate(
            knownCount = known,
            unknownCount = unknown,
            totalMs = totalMs,
            averageMs = if (known > 0L) totalMs / known.toDouble() else 0.0,
        )

    // ==== 价格上下文（重估用） ====

    private fun pricingContext(
        overrides: List<TokenStatPriceOverrideEntity>,
        legacyPrices: Map<String, LegacyPriceSettings?>,
        params: TokenStatsQueryParams,
    ): PricingContext = PricingContext(overrides, legacyPrices, params)

    private class PricingContext(
        private val overrides: List<TokenStatPriceOverrideEntity>,
        private val legacyPrices: Map<String, LegacyPriceSettings?>,
        private val params: TokenStatsQueryParams,
    ) {
        private val cache = HashMap<String, ResolvedPricing>()

        fun pricingFor(identity: TokenStatIdentityEntity): ResolvedPricing? {
            if (params.mode != TokenStatsCostMode.REVALUED) return null
            return cache.getOrPut(identity.identityId) { resolveCurrent(identity) }
        }

        private fun resolveCurrent(identity: TokenStatIdentityEntity): ResolvedPricing {
            val providerModel = identity.providerModel
            return TokenPriceResolver.resolve(
                provider = identity.provider,
                model = identity.model,
                configId = identity.configId,
                overrides = overrides,
                legacyOverride = legacyPrices[providerModel],
                defaults = DefaultModelPricingCollect.getDefaultPricing(providerModel),
            )
        }
    }

    private fun pricingInfoFor(
        identity: TokenStatIdentityEntity,
        events: List<TokenStatEventEntity>,
        pricing: PricingContext,
        params: TokenStatsQueryParams,
    ): TokenStatsPricingInfo? {
        if (events.isEmpty()) return null
        return if (params.mode == TokenStatsCostMode.REVALUED) {
            pricing.pricingFor(identity)?.toPricingInfo()
        } else {
            val latest = events.maxByOrNull { it.startedAtMs } ?: return null
            TokenStatsPricingInfo(
                billingMode = BillingMode.fromString(latest.billingMode),
                currency = parseCurrency(latest.pricingCurrency),
                inputPricePerMillion = latest.inputPricePerMillion,
                cachedInputPricePerMillion = latest.cachedInputPricePerMillion,
                cacheWritePricePerMillion = latest.cacheWritePricePerMillion,
                outputPricePerMillion = latest.outputPricePerMillion,
                pricePerRequest = latest.pricePerRequest,
                source = PricingSource.fromName(latest.pricingSource),
                known = latest.pricingSource != PricingSource.UNKNOWN.name,
            )
        }
    }

    // ==== 工具 ====

    private fun filterByCategory(
        events: List<TokenStatEventEntity>,
        params: TokenStatsQueryParams,
    ): List<TokenStatEventEntity> {
        val categories = params.categories ?: return events
        return events.filter { TokenStatCategory.fromName(it.category) in categories }
    }

    private fun List<TokenStatEventEntity>.filterByStatus(
        params: TokenStatsQueryParams,
    ): List<TokenStatEventEntity> {
        val statuses = params.statuses ?: return this
        return filter { TokenStatStatus.fromName(it.status) in statuses }
    }

    private fun parseCurrency(raw: String): PricingCurrency =
        if (raw.equals("CNY", ignoreCase = true)) PricingCurrency.CNY else PricingCurrency.USD

    private fun TokenStatEventEntity.toUsageInput(): TokenUsageInput =
        TokenUsageInput(
            uncachedInputTokens = uncachedInputTokens,
            cachedInputTokens = cachedInputTokens,
            cacheWriteTokens = cacheWriteTokens,
            totalInputTokens = totalInputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            reasoningIncludedInOutput = reasoningIncludedInOutput,
            // null = 旧行未声明独立计费概念：按保守默认 true（该字段未知时
            // 缓存写入未知会阻塞费用，与“未知不当作 0”一致）
            cacheWriteSeparateBilling = cacheWriteSeparateBilling ?: true,
        )

    private fun ResolvedPricing.toPricingInfo(): TokenStatsPricingInfo =
        TokenStatsPricingInfo(
            billingMode = billingMode,
            currency = currency,
            inputPricePerMillion = inputPricePerMillion,
            cachedInputPricePerMillion = cachedInputPricePerMillion,
            cacheWritePricePerMillion = cacheWritePricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
            pricePerRequest = pricePerRequest,
            source = source,
            known = known,
        )
}

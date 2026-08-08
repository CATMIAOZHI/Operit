package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聚合器纯函数测试（阶段 3）：
 * unknown 与 0 严格区分、部分未知费用、历史快照/当前重估双口径、汇率变化
 * 只影响换算不影响原币、模型分组/别名/多 config 一致性、桶合计 == 范围总计、
 * 性能 unknown 排除平均、Long 饱和、BigDecimal 精度。
 */
class TokenStatsAggregatorTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val params = TokenStatsQueryParams()

    // ==== 构建辅助 ====

    private fun identity(
        id: String,
        configId: String = "cfg-$id",
        provider: String = "PROVIDER",
        model: String = "model-$id",
        displayModelId: String = TokenStatIdentityResolver.displayModelIdFor(model),
    ): TokenStatIdentityEntity =
        TokenStatIdentityEntity(
            identityId = id,
            configId = configId,
            provider = provider,
            model = model,
            displayModelId = displayModelId,
        )

    private fun displayModel(id: String, name: String): TokenStatDisplayModelEntity =
        TokenStatDisplayModelEntity(
            displayModelId = id,
            normalizedModel = TokenStatIdentityResolver.normalizeModelName(id),
            displayName = name,
        )

    private fun event(
        id: String,
        identityId: String,
        startedAtMs: Long,
        endedAtMs: Long = startedAtMs + 1_000L,
        firstTokenAtMs: Long? = startedAtMs + 200L,
        uncached: Long? = 100L,
        cached: Long? = 0L,
        cacheWrite: Long? = 0L,
        totalInput: Long? = null,
        output: Long? = 50L,
        reasoning: Long? = null,
        reasoningIncluded: Boolean? = true,
        cacheWriteSeparateBilling: Boolean? = null,
        category: String = TokenStatCategory.CHAT.name,
        status: String = TokenStatStatus.COMPLETED.name,
        billingMode: String = BillingMode.TOKEN.name,
        pricingCurrency: String = PricingCurrency.USD.name,
        inputPrice: Double? = 1.0,
        cachedPrice: Double? = 1.0,
        cacheWritePrice: Double? = null,
        outputPrice: Double? = 2.0,
        pricePerRequest: Double? = null,
        pricingSource: String = PricingSource.DEFAULT.name,
        cost: Double? = null,
    ): TokenStatEventEntity =
        TokenStatEventEntity(
            eventId = id,
            statIdentityId = identityId,
            category = category,
            status = status,
            acceptedGeneration = 0L,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            firstTokenAtMs = firstTokenAtMs,
            uncachedInputTokens = uncached,
            cachedInputTokens = cached,
            cacheWriteTokens = cacheWrite,
            totalInputTokens = totalInput,
            outputTokens = output,
            reasoningTokens = reasoning,
            reasoningIncludedInOutput = reasoningIncluded,
            cacheWriteSeparateBilling = cacheWriteSeparateBilling,
            billingMode = billingMode,
            pricingCurrency = pricingCurrency,
            inputPricePerMillion = inputPrice,
            cachedInputPricePerMillion = cachedPrice,
            cacheWritePricePerMillion = cacheWritePrice,
            outputPricePerMillion = outputPrice,
            pricePerRequest = pricePerRequest,
            pricingSource = pricingSource,
            costInPricingCurrency = cost,
            diagnosticsJson = null,
        )

    private fun baseline(
        identityId: String,
        inputTokens: Long = 100L,
        cachedInputTokens: Long = 10L,
        outputTokens: Long = 50L,
        requestCount: Long = 5L,
        pricingCurrency: String = PricingCurrency.USD.name,
        cost: Double? = 2.0,
        isEstimated: Boolean = true,
    ): TokenStatBaselineEntity =
        TokenStatBaselineEntity(
            identityId = identityId,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            requestCount = requestCount,
            pricingCurrency = pricingCurrency,
            costInPricingCurrency = cost,
            isEstimated = isEstimated,
            fingerprint = "fp",
            importedAtMs = 0L,
            frozenBillingMode = BillingMode.TOKEN.name,
        )

    private fun aggregated(events: List<TokenStatEventEntity>, identities: List<TokenStatIdentityEntity>): TokenStatsTotals =
        TokenStatsAggregator.rangeData(
            events = events,
            identitiesById = identities.associateBy { it.identityId },
            displayModelsById = emptyMap(),
            overrides = emptyList(),
            legacyPrices = emptyMap(),
            range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
            granularity = TokenStatsGranularity.DAILY,
            zone = shanghai,
            params = params,
        ).summary

    @Test
    fun `reasoning aggregate counts only separately billed reasoning`() {
        val id1 = identity("id-1")
        val totals =
            aggregated(
                events =
                    listOf(
                        event("e1", "id-1", 1000L, reasoning = 30L, reasoningIncluded = true),
                        event("e2", "id-1", 2000L, reasoning = 20L, reasoningIncluded = false),
                    ),
                identities = listOf(id1),
            )
        // 输出已含推理的事件不再独立计入，仅独立计费的推理入账
        assertEquals(20L, totals.reasoning.knownSum)
        assertEquals(1L, totals.reasoning.knownEventCount)
    }

    // ==== 生命周期 ====

    @Test
    fun `lifetime combines events and baseline with converted costs`() {
        val id1 = identity("id-1")
        val events =
            listOf(
                event("e1", "id-1", 1000L, cost = 0.5),
                event("e2", "id-1", 2000L, cost = 1.5),
            )
        val overview =
            TokenStatsAggregator.lifetime(
                events = events,
                baselines = listOf(baseline("id-1", cost = 2.0)),
                identitiesById = mapOf("id-1" to id1),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                params = params,
            )
        assertEquals(2L, overview.eventTotals.requests)
        assertEquals(14.0, overview.eventTotals.cost.knownAmount, 1e-9) // (0.5+1.5)*7
        assertTrue(overview.eventTotals.cost.isFullyKnown)
        assertEquals(1L, overview.baselineTotals.identityCount)
        assertEquals(5L, overview.baselineTotals.requests)
        assertEquals(14.0, overview.baselineTotals.cost.knownAmount, 1e-9) // 2.0*7
        assertEquals(7L, overview.combinedRequests)
        assertTrue(overview.baselineTotals.anyEstimated)
        assertEquals(TokenStatsCostMode.HISTORICAL, overview.baselineTotals.cost.mode)
    }

    @Test
    fun `incremental lifetime accumulator equals single list aggregation`() {
        // P2-1：生命周期分页路径（DAO 逐页喂入累加器）与单列表路径必须逐字段一致。
        val id1 = identity("id-1", provider = "OPENAI", model = "gpt-4o-2024-11-20")
        val events =
            (0 until 2500).map { index ->
                event(
                    "e$index", "id-1", index.toLong() * 1000L,
                    uncached = 100L, cached = 0L, output = 50L, cost = null,
                )
            }
        val baselines = listOf(baseline("id-1", cost = 2.0))

        val single =
            TokenStatsAggregator.lifetime(
                events = events,
                baselines = baselines,
                identitiesById = mapOf("id-1" to id1),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                params = params.copy(mode = TokenStatsCostMode.REVALUED),
            )

        val accumulator =
            TokenStatsAggregator.TokenStatsEventTotalsAccumulator(
                legacyPrices = emptyMap(),
                params = params.copy(mode = TokenStatsCostMode.REVALUED),
            )
        events.chunked(333).forEach { chunk ->
            accumulator.addPage(chunk, mapOf("id-1" to id1), emptyList())
        }
        val paged =
            TokenStatsAggregator.lifetimeFrom(
                eventsTotals = accumulator.totals(),
                baselines = baselines,
                params = params.copy(mode = TokenStatsCostMode.REVALUED),
            )

        val a = single.eventTotals
        val b = paged.eventTotals
        assertEquals(a.requests, b.requests)
        assertEquals(a.uncachedInput, b.uncachedInput)
        assertEquals(a.cachedInput, b.cachedInput)
        assertEquals(a.cacheWrite, b.cacheWrite)
        assertEquals(a.totalInput, b.totalInput)
        assertEquals(a.output, b.output)
        assertEquals(a.reasoning, b.reasoning)
        // canonical 总 token 同样必须逐字段一致（P2-1 分页等价）
        assertEquals(a.totalTokens, b.totalTokens)
        assertEquals(a.cost.knownAmount, b.cost.knownAmount, 1e-9)
        assertEquals(a.cost.unknownContributionCount, b.cost.unknownContributionCount)
        assertEquals(a.cost.totalContributionCount, b.cost.totalContributionCount)
        assertEquals(a.cost.originalCurrencyAmounts, b.cost.originalCurrencyAmounts)
        assertEquals(a.cost.isFullyKnown, b.cost.isFullyKnown)
        assertEquals(single.combinedRequests, paged.combinedRequests)
        assertEquals(single.baselineTotals, paged.baselineTotals)
    }

    @Test
    fun `unknown cost contributions are partial not zero`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, cost = 1.0),
                event("e2", "id-1", 2000L, cost = null),
            )
        val cost = aggregated(events, listOf(identity("id-1"))).cost
        assertEquals(7.0, cost.knownAmount, 1e-9)
        assertEquals(1L, cost.unknownContributionCount)
        assertEquals(2L, cost.totalContributionCount)
        assertFalse(cost.isFullyKnown)
    }

    @Test
    fun `non finite historical costs are unknown and never reach BigDecimal`() {
        val id = identity("id-1")
        val events =
            listOf(
                event("finite", id.identityId, 1000L, cost = 1.0),
                event("infinite", id.identityId, 2000L, cost = Double.POSITIVE_INFINITY),
                event("nan", id.identityId, 3000L, cost = Double.NaN),
            )
        val rangeCost = aggregated(events, listOf(id)).cost
        assertEquals(7.0, rangeCost.knownAmount, 1e-9)
        assertEquals(2L, rangeCost.unknownContributionCount)

        val lifetime =
            TokenStatsAggregator.lifetime(
                events = events,
                baselines = listOf(baseline(id.identityId, cost = Double.POSITIVE_INFINITY)),
                identitiesById = mapOf(id.identityId to id),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                params = params,
            )
        assertEquals(2L, lifetime.eventTotals.cost.unknownContributionCount)
        assertEquals(1L, lifetime.baselineTotals.cost.unknownContributionCount)
    }

    @Test
    fun `zero cost is a known contribution`() {
        val cost = aggregated(listOf(event("e1", "id-1", 1000L, cost = 0.0)), listOf(identity("id-1"))).cost
        assertEquals(0.0, cost.knownAmount, 1e-9)
        assertEquals(0L, cost.unknownContributionCount)
        assertTrue(cost.isFullyKnown)
        assertTrue(cost.originalCurrencyAmounts.isEmpty())
    }

    @Test
    fun `token unknown and zero are distinguished`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, uncached = null, output = 0L),
                event("e2", "id-1", 2000L, uncached = 0L, output = 0L),
                event("e3", "id-1", 3000L, uncached = 10L, output = 5L),
            )
        val totals = aggregated(events, listOf(identity("id-1")))
        assertEquals(10L, totals.uncachedInput.knownSum)
        assertEquals(2L, totals.uncachedInput.knownEventCount)
        assertEquals(1L, totals.uncachedInput.unknownEventCount)
        assertEquals(3L, totals.uncachedInput.totalEventCount)
        assertFalse(totals.uncachedInput.isFullyKnown)
        // output：三个事件都已知（含两个真实 0）
        assertEquals(5L, totals.output.knownSum)
        assertEquals(0L, totals.output.unknownEventCount)
        assertTrue(totals.output.isFullyKnown)
    }

    // ==== 汇率与原币 ====

    @Test
    fun `rate change affects converted total but not original currency amounts`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, cost = 1.0),
                event("e2", "id-1", 2000L, cost = 1.0),
            )
        val low = aggregatedWithRate(events, 7.0).cost
        val high = aggregatedWithRate(events, 7.5).cost
        assertEquals(14.0, low.knownAmount, 1e-9)
        assertEquals(15.0, high.knownAmount, 1e-9)
        assertEquals(2.0, low.originalCurrencyAmounts[PricingCurrency.USD]!!, 1e-9)
        assertEquals(2.0, high.originalCurrencyAmounts[PricingCurrency.USD]!!, 1e-9)
    }

    @Test
    fun `mixed currency events convert per currency`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, pricingCurrency = PricingCurrency.USD.name, cost = 1.0),
                event("e2", "id-1", 2000L, pricingCurrency = PricingCurrency.CNY.name, cost = 7.0),
            )
        val cny = aggregatedWithParams(events, params.copy(targetCurrency = PricingCurrency.CNY)).cost
        assertEquals(14.0, cny.knownAmount, 1e-9) // 1*7 + 7
        assertEquals(1.0, cny.originalCurrencyAmounts[PricingCurrency.USD]!!, 1e-9)
        assertEquals(7.0, cny.originalCurrencyAmounts[PricingCurrency.CNY]!!, 1e-9)

        val usd = aggregatedWithParams(events, params.copy(targetCurrency = PricingCurrency.USD)).cost
        assertEquals(2.0, usd.knownAmount, 1e-9) // 1 + 7/7
    }

    private fun aggregatedWithRate(events: List<TokenStatEventEntity>, rate: Double): TokenStatsTotals =
        aggregatedWithParams(events, params.copy(manualRate = rate))

    private fun aggregatedWithParams(
        events: List<TokenStatEventEntity>,
        p: TokenStatsQueryParams,
        identities: List<TokenStatIdentityEntity> = listOf(identity("id-1")),
    ): TokenStatsTotals =
        TokenStatsAggregator.rangeData(
            events = events,
            identitiesById = identities.associateBy { it.identityId },
            displayModelsById = emptyMap(),
            overrides = emptyList(),
            legacyPrices = emptyMap(),
            range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
            granularity = TokenStatsGranularity.DAILY,
            zone = shanghai,
            params = p,
        ).summary

    // ==== 当前价格重估 ====

    @Test
    fun `revalued mode recomputes cost from current pricing and usage`() {
        val events =
            listOf(
                // 快照成本缺失（未知），重估后按当前价格算出已知成本
                event("e1", "id-1", 1000L, uncached = 1_000L, cached = 0L, output = 500L, cost = null),
            )
        val totals =
            aggregatedWithParams(
                events,
                params.copy(
                    mode = TokenStatsCostMode.REVALUED,
                    manualRate = 7.0,
                    targetCurrency = PricingCurrency.CNY,
                ),
                identities = listOf(identity("id-1", provider = "OPENAI", model = "gpt-4o-2024-11-20")),
            )
        val cost = totals.cost
        assertTrue(cost.isFullyKnown)
        // (1000*1.5 + 500*6)/1e6 = 0.0045 USD -> 0.0315 CNY（gpt-4o-2024-11-20 内置价）
        assertEquals(0.0315, cost.knownAmount, 1e-9)
        assertEquals(0.0045, cost.originalCurrencyAmounts[PricingCurrency.USD]!!, 1e-9)
    }

    @Test
    fun `revalued mode does not double bill included reasoning`() {
        fun totalsWith(reasoningIncluded: Boolean?): TokenStatsCostSummary {
            val events =
                listOf(
                    event(
                        "e1", "id-1", 1000L,
                        uncached = 0L, cached = 0L, output = 500L,
                        reasoning = 100L, reasoningIncluded = reasoningIncluded, cost = null,
                    ),
                )
            return aggregatedWithParams(
                events,
                params.copy(mode = TokenStatsCostMode.REVALUED, manualRate = 1.0, targetCurrency = PricingCurrency.USD),
                identities = listOf(identity("id-1", provider = "OPENAI", model = "gpt-4o-2024-11-20")),
            ).cost
        }
        val included = totalsWith(reasoningIncluded = true)
        assertEquals(0.003, included.knownAmount, 1e-9) // 500 * 6 / 1e6
        val separate = totalsWith(reasoningIncluded = false)
        assertEquals(0.0036, separate.knownAmount, 1e-9) // (500+100) * 6 / 1e6
        assertTrue(separate.knownAmount > included.knownAmount)
    }

    @Test
    fun `revalued cache write separate billing without price stays unknown`() {
        val openai = listOf(identity("id-1", provider = "OPENAI", model = "gpt-4o-2024-11-20"))
        val events =
            listOf(
                event(
                    "e1", "id-1", 1000L,
                    uncached = 1_000L, cached = 0L, cacheWrite = 100L, output = 500L,
                    cacheWriteSeparateBilling = true, cacheWritePrice = null, cost = null,
                ),
            )
        val separateCost = aggregatedWithParams(
            events, params.copy(mode = TokenStatsCostMode.REVALUED), identities = openai,
        ).cost
        assertFalse(separateCost.isFullyKnown)
        assertEquals(1L, separateCost.unknownContributionCount)

        val mergedCost = aggregatedWithParams(
            listOf(
                event(
                    "e2", "id-1", 2000L,
                    uncached = 1_000L, cached = 0L, cacheWrite = 100L, output = 500L,
                    cacheWriteSeparateBilling = false, cacheWritePrice = null, cost = null,
                ),
            ),
            params.copy(mode = TokenStatsCostMode.REVALUED),
            identities = openai,
        ).cost
        assertTrue(mergedCost.isFullyKnown)
    }

    @Test
    fun `revalued config override wins over provider override`() {
        val identityWithConfig = identity("id-1", configId = "cfg-1")
        val providerOverride =
            TokenStatPriceOverrideEntity.normalized(
                scope = TokenPriceResolver.SCOPE_PROVIDER_MODEL,
                provider = "PROVIDER",
                model = "model-id-1",
                configId = null,
                billingMode = BillingMode.TOKEN.name,
                pricingCurrency = PricingCurrency.USD.name,
                inputPricePerMillion = 10.0,
                cachedInputPricePerMillion = 10.0,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 20.0,
                pricePerRequest = null,
            )
        val configOverride =
            TokenStatPriceOverrideEntity.normalized(
                scope = TokenPriceResolver.SCOPE_CONFIG,
                provider = "PROVIDER",
                model = "model-id-1",
                configId = "cfg-1",
                billingMode = BillingMode.TOKEN.name,
                pricingCurrency = PricingCurrency.USD.name,
                inputPricePerMillion = 2.0,
                cachedInputPricePerMillion = 2.0,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 4.0,
                pricePerRequest = null,
            )
        val events =
            listOf(event("e1", "id-1", 1000L, uncached = 1_000L, cached = 0L, output = 500L, cost = null))
        val totals =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = mapOf("id-1" to identityWithConfig),
                displayModelsById = emptyMap(),
                overrides = listOf(providerOverride, configOverride),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params.copy(mode = TokenStatsCostMode.REVALUED),
            ).summary
        // (1000*2 + 500*4)/1e6 = 0.004 USD -> 0.028 CNY（用 CONFIG 价，非 provider 价的 0.14）
        assertEquals(0.028, totals.cost.knownAmount, 1e-9)
    }

    @Test
    fun `revalued unknown identity contributes unknown cost`() {
        val totals =
            aggregatedWithParams(
                listOf(event("e1", "missing-id", 1000L, cost = null)),
                params.copy(mode = TokenStatsCostMode.REVALUED),
            )
        assertFalse(totals.cost.isFullyKnown)
        assertEquals(1L, totals.cost.unknownContributionCount)
    }

    @Test
    fun `revalued unknown model with complete usage is unknown not zero`() {
        // P1-1：identity 存在但 provider/model 未知（无覆盖、无内置价 →
        // zeroPricing known = false）：即使用量完整，费用也必须是 unknown 贡献，
        // 绝不能伪装成 known 的 0 元。
        val totals =
            aggregatedWithParams(
                listOf(
                    event("e1", "id-1", 1000L, uncached = 1_000L, cached = 0L, output = 500L, cost = null),
                ),
                params.copy(mode = TokenStatsCostMode.REVALUED),
                identities = listOf(identity("id-1", provider = "NO_SUCH_PROVIDER", model = "no-such-model")),
            )
        assertEquals(1L, totals.cost.unknownContributionCount)
        assertEquals(1L, totals.cost.totalContributionCount)
        assertEquals(0.0, totals.cost.knownAmount, 1e-9)
        assertFalse(totals.cost.isFullyKnown)
        assertTrue(totals.cost.originalCurrencyAmounts.isEmpty())
    }

    @Test
    fun `revalued count mode with unknown pricing is unknown not zero`() {
        // P1-1：COUNT 模式同样受 known 边界约束（zeroPricing 的按次价 > 0 只是
        // 兜底默认，未知模型 known = false → 成本 unknown）。
        val totals =
            aggregatedWithParams(
                listOf(
                    event(
                        "e1", "id-1", 1000L,
                        uncached = 1_000L, cached = 0L, output = 500L, cost = null,
                        billingMode = BillingMode.COUNT.name,
                    ),
                ),
                params.copy(mode = TokenStatsCostMode.REVALUED),
                identities = listOf(identity("id-1", provider = "NO_SUCH_PROVIDER", model = "no-such-model")),
            )
        assertFalse(totals.cost.isFullyKnown)
        assertEquals(1L, totals.cost.unknownContributionCount)
        assertEquals(0.0, totals.cost.knownAmount, 1e-9)
    }

    @Test
    fun `revalued unknown pricing never yields fully known zero cost`() {
        // P1-1 回归：旧实现把 unknown（known = false）当 0 元已知成本，isFullyKnown
        // 为 true；修复后必须为 partial。
        val totals =
            aggregatedWithParams(
                listOf(
                    event("e1", "id-1", 1000L, uncached = 0L, cached = 0L, output = 0L, cost = null),
                ),
                params.copy(mode = TokenStatsCostMode.REVALUED),
                identities = listOf(identity("id-1", provider = "NO_SUCH_PROVIDER", model = "no-such-model")),
            )
        assertFalse(totals.cost.isFullyKnown)
        assertEquals(1L, totals.cost.unknownContributionCount)
        assertEquals(0.0, totals.cost.knownAmount, 1e-9)
    }

    // ==== 桶 ====

    /** 本地对齐的 24 小时范围（上海），保证桶 0 起点 == 范围起点。 */
    private val alignedDayStart: Long =
        java.time.LocalDateTime.parse("2026-08-07T00:00:00").atZone(shanghai).toInstant().toEpochMilli()

    private fun hourlyRangeData(
        events: List<TokenStatEventEntity>,
        identities: List<TokenStatIdentityEntity> = listOf(identity("id-1")),
        startHour: Long = alignedDayStart,
    ): TokenStatsRangeData =
        TokenStatsAggregator.rangeData(
            events = events,
            identitiesById = identities.associateBy { it.identityId },
            displayModelsById = emptyMap(),
            overrides = emptyList(),
            legacyPrices = emptyMap(),
            range = TokenStatsTimeRanges.customRange(startHour, startHour + 24L * TokenStatsTimeRanges.HOUR_MS),
            granularity = TokenStatsGranularity.HOURLY,
            zone = shanghai,
            params = params,
        )

    @Test
    fun `bucket sums equal range total and empty buckets are filled`() {
        val start = alignedDayStart
        val events =
            listOf(
                event("e1", "id-1", start + TokenStatsTimeRanges.HOUR_MS, cost = 1.0),
                event("e2", "id-1", start + 5L * TokenStatsTimeRanges.HOUR_MS, cost = 2.0),
                event("e3", "id-1", start + 10L * TokenStatsTimeRanges.HOUR_MS, cost = 0.5),
            )
        val data = hourlyRangeData(events, startHour = start)
        assertEquals(3L, data.summary.requests)
        assertEquals(24, data.buckets.size)
        assertEquals(24.5, data.summary.cost.knownAmount, 1e-9) // (1+2+0.5)*7

        val bucketRequests = data.buckets.sumOf { it.totals.requests }
        assertEquals(data.summary.requests, bucketRequests)
        assertEquals(
            data.summary.uncachedInput.knownSum,
            data.buckets.sumOf { it.totals.uncachedInput.knownSum },
        )
        assertEquals(
            data.summary.output.knownSum,
            data.buckets.sumOf { it.totals.output.knownSum },
        )
        assertEquals(
            data.summary.cost.knownAmount,
            data.buckets.sumOf { it.totals.cost.knownAmount },
            1e-9,
        )
        assertEquals(
            data.summary.cost.unknownContributionCount,
            data.buckets.sumOf { it.totals.cost.unknownContributionCount },
        )
        // 空桶补齐：请求为 0、费用已知且为 0
        val empty = data.buckets[3] // 第 4 个桶（e1 在桶 1，e2 在桶 5，e3 在桶 10）
        assertEquals(0L, empty.totals.requests)
        assertEquals(0.0, empty.totals.cost.knownAmount, 1e-9)
        assertTrue(empty.totals.cost.isFullyKnown)
    }

    @Test
    fun `bucket boundary events land in the correct bucket`() {
        val start = alignedDayStart
        val events =
            listOf(
                event("at-start", "id-1", start),
                event("at-boundary", "id-1", start + TokenStatsTimeRanges.HOUR_MS),
            )
        val data = hourlyRangeData(events, startHour = start)
        assertEquals(1L, data.buckets[0].totals.requests)
        assertEquals(1L, data.buckets[1].totals.requests)
    }

    @Test
    fun `bucket model split matches range total`() {
        val id1 = identity("id-1", model = "gpt-4o")
        val id2 = identity("id-2", configId = "cfg-2", model = "gpt-4o")
        val events =
            listOf(
                event("e1", "id-1", alignedDayStart + TokenStatsTimeRanges.HOUR_MS, cost = 1.0),
                event("e2", "id-2", alignedDayStart + 2L * TokenStatsTimeRanges.HOUR_MS, cost = 2.0),
            )
        val data =
            hourlyRangeData(events, identities = listOf(id1, id2))
        val bucket = data.buckets.first { it.totals.requests == 1L }
        assertEquals(1L, bucket.byModel["gpt-4o"]!!.requests)
        assertEquals(7.0, bucket.byModel["gpt-4o"]!!.cost.knownAmount, 1e-9)
        assertEquals(2, data.summary.requests)
    }

    @Test
    fun `canonical totals aggregate per event across summary buckets and model buckets`() {
        val id1 = identity("id-1", provider = "OPENAI", model = "gpt-4o")
        val id2 = identity("id-2", configId = "cfg-2", provider = "ANTHROPIC", model = "claude-3-5-sonnet")
        val openaiModelId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")
        val anthropicModelId = TokenStatIdentityResolver.displayModelIdFor("claude-3-5-sonnet")
        val events =
            listOf(
                // OpenAI：权威 totalInput（600）即使拆分未知也能用 → 600+400
                event(
                    "e1", "id-1", alignedDayStart,
                    uncached = null, cached = null, totalInput = 600L, output = 400L,
                    cacheWriteSeparateBilling = false,
                ),
                // OpenAI 无 totalInput：输入 = uncached+cached，cacheWrite 不重复 → 500+100+400
                event(
                    "e2", "id-1", alignedDayStart + TokenStatsTimeRanges.HOUR_MS,
                    uncached = 500L, cached = 100L, cacheWrite = 50L, output = 400L,
                    cacheWriteSeparateBilling = false,
                ),
                // Anthropic：权威 totalInput = 三分量之和，cacheWrite 只计一次 → 650+400
                event(
                    "e3", "id-2", alignedDayStart + 2L * TokenStatsTimeRanges.HOUR_MS,
                    uncached = 500L, cached = 100L, cacheWrite = 50L, totalInput = 650L, output = 400L,
                    cacheWriteSeparateBilling = true,
                ),
                // 独立推理：output + reasoning → 0+100+20
                event(
                    "e4", "id-2", alignedDayStart + 3L * TokenStatsTimeRanges.HOUR_MS,
                    uncached = 0L, cached = 0L, cacheWrite = 0L, totalInput = 0L, output = 100L,
                    reasoning = 20L, reasoningIncluded = false, cacheWriteSeparateBilling = true,
                ),
                // 输入未知 → canonical unknown（输出已知也不拼 0）
                event(
                    "e5", "id-1", alignedDayStart + 4L * TokenStatsTimeRanges.HOUR_MS,
                    uncached = null, cached = null, totalInput = null, output = 50L,
                    cacheWriteSeparateBilling = false,
                ),
            )
        val data = hourlyRangeData(events, identities = listOf(id1, id2))
        val summary = data.summary
        assertEquals(1000L + 1000L + 1050L + 120L, summary.totalTokens.knownSum)
        assertEquals(4L, summary.totalTokens.knownEventCount)
        assertEquals(1L, summary.totalTokens.unknownEventCount)
        assertEquals(5L, summary.totalTokens.totalEventCount)
        // 桶合计 == 范围总计（canonical 与分量同样守恒）
        assertEquals(
            summary.totalTokens.knownSum,
            data.buckets.sumOf { it.totals.totalTokens.knownSum },
        )
        assertEquals(
            summary.totalTokens.unknownEventCount,
            data.buckets.sumOf { it.totals.totalTokens.unknownEventCount },
        )
        // 模型桶同样聚合 canonical（按桶×模型分组）：跨桶合计与范围总计一致
        // OpenAI e1+e2=2000、e5 unknown；Anthropic e3+e4=1170
        val openaiTotal = data.buckets.sumOf { it.byModel[openaiModelId]?.totalTokens ?: 0L }
        val openaiUnknown =
            data.buckets.sumOf { it.byModel[openaiModelId]?.totalTokensUnknownEventCount ?: 0L }
        assertEquals(2000L, openaiTotal)
        assertEquals(1L, openaiUnknown)
        val anthropicTotal = data.buckets.sumOf { it.byModel[anthropicModelId]?.totalTokens ?: 0L }
        val anthropicUnknown =
            data.buckets.sumOf { it.byModel[anthropicModelId]?.totalTokensUnknownEventCount ?: 0L }
        assertEquals(1170L, anthropicTotal)
        assertEquals(0L, anthropicUnknown)
        // 桶 0 只有 e1：该桶 OpenAI 模型桶的 canonical = e1 单事件
        assertEquals(1000L, data.buckets[0].byModel.getValue(openaiModelId).totalTokens)
    }

    // ==== 模型分组与明细 ====

    @Test
    fun `same normalized model merges into one display group across configs`() {
        val id1 = identity("id-1", configId = "cfg-1", provider = "P1", model = "gpt-4o")
        val id2 = identity("id-2", configId = "cfg-2", provider = "P1", model = "GPT-4o")
        val events =
            listOf(
                event("e1", "id-1", 1000L, cost = 1.0),
                event("e2", "id-2", 2000L, cost = 2.0),
            )
        val data =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = listOf(id1, id2).associateBy { it.identityId },
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params,
            )
        assertEquals(1, data.displayModels.size)
        val model = data.displayModels.single()
        assertEquals(TokenStatIdentityResolver.displayModelIdFor("gpt-4o"), model.displayModelId)
        assertEquals(2, model.identities.size)
        assertEquals(2L, model.totals.requests)
        assertEquals(21.0, model.totals.cost.knownAmount, 1e-9) // (1+2)*7
        // 身份分项之和 == 展示模型总计
        assertEquals(
            model.totals.cost.knownAmount,
            model.identities.sumOf { it.totals.cost.knownAmount },
            1e-9,
        )
        assertEquals(2L, model.identities.sumOf { it.totals.requests })
    }

    @Test
    fun `manual alias groups different names via displayModelId`() {
        val id1 = identity("id-1", model = "model-a", displayModelId = "aliased")
        val id2 = identity("id-2", configId = "cfg-2", model = "model-b", displayModelId = "aliased")
        val events =
            listOf(
                event("e1", "id-1", 1000L, cost = 1.0),
                event("e2", "id-2", 2000L, cost = 2.0),
            )
        val data =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = listOf(id1, id2).associateBy { it.identityId },
                displayModelsById = mapOf("aliased" to displayModel("aliased", "My Alias")),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params,
            )
        assertEquals(1, data.displayModels.size)
        val model = data.displayModels.single()
        assertEquals("My Alias", model.displayName)
        assertEquals(2L, model.totals.requests)
        // display name 缺失时回退到 displayModelId
        val noDisplayRow =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = listOf(id1, id2).associateBy { it.identityId },
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params,
            )
        assertEquals("aliased", noDisplayRow.displayModels.single().displayName)
    }

    @Test
    fun `identity pricing info reflects snapshot or current resolution`() {
        val id1 = identity("id-1")
        val events = listOf(event("e1", "id-1", 1000L, cost = 1.0, outputPrice = 3.0))
        val historical =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = mapOf("id-1" to id1),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params,
            )
        val identityBreakdown = historical.displayModels.single().identities.single()
        assertEquals(3.0, identityBreakdown.pricing!!.outputPricePerMillion!!, 1e-9)
        assertEquals(PricingSource.DEFAULT, identityBreakdown.pricing!!.source)

        val revalued =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = mapOf("id-1" to identity("id-1", provider = "OPENAI", model = "gpt-4o-2024-11-20")),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params.copy(mode = TokenStatsCostMode.REVALUED),
            )
        val revaluedPricing = revalued.displayModels.single().identities.single().pricing!!
        assertEquals(BillingMode.TOKEN, revaluedPricing.billingMode)
        assertTrue(revaluedPricing.known)
    }

    // ==== 分类与状态 ====

    @Test
    fun `category breakdown and filter`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, category = TokenStatCategory.CHAT.name),
                event("e2", "id-1", 2000L, category = TokenStatCategory.CHAT.name),
                event("e3", "id-1", 3000L, category = TokenStatCategory.SUMMARY.name),
            )
        val all = aggregated(events, listOf(identity("id-1")))
        assertEquals(3L, all.requests)

        val data =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = mapOf("id-1" to identity("id-1")),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params.copy(categories = setOf(TokenStatCategory.CHAT)),
            )
        assertEquals(2L, data.summary.requests)
        assertEquals(listOf(TokenStatCategory.CHAT), data.categories.map { it.category })

        val allData =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = mapOf("id-1" to identity("id-1")),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params,
            )
        assertEquals(listOf(TokenStatCategory.CHAT, TokenStatCategory.SUMMARY), allData.categories.map { it.category })
        assertEquals(2L, allData.categories.first { it.category == TokenStatCategory.CHAT }.totals.requests)
    }

    @Test
    fun `status breakdown counts per status in enum order`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, status = TokenStatStatus.FAILED.name),
                event("e2", "id-1", 2000L, status = TokenStatStatus.COMPLETED.name),
                event("e3", "id-1", 3000L, status = TokenStatStatus.COMPLETED.name),
                event("e4", "id-1", 4000L, status = TokenStatStatus.TIMEOUT.name),
            )
        val data =
            TokenStatsAggregator.rangeData(
                events = events,
                identitiesById = mapOf("id-1" to identity("id-1")),
                displayModelsById = emptyMap(),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                range = TokenStatsTimeRanges.customRange(0L, 30L * TokenStatsTimeRanges.DAY_MS),
                granularity = TokenStatsGranularity.DAILY,
                zone = shanghai,
                params = params,
            )
        assertEquals(
            listOf(TokenStatStatus.COMPLETED, TokenStatStatus.TIMEOUT, TokenStatStatus.FAILED),
            data.statuses.map { it.status },
        )
        assertEquals(2L, data.statuses.first { it.status == TokenStatStatus.COMPLETED }.totals.requests)
    }

    // ==== 性能 ====

    @Test
    fun `performance excludes unknown samples from averages`() {
        val events =
            listOf(
                // e1: 正常（TTFT 100ms，生成 400ms）
                event("e1", "id-1", 0L, endedAtMs = 500L, firstTokenAtMs = 100L),
                // e2: 无首 token -> TTFT/生成都 unknown
                event("e2", "id-1", 0L, endedAtMs = 100L, firstTokenAtMs = null),
                // e3: 结束早于首 token -> TTFT 有效 50ms，生成 unknown
                event("e3", "id-1", 0L, endedAtMs = 40L, firstTokenAtMs = 50L),
                // e4: 负时间戳 -> 全部 unknown
                event("e4", "id-1", -5L, endedAtMs = 10L, firstTokenAtMs = -5L),
            )
        val performance = TokenStatsAggregator.performanceOf(events)
        assertEquals(2L, performance.ttft.knownCount)
        assertEquals(2L, performance.ttft.unknownCount)
        assertEquals(150L, performance.ttft.totalMs)
        assertEquals(75.0, performance.ttft.averageMs, 1e-9)
        assertEquals(1L, performance.generationDuration.knownCount)
        assertEquals(3L, performance.generationDuration.unknownCount)
        assertEquals(400L, performance.generationDuration.totalMs)
        assertEquals(400.0, performance.generationDuration.averageMs, 1e-9)
    }

    @Test
    fun `performance with no data has zero averages`() {
        val performance = TokenStatsAggregator.performanceOf(emptyList())
        assertEquals(0L, performance.ttft.knownCount)
        assertEquals(0.0, performance.ttft.averageMs, 1e-9)
        assertFalse(performance.ttft.hasData)
    }

    // ==== 数值边界 ====

    @Test
    fun `long sums saturate instead of overflowing negative`() {
        val events =
            listOf(
                event("e1", "id-1", 1000L, output = Long.MAX_VALUE),
                event("e2", "id-1", 2000L, output = 10L),
            )
        val totals = aggregated(events, listOf(identity("id-1")))
        assertEquals(Long.MAX_VALUE, totals.output.knownSum)
        assertTrue(totals.output.knownSum > 0)
        assertEquals(2L, totals.output.knownEventCount)
    }

    @Test
    fun `cost accumulation is precise across many events`() {
        val events =
            (0 until 1000).map { index ->
                event("e$index", "id-1", index.toLong() * 1000L, cost = 0.1)
            }
        val cost = aggregated(events, listOf(identity("id-1"))).cost
        assertEquals(100.0 * 7.0, cost.knownAmount, 1e-6)
        assertTrue(cost.isFullyKnown)
    }

    // ==== baseline ====

    @Test
    fun `baseline unknown cost counted as unknown`() {
        val overview =
            TokenStatsAggregator.lifetime(
                events = emptyList(),
                baselines = listOf(baseline("id-1", cost = null)),
                identitiesById = mapOf("id-1" to identity("id-1")),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                params = params,
            )
        assertEquals(0.0, overview.baselineTotals.cost.knownAmount, 1e-9)
        assertEquals(1L, overview.baselineTotals.cost.unknownContributionCount)
        assertFalse(overview.baselineTotals.cost.isFullyKnown)
        // baseline 恒为估算/冻结快照口径，不受重估参数影响
        val revalued =
            TokenStatsAggregator.lifetime(
                events = emptyList(),
                baselines = listOf(baseline("id-1", cost = 1.0)),
                identitiesById = mapOf("id-1" to identity("id-1")),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                params = params.copy(mode = TokenStatsCostMode.REVALUED),
            )
        assertEquals(TokenStatsCostMode.HISTORICAL, revalued.baselineTotals.cost.mode)
    }

    @Test
    fun `default params mark rate as estimated 7 dot 0`() {
        val cost = aggregated(listOf(event("e1", "id-1", 1000L, cost = 1.0)), listOf(identity("id-1"))).cost
        assertEquals(7.0, cost.rateUsed, 1e-9)
        assertTrue(cost.rateIsEstimated)
        assertEquals(PricingCurrency.CNY, cost.currency)
    }

    @Test
    fun `baseline tokens aggregate with saturation`() {
        val overview =
            TokenStatsAggregator.lifetime(
                events = emptyList(),
                baselines =
                    listOf(
                        baseline("id-1", inputTokens = Long.MAX_VALUE, requestCount = 5L),
                        baseline("id-2", inputTokens = 10L, requestCount = 5L),
                    ),
                identitiesById =
                    mapOf("id-1" to identity("id-1"), "id-2" to identity("id-2", configId = "cfg-2")),
                overrides = emptyList(),
                legacyPrices = emptyMap(),
                params = params,
            )
        assertEquals(Long.MAX_VALUE, overview.baselineTotals.inputTokens)
        assertEquals(10L, overview.baselineTotals.requests)
    }
}

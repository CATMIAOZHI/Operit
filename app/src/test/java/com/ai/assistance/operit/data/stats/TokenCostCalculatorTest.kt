package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenCostCalculatorTest {

    private val tokenPricing =
        ResolvedPricing(
            billingMode = BillingMode.TOKEN,
            currency = PricingCurrency.USD,
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = 0.5,
            cacheWritePricePerMillion = 0.75,
            outputPricePerMillion = 2.0,
            source = PricingSource.DEFAULT,
            known = true,
        )

    private val unknownPricing =
        ResolvedPricing(
            billingMode = BillingMode.TOKEN,
            currency = PricingCurrency.CNY,
            source = PricingSource.UNKNOWN,
            known = false,
        )

    private val countPricing =
        ResolvedPricing(
            billingMode = BillingMode.COUNT,
            currency = PricingCurrency.CNY,
            pricePerRequest = 0.01,
            source = PricingSource.DEFAULT,
            known = true,
        )

    @Test
    fun `token cost is computed per million in native currency`() {
        val cost =
            TokenCostCalculator.computeCost(
                usage =
                    TokenUsageInput(
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
cacheWriteTokens = 0L,
outputTokens = 500L,
                    ),
                pricing = tokenPricing,
            )

        assertEquals(0.0019, cost.amount!!, 1e-12)
        // 800/1e6*1 + 200/1e6*0.5 + 500/1e6*2 = 0.0008 + 0.0001 + 0.001
        assertEquals(PricingCurrency.USD, cost.currency)
        assertEquals(1000L, cost.billedInputTokens)
        assertEquals(500L, cost.billedOutputTokens)
    }

    @Test
    fun `reasoning included in output is not billed twice`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
reasoningTokens = 300L,
                reasoningIncludedInOutput = true,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)

        assertEquals(500L, cost.billedOutputTokens)
        // 1000/1e6*1 + 0 + 500/1e6*2 = 0.001 + 0.001
        assertEquals(0.002, cost.amount!!, 1e-12)
    }

    @Test
    fun `reasoning declared separate is added to billed output`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
reasoningTokens = 300L,
                reasoningIncludedInOutput = false,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)

        assertEquals(800L, cost.billedOutputTokens)
        // 1000/1e6*1 + 800/1e6*2 = 0.001 + 0.0016
        assertEquals(0.0026, cost.amount!!, 1e-12)
    }

    @Test
    fun `reasoning without inclusion declaration defaults to included`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
outputTokens = 500L,
reasoningTokens = 300L,
                reasoningIncludedInOutput = null,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)

        assertEquals(500L, cost.billedOutputTokens)
    }

    @Test
    fun `unknown output tokens produce unknown cost not zero`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
                outputTokens = null,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)

        assertNull(cost.amount)
    }

    @Test
    fun `unknown input tokens produce unknown cost not zero`() {
        val usage =
            TokenUsageInput(
                uncachedInputTokens = null,
cachedInputTokens = 0L,
outputTokens = 500L,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)

        assertNull(cost.amount)
    }

    @Test
    fun `null cached input keeps cost unknown while zero cached input is a real zero`() {
        val unknownCache =
            TokenUsageInput(
uncachedInputTokens = 1000L,
                cachedInputTokens = null,
outputTokens = 500L,
            )
        val noCacheRead =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
            )

        assertNull(TokenCostCalculator.computeCost(unknownCache, tokenPricing).amount)
        // 确认无缓存读取：1000/1e6*1 + 0 + 500/1e6*2
        assertEquals(0.002, TokenCostCalculator.computeCost(noCacheRead, tokenPricing).amount!!, 1e-12)
    }

    @Test
    fun `null cache write keeps cost unknown while zero cache write is a real zero`() {
        val unknownWrite =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
                cacheWriteTokens = null,
outputTokens = 500L,
            )
        val noCacheWrite =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
            )

        assertNull(TokenCostCalculator.computeCost(unknownWrite, tokenPricing).amount)
        assertEquals(
            0.002,
            TokenCostCalculator.computeCost(noCacheWrite, tokenPricing).amount!!,
            1e-12
        )
    }

    @Test
    fun `cache write tokens are billed at cache write price when known`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 400L,
outputTokens = 500L,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)

        // 1000/1e6*1 + 0 + 400/1e6*0.75 + 500/1e6*2 = 0.001 + 0.0003 + 0.001
        assertEquals(0.0023, cost.amount!!, 1e-12)
        assertEquals(400L, cost.billedCacheWriteTokens)
    }

    @Test
    fun `cache write without known price keeps cost unknown`() {
        val pricingWithoutWritePrice =
            tokenPricing.copy(cacheWritePricePerMillion = null)
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 400L,
outputTokens = 500L,
            )

        val cost = TokenCostCalculator.computeCost(usage, pricingWithoutWritePrice)

        assertNull(cost.amount)
    }

    @Test
    fun `unknown pricing produces unknown cost not zero`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
outputTokens = 500L,
            )

        val cost = TokenCostCalculator.computeCost(usage, unknownPricing)

        assertNull(cost.amount)
    }

    @Test
    fun `unknown pricing with zero prices is still unknown not zero`() {
        // P1-1：默认价表对未知模型给出全 0 价格但 known = false（zeroPricing），
        // 统一边界必须返回 null——即使用量完整也不能把“无法定价”算成 0 元。
        val zeroValuedUnknown =
            ResolvedPricing(
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 0.0,
                cachedInputPricePerMillion = 0.0,
                cacheWritePricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
                source = PricingSource.UNKNOWN,
                known = false,
            )
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
            )

        assertNull(TokenCostCalculator.computeCost(usage, zeroValuedUnknown).amount)
    }

    @Test
    fun `unknown count pricing with zero per request price is still unknown`() {
        // P1-1：COUNT 模式同样受 known 边界约束，不能按 0 元单次价算出伪 0。
        val unknownCount =
            ResolvedPricing(
                billingMode = BillingMode.COUNT,
                currency = PricingCurrency.USD,
                pricePerRequest = 0.0,
                source = PricingSource.UNKNOWN,
                known = false,
            )

        assertNull(TokenCostCalculator.computeCost(TokenUsageInput(outputTokens = 500L), unknownCount).amount)
    }

    @Test
    fun `count mode cost equals per request price`() {
        val cost =
            TokenCostCalculator.computeCost(
usage = TokenUsageInput(outputTokens = 500L),
                pricing = countPricing,
            )

        assertEquals(0.01, cost.amount!!, 1e-12)
        assertEquals(PricingCurrency.CNY, cost.currency)
    }

    @Test
    fun `known zero pricing yields real zero cost`() {
        val zeroPricing =
            ResolvedPricing(
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 0.0,
                cachedInputPricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
                source = PricingSource.PROVIDER_MODEL_OVERRIDE,
                known = true,
            )

        val cost =
            TokenCostCalculator.computeCost(
                usage =
                    TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
                    ),
                pricing = zeroPricing,
            )

        assertEquals(0.0, cost.amount!!, 1e-12)
    }

    @Test
    fun `total input bills at unified price when split unknown and prices equal`() {
        val equalPricing = tokenPricing.copy(cachedInputPricePerMillion = 1.0)
        val usage =
            TokenUsageInput(
                uncachedInputTokens = null,
                cachedInputTokens = null,
totalInputTokens = 1000L,
outputTokens = 500L,
                // OpenAI 兼容系/Gemini：无独立缓存写入计费概念
                cacheWriteSeparateBilling = false,
            )

        val cost = TokenCostCalculator.computeCost(usage, equalPricing)

        // 1000/1e6*1 + 500/1e6*2 = 0.001 + 0.001
        assertEquals(0.002, cost.amount!!, 1e-12)
        assertEquals(1000L, cost.billedInputTokens)
    }

    @Test
    fun `total input keeps cost unknown when split unknown and prices differ`() {
        // tokenPricing：input 1.0 vs cached 0.5，单价不同且拆分未知 → 不得伪造 uncached
        val usage =
            TokenUsageInput(
                uncachedInputTokens = null,
                cachedInputTokens = null,
totalInputTokens = 1000L,
outputTokens = 500L,
                cacheWriteSeparateBilling = false,
            )

        assertNull(TokenCostCalculator.computeCost(usage, tokenPricing).amount)
    }

    @Test
    fun `split known ignores total input fallback`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 800L,
cachedInputTokens = 200L,
totalInputTokens = 1000L,
outputTokens = 500L,
                cacheWriteSeparateBilling = false,
            )

        val cost = TokenCostCalculator.computeCost(usage, tokenPricing)
        assertEquals(1000L, cost.billedInputTokens)
        // 800/1e6*1 + 200/1e6*0.5 + 500/1e6*2
        assertEquals(0.0019, cost.amount!!, 1e-12)
    }
}

class TokenCostCurrencyTest {

    @Test
    fun `default manual rate is 7`() {
        assertEquals(7.0, TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE, 1e-12)
    }

    @Test
    fun `usd converts to cny with manual rate`() {
        val converted =
            TokenCostCurrency.convertTo(
                amount = 10.0,
                from = PricingCurrency.USD,
                to = PricingCurrency.CNY,
                manualRate = 7.0,
            )
        assertEquals(70.0, converted, 1e-12)
    }

    @Test
    fun `cny converts to usd by dividing manual rate`() {
        val converted =
            TokenCostCurrency.convertTo(
                amount = 70.0,
                from = PricingCurrency.CNY,
                to = PricingCurrency.USD,
                manualRate = 7.0,
            )
        assertEquals(10.0, converted, 1e-12)
    }

    @Test
    fun `same currency conversion is identity`() {
        val converted =
            TokenCostCurrency.convertTo(
                amount = 5.0,
                from = PricingCurrency.CNY,
                to = PricingCurrency.CNY,
                manualRate = 7.0,
            )
        assertEquals(5.0, converted, 1e-12)
    }

    @Test
    fun `changing manual rate changes converted total but not native cost`() {
        val nativeCost = 10.0 // USD

        val atRate7 = TokenCostCurrency.convertTo(nativeCost, PricingCurrency.USD, PricingCurrency.CNY, 7.0)
        val atRate8 = TokenCostCurrency.convertTo(nativeCost, PricingCurrency.USD, PricingCurrency.CNY, 8.0)

        assertEquals(70.0, atRate7, 1e-12)
        assertEquals(80.0, atRate8, 1e-12)
        assertEquals(10.0, nativeCost, 1e-12) // 原币成本不受汇率影响
    }

    @Test
    fun `historical unknown cost stays unknown after conversion`() {
        val converted =
            TokenCostCurrency.historicalCostConverted(
                costInPricingCurrency = null,
                costCurrency = PricingCurrency.USD,
                targetCurrency = PricingCurrency.CNY,
                manualRate = 7.0,
            )
        assertNull(converted)
    }

    @Test
    fun `revaluation uses current pricing instead of historical snapshot`() {
        val usage =
            TokenUsageInput(
uncachedInputTokens = 1000L,
cachedInputTokens = 0L,
cacheWriteTokens = 0L,
outputTokens = 500L,
            )
        val historicalSnapshot =
            ResolvedPricing(
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 1.0,
                cachedInputPricePerMillion = 0.5,
                outputPricePerMillion = 2.0,
                source = PricingSource.DEFAULT,
                known = true,
            )
        val currentPricing =
            ResolvedPricing(
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 2.0,
                cachedInputPricePerMillion = 1.0,
                outputPricePerMillion = 4.0,
                source = PricingSource.PROVIDER_MODEL_OVERRIDE,
                known = true,
            )

        val historical = TokenCostCalculator.computeCost(usage, historicalSnapshot)
        val revalued = TokenCostCurrency.revaluedCost(usage, currentPricing)

        // 历史按事件快照价：0.001 + 0.001 = 0.002
        assertEquals(0.002, historical.amount!!, 1e-12)
        // 重估按当前价：0.002 + 0.002 = 0.004
        assertEquals(0.004, revalued.amount!!, 1e-12)
        // 两种模式按同一手动汇率换算为统一币种
        val historicalCny =
            TokenCostCurrency.historicalCostConverted(
                historical.amount,
                historical.currency,
                PricingCurrency.CNY,
                7.0,
            )
        val revaluedCny =
            TokenCostCurrency.convertTo(
                revalued.amount!!,
                revalued.currency,
                PricingCurrency.CNY,
                7.0,
            )
        assertEquals(0.014, historicalCny!!, 1e-12)
        assertEquals(0.028, revaluedCny, 1e-12)
    }
}

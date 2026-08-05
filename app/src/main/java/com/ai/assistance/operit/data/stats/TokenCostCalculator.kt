package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode

/**
 * 事件用量输入（provider 适配层规范化后的结果）。
 *
 * - null 字段表示“未知”，不允许静默当作 0；0 表示 provider 确认该分量为 0
 *   （例如确认无缓存读取/无缓存写入）。任一未知分量导致 TOKEN 模式成本未知。
 * - [reasoningIncludedInOutput] 是推理 token 归一化边界：
 *   true = provider 的 output 计数已包含推理 token；
 *   false = 推理 token 独立计数，计费时按输出单价补算；
 *   null = provider 未声明，按“已包含”处理，避免重复收费。
 */
data class TokenUsageInput(
    val uncachedInputTokens: Int? = null,
    val cachedInputTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val reasoningIncludedInOutput: Boolean? = null,
)

/** 单次请求的原币成本计算结果；[amount] 为 null 表示未知（非 0）。 */
data class TokenCostResult(
    val amount: Double?,
    val currency: PricingCurrency,
    val billedInputTokens: Int? = null,
    val billedCacheWriteTokens: Int? = null,
    val billedOutputTokens: Int? = null,
)

/**
 * 原币费用计算。
 *
 * - TOKEN 模式：计费输入 = uncached + cached（两者都必须已知，null 即未知→成本 null）；
 *   缓存写入按 cacheWriteTokens 独立计费（未知→成本 null；0 跳过；>0 需要缓存写入单价，
 *   该单价未解析到时成本 null）；计费输出 = output + 独立计数的 reasoning。
 * - COUNT 模式：成本 = 单次价格（每事件一次请求）。
 * - 价格为“每百万 token”原币单价；cached 单价缺省已由 [TokenPriceResolver] 回填。
 */
object TokenCostCalculator {

    fun billedOutputTokens(usage: TokenUsageInput): Int? {
        val output = usage.outputTokens ?: return null
        val separateReasoning =
            if (usage.reasoningIncludedInOutput == false && usage.reasoningTokens != null) {
                usage.reasoningTokens
            } else {
                0
            }
        return output + separateReasoning
    }

    fun billedInputTokens(usage: TokenUsageInput): Int? {
        val uncached = usage.uncachedInputTokens ?: return null
        val cached = usage.cachedInputTokens ?: return null
        return uncached + cached
    }

    fun computeCost(usage: TokenUsageInput, pricing: ResolvedPricing): TokenCostResult {
        if (pricing.billingMode == BillingMode.COUNT) {
            val price = pricing.pricePerRequest
            return TokenCostResult(
                amount = price,
                currency = pricing.currency,
            )
        }

        val billedInput = billedInputTokens(usage)
        val billedOutput = billedOutputTokens(usage)
        if (billedInput == null || billedOutput == null) {
            return TokenCostResult(amount = null, currency = pricing.currency)
        }
        val inputPrice = pricing.inputPricePerMillion
        val cachedPrice = pricing.cachedInputPricePerMillion
        val outputPrice = pricing.outputPricePerMillion
        if (inputPrice == null || cachedPrice == null || outputPrice == null) {
            return TokenCostResult(amount = null, currency = pricing.currency)
        }

        val cachedTokens = usage.cachedInputTokens ?: 0
        val uncachedTokens = billedInput - cachedTokens
        var amount =
            uncachedTokens / 1_000_000.0 * inputPrice +
                cachedTokens / 1_000_000.0 * cachedPrice +
                billedOutput / 1_000_000.0 * outputPrice

        // 缓存写入：未知 → 成本未知；确认 0 → 不参与；> 0 → 需要缓存写入单价
        val cacheWriteTokens = usage.cacheWriteTokens
        if (cacheWriteTokens == null) {
            return TokenCostResult(
                amount = null,
                currency = pricing.currency,
                billedInputTokens = billedInput,
                billedCacheWriteTokens = null,
                billedOutputTokens = billedOutput,
            )
        }
        if (cacheWriteTokens > 0) {
            val cacheWritePrice = pricing.cacheWritePricePerMillion
            if (cacheWritePrice == null) {
                return TokenCostResult(amount = null, currency = pricing.currency)
            }
            amount += cacheWriteTokens / 1_000_000.0 * cacheWritePrice
        }

        return TokenCostResult(
            amount = amount,
            currency = pricing.currency,
            billedInputTokens = billedInput,
            billedCacheWriteTokens = cacheWriteTokens,
            billedOutputTokens = billedOutput,
        )
    }
}

/**
 * 币种换算语义（阶段 1 核心）：
 *
 * - 汇率只由用户手动设置；未设置时使用默认估算值 7.0（界面必须标记为估算）。
 * - 事件保存发生时的原币价格与原币成本，不冻结汇率；换算只发生在展示层，
 *   使用当前手动汇率。因此修改汇率会改变“统一币种”的换算总计，
 *   但不会改变历史原币成本。
 */
object TokenCostCurrency {

    /** 未设置手动汇率时的默认估算值（1 USD = 7.0 CNY）。 */
    const val DEFAULT_USD_TO_CNY_RATE = 7.0

    /** 历史原币成本按当前手动汇率换算到目标币种；未知（null）原样保持未知。 */
    fun historicalCostConverted(
        costInPricingCurrency: Double?,
        costCurrency: PricingCurrency,
        targetCurrency: PricingCurrency,
        manualRate: Double,
    ): Double? {
        val cost = costInPricingCurrency ?: return null
        return convertTo(cost, costCurrency, targetCurrency, manualRate)
    }

    /** 当前价格重估：以当前解析价格 × 事件用量（原币），不触碰历史快照。 */
    fun revaluedCost(usage: TokenUsageInput, currentPricing: ResolvedPricing): TokenCostResult =
        TokenCostCalculator.computeCost(usage, currentPricing)

    fun convertTo(
        amount: Double,
        from: PricingCurrency,
        to: PricingCurrency,
        manualRate: Double,
    ): Double {
        require(manualRate > 0.0) { "manual rate must be positive" }
        if (from == to) return amount
        return if (from == PricingCurrency.USD) {
            amount * manualRate
        } else {
            amount / manualRate
        }
    }
}

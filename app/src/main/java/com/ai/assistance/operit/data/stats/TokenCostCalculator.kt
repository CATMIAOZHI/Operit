package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode

/**
 * 事件用量输入（provider 适配层规范化后的结果）。
 *
 * - null 字段表示“未知”，不允许静默当作 0；0 表示 provider 确认该分量为 0
 *   （例如确认无缓存读取/无缓存写入）。任一未知分量导致 TOKEN 模式成本未知。
 * - token 字段为 [Long]：provider 原值可能超过 Int 范围，聚合与计费全程 Long
 *   运算，绝不因 Int 溢出产生负数落账；负值在适配层已被拒绝为未知。
 * - [totalInputTokens]：provider 明确上报的总输入（含缓存命中/写入）。当
 *   cached/uncached 拆分未知（[uncachedInputTokens]/[cachedInputTokens] 为 null）
 *   时，**只有**在输入与缓存输入单价相同（拆分不影响计费）的前提下才允许按
 *   总输入计费；单价不同则成本仍保持未知，绝不伪造 uncached。
 * - [cacheWriteSeparateBilling]：false = provider 无独立缓存写入计费概念
 *   （OpenAI 兼容系/Gemini/本地/ToolPkg），cacheWriteTokens 缺失或为 0 都不阻碍
 *   费用计算（写入成本已包含在输入单价内）；true = 缓存写入独立计费（Anthropic），
 *   此时该分量未知会导致费用未知。
 * - [reasoningIncludedInOutput] 是推理 token 归一化边界：
 *   true = provider 的 output 计数已包含推理 token；
 *   false = 推理 token 独立计数，计费时按输出单价补算；
 *   null = provider 未声明，按“已包含”处理，避免重复收费。
 */
data class TokenUsageInput(
    val uncachedInputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val totalInputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val reasoningIncludedInOutput: Boolean? = null,
    val cacheWriteSeparateBilling: Boolean = true,
)

/** 单次请求的原币成本计算结果；[amount] 为 null 表示未知（非 0）。 */
data class TokenCostResult(
    val amount: Double?,
    val currency: PricingCurrency,
    val billedInputTokens: Long? = null,
    val billedCacheWriteTokens: Long? = null,
    val billedOutputTokens: Long? = null,
)

/**
 * 原币费用计算。
 *
 * - TOKEN 模式：计费输入 = uncached + cached（两者都必须已知，null 即未知→成本 null）；
 *   缓存写入在 [TokenUsageInput.cacheWriteSeparateBilling] 为 true 时独立计费
 *   （未知→成本 null；0 跳过；>0 需要缓存写入单价，缺失则成本 null）；
 *   为 false（无独立缓存写入概念）时不单独计费，字段缺失不影响成本。
 * - COUNT 模式：成本 = 单次价格（每事件一次请求）。
 * - 价格为“每百万 token”原币单价；cached 单价缺省已由 [TokenPriceResolver] 回填。
 */
object TokenCostCalculator {

    /** Context thresholds use input only, including cache reads and independent cache writes. */
    fun contextInputTokens(usage: TokenUsageInput?, attemptCount: Int = 1): Long? {
        // A logical request may sum multiple retries. That sum is not any one prompt's length.
        if (usage == null || attemptCount != 1) return null
        if (usage.cacheWriteSeparateBilling &&
            (usage.cachedInputTokens == null || usage.cacheWriteTokens == null)) return null
        usage.totalInputTokens?.let { return it }
        val input = billedInputTokens(usage) ?: return null
        return if (usage.cacheWriteSeparateBilling) saturatedAdd(input, usage.cacheWriteTokens!!)
        else input
    }

    fun billedOutputTokens(usage: TokenUsageInput): Long? {
        val output = usage.outputTokens ?: return null
        val separateReasoning =
            if (usage.reasoningIncludedInOutput == false && usage.reasoningTokens != null) {
                usage.reasoningTokens
            } else {
                0L
            }
        return saturatedAdd(output, separateReasoning)
    }

    fun billedInputTokens(usage: TokenUsageInput): Long? {
        val uncached = usage.uncachedInputTokens ?: return null
        val cached = usage.cachedInputTokens ?: return null
        return saturatedAdd(uncached, cached)
    }

    fun computeCost(usage: TokenUsageInput, pricing: ResolvedPricing): TokenCostResult {
        // 统一边界（P1-1）：未知定价（known = false，如未知模型的内置全 0 缺省或
        // 空价格覆盖行）无论用量是否完整，成本一律为 null（未知），绝不算出伪 0。
        // 阶段 2 落账与阶段 3 重估都走本入口，因此该防线同时保护两条路径。
        if (!pricing.known) {
            return TokenCostResult(amount = null, currency = pricing.currency)
        }
        if (pricing.billingMode == BillingMode.COUNT) {
            val price = pricing.pricePerRequest?.takeIf { it.isFinite() }
            return TokenCostResult(
                amount = price,
                currency = pricing.currency,
            )
        }

        val billedOutput = billedOutputTokens(usage)
        if (billedOutput == null) {
            return TokenCostResult(amount = null, currency = pricing.currency)
        }
        val inputPrice = pricing.inputPricePerMillion
        val cachedPrice = pricing.cachedInputPricePerMillion
        val outputPrice = pricing.outputPricePerMillion
        if (inputPrice == null || cachedPrice == null || outputPrice == null ||
            !inputPrice.isFinite() || !cachedPrice.isFinite() || !outputPrice.isFinite()
        ) {
            return TokenCostResult(amount = null, currency = pricing.currency)
        }

        // 输入计费：
        // - 拆分已知 → 按 uncached/cached 分量各自计价；
        // - 拆分未知（cached details 缺失）但总输入已知 → 仅当输入与缓存输入
        //   单价相同（拆分不影响计费）时按总输入计价；单价不同则成本保持未知，
        //   绝不把总输入伪装成 uncached。
        val cachedTokens = usage.cachedInputTokens
        val uncachedTokens = usage.uncachedInputTokens
        val billedInput: Long
        val inputAmount: Double
        if (cachedTokens != null && uncachedTokens != null) {
            billedInput = saturatedAdd(uncachedTokens, cachedTokens)
            inputAmount =
                safeAdd(
                    scaledTokenCost(uncachedTokens, inputPrice),
                    scaledTokenCost(cachedTokens, cachedPrice),
                ) ?: return TokenCostResult(amount = null, currency = pricing.currency)
        } else {
            val total = usage.totalInputTokens
            if (total == null || inputPrice != cachedPrice) {
                return TokenCostResult(amount = null, currency = pricing.currency)
            }
            billedInput = total
            inputAmount =
                scaledTokenCost(total, inputPrice)
                    ?: return TokenCostResult(amount = null, currency = pricing.currency)
        }
        var amount =
            safeAdd(inputAmount, scaledTokenCost(billedOutput, outputPrice))
                ?: return TokenCostResult(amount = null, currency = pricing.currency)

        // 缓存写入：
        // - 独立计费概念下未知 → 成本未知（不静默当作 0）；
        // - 确认 0 → 不参与；
        // - > 0 且独立计费 → 需要缓存写入单价，缺失则成本未知；
        // - 非独立计费（OpenAI 兼容系等）→ 写入成本已包含在输入单价内，不单独计费。
        val cacheWriteTokens = usage.cacheWriteTokens
        if (cacheWriteTokens == null && usage.cacheWriteSeparateBilling) {
            return TokenCostResult(
                amount = null,
                currency = pricing.currency,
                billedInputTokens = billedInput,
                billedCacheWriteTokens = null,
                billedOutputTokens = billedOutput,
            )
        }
        if (cacheWriteTokens != null && cacheWriteTokens > 0 && usage.cacheWriteSeparateBilling) {
            val cacheWritePrice = pricing.cacheWritePricePerMillion
            if (cacheWritePrice == null || !cacheWritePrice.isFinite()) {
                return TokenCostResult(
                    amount = null,
                    currency = pricing.currency,
                    billedInputTokens = billedInput,
                    billedCacheWriteTokens = cacheWriteTokens,
                    billedOutputTokens = billedOutput,
                )
            }
            amount =
                safeAdd(amount, scaledTokenCost(cacheWriteTokens, cacheWritePrice))
                    ?: return TokenCostResult(
                        amount = null,
                        currency = pricing.currency,
                        billedInputTokens = billedInput,
                        billedCacheWriteTokens = cacheWriteTokens,
                        billedOutputTokens = billedOutput,
                    )
        }

        return TokenCostResult(
            amount = amount,
            currency = pricing.currency,
            billedInputTokens = billedInput,
            billedCacheWriteTokens = cacheWriteTokens,
            billedOutputTokens = billedOutput,
        )
    }

    /**
     * 饱和加法：溢出时钳制到 [Long.MAX_VALUE]，绝不出现负数或回绕；调用方
     * 只接受非负分量，负数视为异常数据在适配层已拒绝，这里做最终防线。
     */
    internal fun saturatedAdd(left: Long, right: Long): Long =
        if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun scaledTokenCost(tokens: Long, pricePerMillion: Double): Double? =
        (tokens / 1_000_000.0 * pricePerMillion).takeIf { it.isFinite() }

    private fun safeAdd(left: Double?, right: Double?): Double? {
        if (left == null || right == null) return null
        return (left + right).takeIf { it.isFinite() }
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
    ): Double? {
        require(manualRate.isFinite() && manualRate > 0.0) { "manual rate must be finite and positive" }
        if (!amount.isFinite()) return null
        val converted = if (from == to) {
            amount
        } else if (from == PricingCurrency.USD) {
            amount * manualRate
        } else {
            amount / manualRate
        }
        return converted.takeIf { it.isFinite() }
    }
}

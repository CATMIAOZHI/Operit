package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity

/** 旧系统（DataStore）中用户保存的价格设置；null 字段表示“未设置”。 */
data class LegacyPriceSettings(
    val billingMode: BillingMode? = null,
    val inputPricePerMillion: Double? = null,
    val cachedInputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val pricePerRequest: Double? = null,
) {
    /**
     * 旧系统约定：价格键缺失时读数为 0，且 0 与“未设置”不可区分，
     * 因此只有 > 0 的值才视为用户设置。
     */
    fun hasAnyUserSetting(): Boolean =
        billingMode != null ||
            (inputPricePerMillion ?: 0.0) > 0.0 ||
            (cachedInputPricePerMillion ?: 0.0) > 0.0 ||
            (outputPricePerMillion ?: 0.0) > 0.0 ||
            (pricePerRequest ?: 0.0) > 0.0
}

/**
 * 解析完成的定价：TOKEN 模式下价格均已按层级回填（cached 缺省回退到 input），
 * [known] 为 false 表示“未知定价”，对应成本必须为 null，不得静默当作 0。
 *
 * [cacheWritePricePerMillion] 无内置/旧系统数据来源时保持 null（未知）：
 * 事件中 cacheWriteTokens > 0 且价格未知时成本为 null；cacheWriteTokens == 0
 * （确认无缓存写入）时不需要该价格。不猜测缓存写入单价。
 */
data class ResolvedPricing(
    val billingMode: BillingMode,
    val currency: PricingCurrency,
    val inputPricePerMillion: Double? = null,
    val cachedInputPricePerMillion: Double? = null,
    val cacheWritePricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val pricePerRequest: Double? = null,
    val source: PricingSource,
    val known: Boolean,
)

/**
 * 价格层级解析：`目录默认价 -> 旧 DataStore -> provider/model 覆盖 -> 配置覆盖`。
 * 每一层按字段叠加：null 继承，显式 0 保留；切换 billingMode 时清空不兼容字段。
 */
object TokenPriceResolver {

    const val SCOPE_CONFIG = "CONFIG"
    const val SCOPE_PROVIDER_MODEL = "PROVIDER_MODEL"

    /**
     * 构造已规范化的覆盖行（便捷工厂，等价于
     * [TokenStatPriceOverrideEntity.normalized]）。
     * provider/model 规范化（trim + 小写 + 空白压缩）、configId 仅 trim；
     * PROVIDER_MODEL 范围强制 configId 为空串（“不限定配置实例”）。
     * 非法 scope 或空白 provider/model 抛 [IllegalArgumentException]。
     * 规范化后相同业务组合在数据库中必然冲突并 REPLACE 覆盖（见实体唯一索引）。
     */
    fun normalizedOverride(
        scope: String,
        provider: String,
        model: String,
        configId: String?,
        billingMode: BillingMode,
        pricingCurrency: String,
        inputPricePerMillion: Double? = null,
        cachedInputPricePerMillion: Double? = null,
        cacheWritePricePerMillion: Double? = null,
        outputPricePerMillion: Double? = null,
        pricePerRequest: Double? = null,
    ): TokenStatPriceOverrideEntity =
        TokenStatPriceOverrideEntity.normalized(
            scope = scope,
            provider = provider,
            model = model,
            configId = configId,
            billingMode = billingMode.name,
            pricingCurrency = pricingCurrency,
            inputPricePerMillion = inputPricePerMillion,
            cachedInputPricePerMillion = cachedInputPricePerMillion,
            cacheWritePricePerMillion = cacheWritePricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
            pricePerRequest = pricePerRequest,
        )

    /**
     * 解析定价：按**规范化业务字段**（而非任何主键）匹配覆盖行，
     * 行内容与查询键一致才命中，键/内容错配不可能造成错误解析。
     * 顺序：目录默认价 -> 旧系统价格 -> PROVIDER_MODEL 覆盖 -> CONFIG 覆盖。
     */
    fun resolve(
        provider: String,
        model: String,
        configId: String?,
        overrides: List<TokenStatPriceOverrideEntity>,
        legacyOverride: LegacyPriceSettings?,
        defaults: ModelPricingDefaults,
        contextInputTokens: Long? = null,
        selectContextTier: Boolean = false,
    ): ResolvedPricing {
        val canonicalProvider = TokenStatIdentityResolver.normalizeProvider(provider)
        val canonicalModel = TokenStatIdentityResolver.normalizeModelName(model)
        val canonicalConfigId = configId?.trim().orEmpty()

        val providerOverride = overrides.firstOrNull {
            it.scope == SCOPE_PROVIDER_MODEL &&
                TokenStatIdentityResolver.normalizeProvider(it.provider) == canonicalProvider &&
                TokenStatIdentityResolver.normalizeModelName(it.model) == canonicalModel &&
                it.configId.isBlank()
        }
        val configOverride = if (canonicalConfigId.isNotEmpty()) overrides.firstOrNull {
            it.scope == SCOPE_CONFIG &&
                TokenStatIdentityResolver.normalizeProvider(it.provider) == canonicalProvider &&
                TokenStatIdentityResolver.normalizeModelName(it.model) == canonicalModel &&
                it.configId.trim() == canonicalConfigId
        } else null

        var pricing = MutablePricing.fromDefaults(
            if (selectContextTier) defaults.forContext(contextInputTokens) else defaults
        )
        var source = if (pricing.baseKnown) PricingSource.DEFAULT else PricingSource.UNKNOWN
        if (legacyOverride != null && legacyOverride.hasAnyUserSetting()) {
            pricing.applyLegacy(legacyOverride)
            source = PricingSource.LEGACY_OVERRIDE
        }
        providerOverride?.let {
            pricing.applyOverride(it)
            source = PricingSource.PROVIDER_MODEL_OVERRIDE
        }
        configOverride?.let {
            pricing.applyOverride(it)
            source = PricingSource.CONFIG_OVERRIDE
        }
        return pricing.toResolved(source)
    }

    private data class MutablePricing(
        var billingMode: BillingMode,
        var currency: PricingCurrency,
        var input: Double?,
        var cached: Double?,
        var cacheWrite: Double?,
        var output: Double?,
        var perRequest: Double?,
        val baseKnown: Boolean,
        var hasExplicitLayer: Boolean = false,
    ) {
        companion object {
            fun fromDefaults(defaults: ModelPricingDefaults) = MutablePricing(
                billingMode = defaults.billingMode,
                currency = defaults.currency,
                input = defaults.inputPricePerMillion.takeIf { defaults.hasInputPrice },
                cached = defaults.cachedInputPricePerMillion.takeIf { defaults.hasCachedInputPrice },
                cacheWrite = defaults.cacheWritePricePerMillion,
                output = defaults.outputPricePerMillion.takeIf { defaults.hasOutputPrice },
                perRequest = defaults.pricePerRequest.takeIf { defaults.hasPricePerRequest },
                baseKnown = defaults.known || if (defaults.billingMode == BillingMode.COUNT) {
                    defaults.hasPricePerRequest
                } else {
                    defaults.hasInputPrice && defaults.hasCachedInputPrice && defaults.hasOutputPrice
                },
            )
        }

        fun applyLegacy(layer: LegacyPriceSettings) {
            layer.billingMode?.let { switchMode(it) }
            // LegacyStorage historically used zero as “not set”; preserve that compatibility rule.
            layer.inputPricePerMillion?.takeIf { it > 0.0 }?.let { input = it }
            layer.cachedInputPricePerMillion?.takeIf { it > 0.0 }?.let { cached = it }
            layer.outputPricePerMillion?.takeIf { it > 0.0 }?.let { output = it }
            layer.pricePerRequest?.takeIf { it > 0.0 }?.let { perRequest = it }
            hasExplicitLayer = true
        }

        fun applyOverride(row: TokenStatPriceOverrideEntity) {
            hasExplicitLayer = true
            switchMode(BillingMode.fromString(row.billingMode))
            currency = parseCurrency(row.pricingCurrency)
            if (billingMode == BillingMode.COUNT) {
                row.pricePerRequest?.let { perRequest = it }
            } else {
                row.inputPricePerMillion?.let { input = it }
                row.cachedInputPricePerMillion?.let { cached = it }
                row.cacheWritePricePerMillion?.let { cacheWrite = it }
                row.outputPricePerMillion?.let { output = it }
            }
        }

        private fun switchMode(next: BillingMode) {
            if (next == billingMode) return
            billingMode = next
            if (next == BillingMode.COUNT) {
                input = null; cached = null; cacheWrite = null; output = null
                // A per-request value attached to token-mode defaults belongs to the
                // inactive mode. Only the COUNT layer may opt back into it explicitly.
                perRequest = null
            } else {
                perRequest = null
            }
        }

        fun toResolved(source: PricingSource): ResolvedPricing {
            val complete = if (billingMode == BillingMode.COUNT) {
                perRequest != null
            } else {
                input != null && cached != null && output != null
            }
            val known = complete && (baseKnown || hasExplicitLayer)
            return ResolvedPricing(
                billingMode = billingMode,
                currency = currency,
                inputPricePerMillion = if (billingMode == BillingMode.TOKEN) input else null,
                cachedInputPricePerMillion = if (billingMode == BillingMode.TOKEN) cached else null,
                cacheWritePricePerMillion = if (billingMode == BillingMode.TOKEN) cacheWrite else null,
                outputPricePerMillion = if (billingMode == BillingMode.TOKEN) output else null,
                pricePerRequest = if (billingMode == BillingMode.COUNT) perRequest else null,
                source = if (known) source else PricingSource.UNKNOWN,
                known = known,
            )
        }
    }

    private fun parseCurrency(raw: String): PricingCurrency =
        if (raw.equals("CNY", ignoreCase = true)) PricingCurrency.CNY else PricingCurrency.USD
}

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
 * 价格层级解析：`内置模型默认价 -> provider/model 覆盖 -> 特定 API 配置覆盖`，
 * 阶段 1 额外桥接旧 DataStore 中用户保存的 provider/model 价格（[LegacyPriceSettings]），
 * 顺序为：CONFIG 覆盖 > PROVIDER_MODEL 覆盖 > 旧系统价格 > 内置默认价。
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
     * 顺序：CONFIG 覆盖 > PROVIDER_MODEL 覆盖 > 旧系统价格 > 内置默认价。
     */
    fun resolve(
        provider: String,
        model: String,
        configId: String?,
        overrides: List<TokenStatPriceOverrideEntity>,
        legacyOverride: LegacyPriceSettings?,
        defaults: ModelPricingDefaults,
    ): ResolvedPricing {
        val canonicalProvider = TokenStatIdentityResolver.normalizeProvider(provider)
        val canonicalModel = TokenStatIdentityResolver.normalizeModelName(model)
        val canonicalConfigId = configId?.trim().orEmpty()

        if (canonicalConfigId.isNotEmpty()) {
            overrides.firstOrNull {
                it.scope == SCOPE_CONFIG &&
                    TokenStatIdentityResolver.normalizeProvider(it.provider) == canonicalProvider &&
                    TokenStatIdentityResolver.normalizeModelName(it.model) == canonicalModel &&
                    it.configId.trim() == canonicalConfigId
            }?.let { return fromOverrideRow(it, PricingSource.CONFIG_OVERRIDE) }
        }

        overrides.firstOrNull {
            it.scope == SCOPE_PROVIDER_MODEL &&
                TokenStatIdentityResolver.normalizeProvider(it.provider) == canonicalProvider &&
                TokenStatIdentityResolver.normalizeModelName(it.model) == canonicalModel &&
                it.configId.isBlank()
        }?.let { return fromOverrideRow(it, PricingSource.PROVIDER_MODEL_OVERRIDE) }

        if (legacyOverride != null && legacyOverride.hasAnyUserSetting()) {
            return fromLegacy(legacyOverride, defaults)
        }

        return fromDefaults(defaults)
    }

    /** 数据库覆盖行：显式实体，null 表示未使用；价格 0 是用户的真实设置。 */
    private fun fromOverrideRow(
        row: TokenStatPriceOverrideEntity,
        source: PricingSource,
    ): ResolvedPricing {
        val billingMode = BillingMode.fromString(row.billingMode)
        val currency = parseCurrency(row.pricingCurrency)
        return if (billingMode == BillingMode.COUNT) {
            ResolvedPricing(
                billingMode = billingMode,
                currency = currency,
                pricePerRequest = row.pricePerRequest,
                source = source,
                known = row.pricePerRequest != null,
            )
        } else {
            val input = row.inputPricePerMillion
            val cached = row.cachedInputPricePerMillion ?: input
            val output = row.outputPricePerMillion
            ResolvedPricing(
                billingMode = billingMode,
                currency = currency,
                inputPricePerMillion = input,
                cachedInputPricePerMillion = cached,
                cacheWritePricePerMillion = row.cacheWritePricePerMillion,
                outputPricePerMillion = output,
                source = source,
                known = input != null || output != null,
            )
        }
    }

    /** 旧系统价格：缺省分量回退到内置默认价；> 0 才算用户设置（旧约定 0 == 未设置）。 */
    private fun fromLegacy(
        legacy: LegacyPriceSettings,
        defaults: ModelPricingDefaults,
    ): ResolvedPricing {
        val billingMode = legacy.billingMode ?: defaults.billingMode
        return if (billingMode == BillingMode.COUNT) {
            val pricePerRequest =
                legacy.pricePerRequest?.takeIf { it > 0.0 } ?: defaults.pricePerRequest
            ResolvedPricing(
                billingMode = billingMode,
                currency = defaults.currency,
                pricePerRequest = pricePerRequest,
                source = PricingSource.LEGACY_OVERRIDE,
                known = pricePerRequest > 0.0,
            )
        } else {
            val input =
                legacy.inputPricePerMillion?.takeIf { it > 0.0 }
                    ?: defaults.inputPricePerMillion
            val cached =
                legacy.cachedInputPricePerMillion?.takeIf { it > 0.0 }
                    ?: defaults.cachedInputPricePerMillion
                    ?: input
            val output =
                legacy.outputPricePerMillion?.takeIf { it > 0.0 }
                    ?: defaults.outputPricePerMillion
            ResolvedPricing(
                billingMode = billingMode,
                currency = defaults.currency,
                inputPricePerMillion = input,
                cachedInputPricePerMillion = cached,
                // 旧系统没有缓存写入计费，不猜测：保持未知
                cacheWritePricePerMillion = null,
                outputPricePerMillion = output,
                source = PricingSource.LEGACY_OVERRIDE,
                known = (input ?: 0.0) > 0.0 || (cached ?: 0.0) > 0.0 || (output ?: 0.0) > 0.0,
            )
        }
    }

    /** 内置默认价；默认值表对未知模型/供应商给出全 0 缺省（zeroPricing），视为未知。 */
    private fun fromDefaults(defaults: ModelPricingDefaults): ResolvedPricing {
        val known =
            if (defaults.billingMode == BillingMode.COUNT) {
                defaults.pricePerRequest > 0.0
            } else {
                defaults.inputPricePerMillion > 0.0 ||
                    defaults.cachedInputPricePerMillion > 0.0 ||
                    defaults.outputPricePerMillion > 0.0
            }
        return ResolvedPricing(
            billingMode = defaults.billingMode,
            currency = defaults.currency,
            inputPricePerMillion = defaults.inputPricePerMillion,
            cachedInputPricePerMillion = defaults.cachedInputPricePerMillion,
            // 内置价格表没有缓存写入单价，不猜测：保持未知
            cacheWritePricePerMillion = null,
            outputPricePerMillion = defaults.outputPricePerMillion,
            pricePerRequest = defaults.pricePerRequest,
            source = if (known) PricingSource.DEFAULT else PricingSource.UNKNOWN,
            known = known,
        )
    }

    private fun parseCurrency(raw: String): PricingCurrency =
        if (raw.equals("CNY", ignoreCase = true)) PricingCurrency.CNY else PricingCurrency.USD
}

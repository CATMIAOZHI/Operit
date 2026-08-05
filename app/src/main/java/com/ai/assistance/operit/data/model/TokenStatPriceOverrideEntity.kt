package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 价格覆盖的作用范围（固定枚举，数据库只持久化枚举名）。 */
enum class PriceOverrideScope {
    PROVIDER_MODEL,
    CONFIG;

    companion object {
        /**
         * 严格解析：非固定枚举名返回 null。写入边界用它拒绝非法 scope，
         * 数据库只可能包含本枚举的 name。
         */
        fun fromNameOrNull(name: String?): PriceOverrideScope? =
            entries.firstOrNull { it.name == name }
    }
}

/**
 * 价格覆盖：`内置模型默认价 -> provider/model 覆盖 -> 特定 API 配置覆盖` 层级中的
 * 用户覆盖层（后两层）。
 *
 * - [scope] = PROVIDER_MODEL：适用于所有配置实例的 provider:model。
 * - [scope] = CONFIG：仅适用于指定 [configId] 的配置实例，优先级最高。
 * 价格为原币单价（每百万 token，或按次计费单价），由 [pricingCurrency] 声明币种。
 * 价格字段为 null 表示该计费方式下不使用。
 *
 * 唯一性由**规范化业务字段本身**在数据库层强制（UNIQUE 索引）：
 * `(scope, provider, model, configId)` 四个字段均为非空规范化值——
 * provider/model 规范化（trim + 小写 + 空白压缩），[configId] 仅 trim；
 * PROVIDER_MODEL 范围用空串 `""` 表示“不限定配置实例”。
 * [rowId] 只是内部自增主键，不承载业务语义，REPLACE 后可能变化。
 *
 * 写入必须经过 [TokenStatPriceOverrideEntity.normalized]（或等价边界）：
 * 该工厂是唯一保证“规范化后才落库”的构造入口，非法 scope / 空白 provider/model
 * 直接抛 [IllegalArgumentException]。DAO 的公开写入方法只接受本工厂产物，
 * 不保留任意 entity 的公开插入路径。
 */
@Entity(
    tableName = "token_stat_price_overrides",
    indices = [Index(value = ["scope", "provider", "model", "configId"], unique = true)],
)
data class TokenStatPriceOverrideEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowId") val rowId: Long = 0,
    @ColumnInfo(name = "scope") val scope: String,
    @ColumnInfo(name = "provider") val provider: String,
    @ColumnInfo(name = "model") val model: String,
    @ColumnInfo(name = "configId") val configId: String,
    @ColumnInfo(name = "billingMode") val billingMode: String,
    @ColumnInfo(name = "pricingCurrency") val pricingCurrency: String,
    @ColumnInfo(name = "inputPricePerMillion") val inputPricePerMillion: Double? = null,
    @ColumnInfo(name = "cachedInputPricePerMillion") val cachedInputPricePerMillion: Double? = null,
    @ColumnInfo(name = "cacheWritePricePerMillion") val cacheWritePricePerMillion: Double? = null,
    @ColumnInfo(name = "outputPricePerMillion") val outputPricePerMillion: Double? = null,
    @ColumnInfo(name = "pricePerRequest") val pricePerRequest: Double? = null,
) {
    companion object {
        private fun normalizeProvider(provider: String): String = provider.trim().lowercase()

        private fun normalizeModel(model: String): String =
            model.trim().lowercase().replace(Regex("\\s+"), " ")

        /**
         * 规范化构造（唯一写入入口）：scope 必须是固定枚举名，provider/model 规范化，
         * configId 仅 trim；PROVIDER_MODEL 范围强制 configId 为空串。
         * 非法 scope 或规范化后为空白的 provider/model 抛 [IllegalArgumentException]。
         * 规范化后相同业务组合在数据库中必然冲突并 REPLACE 覆盖（见实体唯一索引）。
         */
        fun normalized(
            scope: String,
            provider: String,
            model: String,
            configId: String?,
            billingMode: String,
            pricingCurrency: String,
            inputPricePerMillion: Double? = null,
            cachedInputPricePerMillion: Double? = null,
            cacheWritePricePerMillion: Double? = null,
            outputPricePerMillion: Double? = null,
            pricePerRequest: Double? = null,
        ): TokenStatPriceOverrideEntity {
            val validScope =
                PriceOverrideScope.fromNameOrNull(scope)
                    ?: throw IllegalArgumentException("非法价格覆盖 scope: $scope")
            val canonicalProvider = normalizeProvider(provider)
            val canonicalModel = normalizeModel(model)
            require(canonicalProvider.isNotEmpty()) { "provider must not be blank" }
            require(canonicalModel.isNotEmpty()) { "model must not be blank" }
            return TokenStatPriceOverrideEntity(
                scope = validScope.name,
                provider = canonicalProvider,
                model = canonicalModel,
                configId =
                    if (validScope == PriceOverrideScope.PROVIDER_MODEL) {
                        ""
                    } else {
                        configId?.trim().orEmpty()
                    },
                billingMode = billingMode,
                pricingCurrency = pricingCurrency,
                inputPricePerMillion = inputPricePerMillion,
                cachedInputPricePerMillion = cachedInputPricePerMillion,
                cacheWritePricePerMillion = cacheWritePricePerMillion,
                outputPricePerMillion = outputPricePerMillion,
                pricePerRequest = pricePerRequest,
            )
        }
    }
}

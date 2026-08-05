package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 逐请求统计事件账本（阶段 1 数据契约）。
 *
 * - 一行代表一次真实请求/尝试；[eventId] 由记录链路提供稳定标识，用于防止重复入账。
 * - 数值型 token/价格/成本字段为 null 表示“未知”，禁止静默当作 0；
 *   0 表示 provider 确认该分量（如无缓存读取、无缓存写入）为 0。
 * - 事件保存发生时的原币价格快照与原币成本（[pricingCurrency]/[costInPricingCurrency]），
 *   不冻结汇率；跨币种展示换算始终使用当前手动汇率。
 * - [reasoningIncludedInOutput] 是 provider 适配层规范化后的边界声明：
 *   true = provider 的 output 计数已包含推理 token（计费时不得再加推理）；
 *   false = 推理 token 独立计数，需按输出单价补算；
 *   null = provider 未声明，计费时按“已包含”处理以避免重复收费。
 * - 不保存提示词/回复正文，也不保存任何凭据。
 */
@Entity(
    tableName = "token_stat_events",
    foreignKeys = [
        ForeignKey(
            entity = TokenStatIdentityEntity::class,
            parentColumns = ["identityId"],
            childColumns = ["statIdentityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["statIdentityId", "startedAtMs"]),
        Index(value = ["startedAtMs"]),
        Index(value = ["category", "startedAtMs"]),
    ],
)
data class TokenStatEventEntity(
    @PrimaryKey @ColumnInfo(name = "eventId") val eventId: String,
    @ColumnInfo(name = "statIdentityId") val statIdentityId: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "startedAtMs") val startedAtMs: Long,
    @ColumnInfo(name = "endedAtMs") val endedAtMs: Long,
    @ColumnInfo(name = "firstTokenAtMs") val firstTokenAtMs: Long? = null,
    @ColumnInfo(name = "uncachedInputTokens") val uncachedInputTokens: Int? = null,
    @ColumnInfo(name = "cachedInputTokens") val cachedInputTokens: Int? = null,
    @ColumnInfo(name = "cacheWriteTokens") val cacheWriteTokens: Int? = null,
    @ColumnInfo(name = "outputTokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoningTokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "reasoningIncludedInOutput") val reasoningIncludedInOutput: Boolean? = null,
    @ColumnInfo(name = "billingMode") val billingMode: String,
    @ColumnInfo(name = "pricingCurrency") val pricingCurrency: String,
    @ColumnInfo(name = "inputPricePerMillion") val inputPricePerMillion: Double? = null,
    @ColumnInfo(name = "cachedInputPricePerMillion") val cachedInputPricePerMillion: Double? = null,
    @ColumnInfo(name = "cacheWritePricePerMillion") val cacheWritePricePerMillion: Double? = null,
    @ColumnInfo(name = "outputPricePerMillion") val outputPricePerMillion: Double? = null,
    @ColumnInfo(name = "pricePerRequest") val pricePerRequest: Double? = null,
    @ColumnInfo(name = "pricingSource") val pricingSource: String,
    @ColumnInfo(name = "costInPricingCurrency") val costInPricingCurrency: Double? = null,
)

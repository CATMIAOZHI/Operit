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
 *   token 列使用 [Long]：provider 原值可能超过 Int 范围，聚合与费用计算全程
 *   Long 运算，绝不因 Int 溢出产生负数落账（与 baseline 表一致）。
 * - 事件保存发生时的原币价格快照与原币成本（[pricingCurrency]/[costInPricingCurrency]），
 *   不冻结汇率；跨币种展示换算始终使用当前手动汇率。
 * - [reasoningIncludedInOutput] 是 provider 适配层规范化后的边界声明：
 *   true = provider 的 output 计数已包含推理 token（计费时不得再加推理）；
 *   false = 推理 token 独立计数，需按输出单价补算；
 *   null = provider 未声明，计费时按“已包含”处理以避免重复收费。
 * - [totalInputTokens]：provider 明确上报的总输入（含缓存命中/写入），在
 *   cached/uncached 拆分未知时仍可表达输入量；费用计算与当前价格重估直接读取，
 *   无需解析 [diagnosticsJson]。
 * - [cacheWriteSeparateBilling]：provider 的缓存写入计费模型（结构化保存，供
 *   当前价格重估直接读取；null = 旧行未声明，重估时按 provider/来源推断）。
 * - [diagnosticsJson] 保存必要且脱敏的原始诊断字段（usage 来源标签、是否观察到
 *   usage、usage 上报次数等），**不**保存正文、API key、Cookie 或 endpoint 凭据。
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
    /** 请求开始时捕获的持久单调 generation；reset 不依赖墙钟判断先后。 */
    @ColumnInfo(name = "acceptedGeneration") val acceptedGeneration: Long = 0L,
    @ColumnInfo(name = "startedAtMs") val startedAtMs: Long,
    @ColumnInfo(name = "endedAtMs") val endedAtMs: Long,
    @ColumnInfo(name = "firstTokenAtMs") val firstTokenAtMs: Long? = null,
    @ColumnInfo(name = "uncachedInputTokens") val uncachedInputTokens: Long? = null,
    @ColumnInfo(name = "cachedInputTokens") val cachedInputTokens: Long? = null,
    @ColumnInfo(name = "cacheWriteTokens") val cacheWriteTokens: Long? = null,
    @ColumnInfo(name = "totalInputTokens") val totalInputTokens: Long? = null,
    @ColumnInfo(name = "outputTokens") val outputTokens: Long? = null,
    @ColumnInfo(name = "reasoningTokens") val reasoningTokens: Long? = null,
    @ColumnInfo(name = "reasoningIncludedInOutput") val reasoningIncludedInOutput: Boolean? = null,
    @ColumnInfo(name = "cacheWriteSeparateBilling") val cacheWriteSeparateBilling: Boolean? = null,
    @ColumnInfo(name = "billingMode") val billingMode: String,
    @ColumnInfo(name = "pricingCurrency") val pricingCurrency: String,
    @ColumnInfo(name = "inputPricePerMillion") val inputPricePerMillion: Double? = null,
    @ColumnInfo(name = "cachedInputPricePerMillion") val cachedInputPricePerMillion: Double? = null,
    @ColumnInfo(name = "cacheWritePricePerMillion") val cacheWritePricePerMillion: Double? = null,
    @ColumnInfo(name = "outputPricePerMillion") val outputPricePerMillion: Double? = null,
    @ColumnInfo(name = "pricePerRequest") val pricePerRequest: Double? = null,
    @ColumnInfo(name = "pricingSource") val pricingSource: String,
    @ColumnInfo(name = "costInPricingCurrency") val costInPricingCurrency: Double? = null,
    @ColumnInfo(name = "diagnosticsJson") val diagnosticsJson: String? = null,
)

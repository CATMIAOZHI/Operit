package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import org.json.JSONObject

/**
 * 一次统计请求/尝试的上下文（阶段 2 记录链路核心）。
 *
 * - 一个上下文对应一次**逻辑请求**（一次 [com.ai.assistance.operit.api.chat.llmprovider.AIService.sendMessage]）。
 * - [eventId] 在请求开始时生成一次，全生命周期稳定：同一请求的重试、重复回调、
 *   重复落账都复用同一标识，配合 DAO 的 IGNORE 插入实现幂等（不会重复入账）。
 * - 重试边界：provider 内部重试**不**产生独立事件；无论内部尝试多少次，
 *   最终只落一个事件，状态为最终结果（成功 / 取消 / 超时 / 失败）。
 * - [onUsage] 可被 provider 多次回调，每次携带 attempt（provider 内部第几次尝试，
 *   从 1 开始）。同一 attempt 的多次上报按 [ProviderUsageSnapshot.completeSnapshot]
 *   合并：部分更新（false，流式增量如 Anthropic message_start/message_delta）按
 *   “最新非空字段优先”合并，output 等累计字段直接取最新值、绝不相加；完整快照
 *   （true，如最终响应 usage）整份覆盖，null 字段 = 明确未知，覆盖旧值（撤销）。
 *   **不同 attempt 的用量按分量累加**，避免重试后漏掉已计费 attempt 的 token/费用。
 * - [aggregatedUsage] 返回按 attempt 聚合后的快照：分量只在所有 attempt 都已知时
 *   求和（Long 饱和加法，绝不 Int 溢出为负），任一 attempt 未知则该分量保持未知。
 * - 负数分量在任何入口都被拒绝为未知（防御：适配层已拒绝，这里做最终防线）。
 * - [onFirstToken] 只在真实首个响应 token/chunk（首个非空内容）到达时设置一次。
 *
 * 不保存正文、API key、Cookie 或 endpoint 凭据。
 */
class TokenStatRequestContext(
    val eventId: String,
    val category: TokenStatCategory,
    val configId: String,
    val provider: String,
    val model: String,
    val startedAtMs: Long,
    /** 请求开始时从 Room 捕获；reset 后完成的旧请求仍属于 reset 前统计。 */
    val acceptedGeneration: Long = 0L,
    /**
     * 请求开始时同步捕获的 [TokenStatSpool.captureRestoreEpoch]（P1 终审）：纯内存、无
     * Room。收尾 [TokenStatSpool.append] 时验证其仍等于当前 restore epoch——恢复屏障开始
     * 即原子递增使所有旧请求失效，绝不写入可能已被恢复替换的 spool/Room。spool 重放
     * （[fromSpoolLine]）不经过 append 的请求 fence，保持默认值即可。
     */
    val sessionEpoch: Long = 0L,
) {
    /** 首个真实响应 token/chunk 时间；无内容响应保持 null。 */
    var firstTokenAtMs: Long? = null
        private set

    /** 最后一次 provider 上报的规范化 usage；未上报保持 null（未知）。 */
    var lastUsage: ProviderUsageSnapshot? = null
        private set

    /** provider 上报 usage 的总次数（诊断：重复回调防重证据）。 */
    var usageReportCount: Int = 0
        private set

    /** 观察到的最大的 attempt 序号（诊断：内部重试边界证据）。 */
    var attemptCount: Int = 0
        private set

    /** 最终状态；未结束时为 null。 */
    var status: TokenStatStatus? = null
        private set

    /** 结束时间；未结束时为 [startedAtMs]。 */
    var endedAtMs: Long = startedAtMs
        private set

    /**
     * 冻结的“发生时”价格快照（P1-1）：由 [TokenStatsLedger.prepareEventLine] 在
     * 请求收尾解析并随 spool 行持久化；排空（[TokenStatsLedger.recordWith]）只使用
     * 该快照，绝不重读当前价格。直接路径（[TokenStatsLedger.record]）不经 spool，
     * 解析即落账，无需冻结。
     */
    internal var frozenPricing: FrozenEventPricing? = null

    /** 价格无法读取时的结构化、脱敏原因；不得用默认价格冒充历史快照。 */
    internal var pricingResolutionDiagnostic: String? = null

    /** attempt -> 该 attempt 最后一次上报的快照（同一 attempt 重复上报取最后）。 */
    private val attemptUsages = LinkedHashMap<Int, ProviderUsageSnapshot>()

    /** 仅在首个真实内容 chunk 到达时记录一次。 */
    fun onFirstToken(nowMs: Long = System.currentTimeMillis()) {
        if (firstTokenAtMs == null) {
            firstTokenAtMs = nowMs
        }
    }

    /**
     * provider 上报 usage。同一 attempt 的重复上报按
     * [ProviderUsageSnapshot.completeSnapshot] 合并：部分更新按“最新非空字段优先”
     * （流式增量快照，如 Anthropic message_start + message_delta：后一次只带
     * 累计 output，input/cache 保留前一次的值；output 等累计字段取最新值，绝不
     * 相加）；完整快照整份覆盖（null = 明确未知，撤销旧值）。不同 attempt 分别
     * 记账，聚合时累加。
     */
    fun onUsage(usage: ProviderUsageSnapshot, attempt: Int = 1) {
        val normalizedAttempt = attempt.coerceAtLeast(1)
        val sanitized = sanitizeUsage(usage)
        usageReportCount += 1
        lastUsage = sanitized
        attemptUsages[normalizedAttempt] =
            mergeSameAttemptSnapshot(attemptUsages[normalizedAttempt], sanitized)
        if (normalizedAttempt > attemptCount) {
            attemptCount = normalizedAttempt
        }
    }

    /** 防御：负值分量一律拒绝为未知（真实负值只会来自异常 provider 数据）。 */
    private fun sanitizeUsage(usage: ProviderUsageSnapshot): ProviderUsageSnapshot {
        fun nonNegative(value: Long?): Long? = value?.takeIf { it >= 0 }
        return ProviderUsageSnapshot(
            uncachedInputTokens = nonNegative(usage.uncachedInputTokens),
            cachedInputTokens = nonNegative(usage.cachedInputTokens),
            cacheWriteTokens = nonNegative(usage.cacheWriteTokens),
            totalInputTokens = nonNegative(usage.totalInputTokens),
            outputTokens = nonNegative(usage.outputTokens),
            reasoningTokens = nonNegative(usage.reasoningTokens),
            reasoningIncludedInOutput = usage.reasoningIncludedInOutput,
            cacheWriteSeparateBilling = usage.cacheWriteSeparateBilling,
            completeSnapshot = usage.completeSnapshot,
            source = usage.source,
        )
    }

    /**
     * 同一 attempt 的快照合并：
     * - 完整快照（[ProviderUsageSnapshot.completeSnapshot] = true）：整份覆盖，
     *   null 字段 = 明确未知（撤销旧值）；
     * - 部分更新（false）：最新上报的非空字段优先；新快照缺失的字段保留旧值。
     *   累计字段（output 等）直接取最新值，不能 start/delta 相加。
     */
    private fun mergeSameAttemptSnapshot(
        previous: ProviderUsageSnapshot?,
        latest: ProviderUsageSnapshot,
    ): ProviderUsageSnapshot {
        if (previous == null) return latest
        if (latest.completeSnapshot) return latest
        return ProviderUsageSnapshot(
            uncachedInputTokens = latest.uncachedInputTokens ?: previous.uncachedInputTokens,
            cachedInputTokens = latest.cachedInputTokens ?: previous.cachedInputTokens,
            cacheWriteTokens = latest.cacheWriteTokens ?: previous.cacheWriteTokens,
            totalInputTokens = latest.totalInputTokens ?: previous.totalInputTokens,
            outputTokens = latest.outputTokens ?: previous.outputTokens,
            reasoningTokens = latest.reasoningTokens ?: previous.reasoningTokens,
            reasoningIncludedInOutput =
                latest.reasoningIncludedInOutput ?: previous.reasoningIncludedInOutput,
            cacheWriteSeparateBilling = latest.cacheWriteSeparateBilling,
            completeSnapshot = false,
            source = latest.source,
        )
    }

    /**
     * 按 attempt 聚合后的 usage：分量在所有上报 attempt 中都已知时才求和
     * （Long 饱和加法，绝不溢出为负），任一 attempt 该分量未知则聚合值保持未知；
     * 来源/包含推理声明取最后一次。
     */
    fun aggregatedUsage(): ProviderUsageSnapshot? {
        val snapshots = attemptUsages.values.toList()
        if (snapshots.isEmpty()) return null
        return ProviderUsageSnapshot(
            uncachedInputTokens = sumComponent(snapshots) { it.uncachedInputTokens },
            cachedInputTokens = sumComponent(snapshots) { it.cachedInputTokens },
            cacheWriteTokens = sumComponent(snapshots) { it.cacheWriteTokens },
            totalInputTokens = sumComponent(snapshots) { it.totalInputTokens },
            outputTokens = sumComponent(snapshots) { it.outputTokens },
            reasoningTokens = sumComponent(snapshots) { it.reasoningTokens },
            reasoningIncludedInOutput = snapshots.lastOrNull()?.reasoningIncludedInOutput,
            cacheWriteSeparateBilling = snapshots.lastOrNull()?.cacheWriteSeparateBilling ?: true,
            completeSnapshot = true,
            source = snapshots.lastOrNull()?.source ?: "unknown",
        )
    }

    private fun sumComponent(
        snapshots: List<ProviderUsageSnapshot>,
        pick: (ProviderUsageSnapshot) -> Long?,
    ): Long? {
        val values = snapshots.mapNotNull(pick)
        if (values.size != snapshots.size) return null
        return values.fold(0L) { acc, value -> TokenCostCalculator.saturatedAdd(acc, value) }
    }

    /** 结束请求：只能设置一次，后续调用被忽略。 */
    fun finish(status: TokenStatStatus, nowMs: Long = System.currentTimeMillis()) {
        if (this.status == null) {
            this.status = status
            endedAtMs = nowMs
        }
    }

    // ==== 磁盘 spool 序列化（TokenStatSpool 写入前日志重放） ====
    // 只保存完整脱敏事件（无正文/凭据）；聚合已完成，重放结果与原始请求一致。
    // v2 起行内携带“发生时”价格/成本快照（P1-1），排空只按快照落账。

    /** 不可变基础 JSON（usage/状态/时间）：调用方在请求收尾同步生成，冻结快照。 */
    internal fun toSpoolBaseJson(): JSONObject =
        JSONObject().apply {
            put("v", SPOOL_FORMAT_VERSION)
            put("eventId", eventId)
            put("category", category.name)
            put("configId", configId)
            put("provider", provider)
            put("model", model)
            put("startedAtMs", startedAtMs)
            put("acceptedGeneration", acceptedGeneration)
            put("endedAtMs", endedAtMs)
            firstTokenAtMs?.let { put("firstTokenAtMs", it) }
            status?.let { put("status", it.name) }
            put("usageReportCount", usageReportCount)
            put("attemptCount", attemptCount)
            aggregatedUsage()?.let { usage ->
                put("usage", usageToJson(usage))
            }
        }

    /** 完整 v2 行：基础 JSON + 发生时价格/成本快照。 */
    internal fun toSpoolLine(pricing: ResolvedPricing, cost: Double?): String =
        toSpoolBaseJson().apply {
            put("pricing", pricingToJson(pricing, cost))
        }.toString()

    internal companion object {
        private const val SPOOL_FORMAT_VERSION = 2

        /**
         * 从 spool 行恢复上下文（重放时直接使用聚合结果与冻结价格快照，语义与
         * 原始请求一致）。v2 严格解析：缺价格快照（v1 未发布格式）视为损坏行，
         * 由 spool 整段隔离（保留证据），绝不静默用当前价格重放。
         */
        internal fun fromSpoolLine(line: String): TokenStatRequestContext {
            val json = JSONObject(line)
            if (json.optInt("v", 0) != SPOOL_FORMAT_VERSION) {
                throw IllegalStateException("unsupported spool format version")
            }
            val context =
                TokenStatRequestContext(
                    eventId = json.getString("eventId"),
                    category = TokenStatCategory.fromName(json.optString("category")),
                    configId = json.getString("configId"),
                    provider = json.getString("provider"),
                    model = json.getString("model"),
                    startedAtMs = json.getLong("startedAtMs"),
                    acceptedGeneration = json.getLong("acceptedGeneration"),
                )
            json.opt("firstTokenAtMs")?.let { firstTokenAtMs ->
                context.firstTokenAtMs = (firstTokenAtMs as Number).toLong()
            }
            context.endedAtMs = json.getLong("endedAtMs")
            context.status = TokenStatStatus.fromName(json.optString("status"))
            context.usageReportCount = json.optInt("usageReportCount", 0)
            context.attemptCount = json.optInt("attemptCount", 0)
            json.optJSONObject("usage")?.let { usageJson ->
                val usage = usageFromJson(usageJson)
                context.lastUsage = usage
                context.attemptUsages[1] = usage
            }
            val pricingJson =
                json.optJSONObject("pricing")
                    ?: throw IllegalStateException("spool line missing pricing snapshot")
            context.frozenPricing = pricingFromJson(pricingJson)
            context.pricingResolutionDiagnostic =
                pricingJson.optString("resolutionDiagnostic").takeIf { it.isNotBlank() }
            return context
        }

        internal fun pricingToJson(
            pricing: ResolvedPricing,
            cost: Double?,
            resolutionDiagnostic: String? = null,
        ): JSONObject =
            JSONObject().apply {
                put("billingMode", pricing.billingMode.name)
                put("currency", pricing.currency.name)
                pricing.inputPricePerMillion?.let { put("inputPricePerMillion", it) }
                pricing.cachedInputPricePerMillion?.let { put("cachedInputPricePerMillion", it) }
                pricing.cacheWritePricePerMillion?.let { put("cacheWritePricePerMillion", it) }
                pricing.outputPricePerMillion?.let { put("outputPricePerMillion", it) }
                pricing.pricePerRequest?.let { put("pricePerRequest", it) }
                put("source", pricing.source.name)
                put("known", pricing.known)
                cost?.let { put("cost", it) }
                resolutionDiagnostic?.let { put("resolutionDiagnostic", it) }
            }

        private fun pricingFromJson(json: JSONObject): FrozenEventPricing {
            val pricing =
                ResolvedPricing(
                    billingMode = BillingMode.fromString(json.getString("billingMode")),
                    currency =
                        if (json.optString("currency").equals("CNY", ignoreCase = true)) {
                            PricingCurrency.CNY
                        } else {
                            PricingCurrency.USD
                        },
                    inputPricePerMillion = json.optDoubleOrNull("inputPricePerMillion"),
                    cachedInputPricePerMillion = json.optDoubleOrNull("cachedInputPricePerMillion"),
                    cacheWritePricePerMillion = json.optDoubleOrNull("cacheWritePricePerMillion"),
                    outputPricePerMillion = json.optDoubleOrNull("outputPricePerMillion"),
                    pricePerRequest = json.optDoubleOrNull("pricePerRequest"),
                    source = PricingSource.fromName(json.optString("source")),
                    known = json.optBoolean("known", false),
                )
            return FrozenEventPricing(pricing, json.optDoubleOrNull("cost"))
        }

        private fun usageToJson(usage: ProviderUsageSnapshot): JSONObject =
            JSONObject().apply {
                usage.uncachedInputTokens?.let { put("uncachedInputTokens", it) }
                usage.cachedInputTokens?.let { put("cachedInputTokens", it) }
                usage.cacheWriteTokens?.let { put("cacheWriteTokens", it) }
                usage.totalInputTokens?.let { put("totalInputTokens", it) }
                usage.outputTokens?.let { put("outputTokens", it) }
                usage.reasoningTokens?.let { put("reasoningTokens", it) }
                usage.reasoningIncludedInOutput?.let { put("reasoningIncludedInOutput", it) }
                put("cacheWriteSeparateBilling", usage.cacheWriteSeparateBilling)
                put("completeSnapshot", usage.completeSnapshot)
                put("source", usage.source)
            }

        private fun usageFromJson(json: JSONObject): ProviderUsageSnapshot =
            ProviderUsageSnapshot(
                uncachedInputTokens = json.optLongOrNull("uncachedInputTokens"),
                cachedInputTokens = json.optLongOrNull("cachedInputTokens"),
                cacheWriteTokens = json.optLongOrNull("cacheWriteTokens"),
                totalInputTokens = json.optLongOrNull("totalInputTokens"),
                outputTokens = json.optLongOrNull("outputTokens"),
                reasoningTokens = json.optLongOrNull("reasoningTokens"),
                reasoningIncludedInOutput =
                    if (json.has("reasoningIncludedInOutput")) {
                        json.optBoolean("reasoningIncludedInOutput")
                    } else {
                        null
                    },
                cacheWriteSeparateBilling = json.optBoolean("cacheWriteSeparateBilling", true),
                completeSnapshot = json.optBoolean("completeSnapshot", false),
                source = json.optString("source", "unknown"),
            )

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (has(key) && !isNull(key)) optLong(key, -1).takeIf { it >= 0 } else null

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (has(key) && !isNull(key)) {
                optDouble(key, Double.NaN).takeIf { !it.isNaN() }
            } else {
                null
            }
    }
}

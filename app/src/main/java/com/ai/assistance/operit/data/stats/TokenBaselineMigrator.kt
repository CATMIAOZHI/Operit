package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import java.security.MessageDigest

/** 一次导入的完整计划（纯决策，便于测试中断/恢复/幂等语义）。 */
data class BaselineImportPlan(
    val identities: List<TokenStatIdentityEntity>,
    val displayModels: List<TokenStatDisplayModelEntity>,
    val baselines: List<TokenStatBaselineEntity>,
    val skippedProviderModels: List<String>,
    /** 受控补导（forceReplace）时应删除的 baseline identityId：恢复后的权威
     *  快照中该旧系统模型已消失，且该身份确为旧系统迁移身份（configId 为空）。
     *  普通导入恒为空（快照缺失不代表用户数据应被删除）。 */
    val removedBaselineIdentityIds: List<String> = emptyList(),
)

/**
 * 旧 DataStore 累计统计到 baseline 的导入核心（纯逻辑，不依赖 Android）。
 *
 * 语义（无启发式）：
 * - 每个统计身份至多一行 baseline。
 * - **普通启动**：计数指纹变化时（旧系统累计 setter 增长，或用户 reset 后降低），
 *   用 baseline 行内**已冻结价格**重新估算并整体替换计数/成本——计数永远是
 *   快照的绝对值（不叠加增量），降低不会产生负增量；**计数不变**时普通价格
 *   setter 不触发任何重估（指纹只含计数）。冻结价格（frozen*）与币种永不被
 *   普通启动替换。
 * - **受控补导**（[forceReplace] = true，真实备份恢复完成后触发，
 *   见 TokenBaselineImportRunner.consumePendingRestore）：以恢复后的偏好快照
 *   重新解析定价并整体替换（含冻结价格），同时记录恢复 generation 保证幂等。
 * - **删除语义**：普通导入（forceReplace = false）绝不对快照中缺失的模型做任何
 *   删除——当前快照可能因偏好文件暂时缺失/部分恢复而不完整，删除会造成
 *   baseline 丢失；显式删除走 TokenStatsResetCoordinator（用户重置）。只有
 *   完整受控补导（forceReplace = true，恢复后的偏好快照是旧系统的权威全量）
 *   才把快照中缺失的模型列入 [BaselineImportPlan.removedBaselineIdentityIds]，
 *   且只针对旧系统迁移身份（configId 为空）；配置实例身份（configId 非空）
 *   的 baseline 不属于旧累计快照，绝不因恢复被删除。
 *
 * [TokenStatBaselineEntity.fingerprint] 只摘要**旧迁移源的累计计数**
 * （不含价格设置）。展示别名/分组：baseline 不保存 displayModelId（单一事实源
 * 是 identity），重导时通过 [preserveExistingGroups] 保留用户已设置的别名与分组。
 *
 * - 迁移中断：整个导入必须由调用方放在单个数据库事务中，中断则整体回滚，重跑即可。
 * - 数据库恢复：baseline 表回到旧状态 → 身份无 baseline → 重新导入。
 * - baseline 无时间分布，不进入事件表。
 */
object TokenBaselineMigrator {

    fun planImport(
        snapshot: LegacyTokenStatsSnapshot,
        existingBaselines: Map<String, TokenStatBaselineEntity>,
        nowMs: Long,
        forceReplace: Boolean = false,
        resolveIdentity: (providerModel: String) -> TokenStatIdentityEntity,
        resolveDisplayModel: (providerModel: String) -> TokenStatDisplayModelEntity,
        resolvePricing: (providerModel: String) -> ResolvedPricing,
        existingIdentities: Map<String, TokenStatIdentityEntity> = emptyMap(),
    ): BaselineImportPlan {
        val identities = mutableListOf<TokenStatIdentityEntity>()
        val displayModels = mutableListOf<TokenStatDisplayModelEntity>()
        val baselines = mutableListOf<TokenStatBaselineEntity>()
        val skipped = mutableListOf<String>()
        val presentIdentityIds = mutableSetOf<String>()

        snapshot.providerModels.keys.sorted().forEach { providerModel ->
            val stats = snapshot.providerModels.getValue(providerModel)
            val (_, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
            if (model.isBlank()) {
                skipped += providerModel
                return@forEach
            }

            val identity = resolveIdentity(providerModel)
            presentIdentityIds += identity.identityId
            val existing = existingBaselines[identity.identityId]
            val fingerprint = fingerprint(stats)

            if (existing != null && !forceReplace) {
                // 计数未变：普通价格 setter 不触发重估。
                if (existing.fingerprint == fingerprint) {
                    return@forEach
                }
                // 计数变化（增长或降低）：用行内冻结价格重估，整体替换为快照绝对值。
                val pricing = frozenPricingOf(existing)
                identities += identity
                baselines +=
                    existing.copy(
                        inputTokens = stats.inputTokens,
                        cachedInputTokens = stats.cachedInputTokens,
                        outputTokens = stats.outputTokens,
                        requestCount = stats.requestCount,
                        costInPricingCurrency = estimateCost(stats, pricing),
                        fingerprint = fingerprint,
                        importedAtMs = nowMs,
                    )
                return@forEach
            }

            val displayModel = resolveDisplayModel(providerModel)
            val pricing = resolvePricing(providerModel)
            val estimatedCost = estimateCost(stats, pricing)

            identities += identity
            displayModels += displayModel
            baselines +=
                TokenStatBaselineEntity(
                    identityId = identity.identityId,
                    inputTokens = stats.inputTokens,
                    cachedInputTokens = stats.cachedInputTokens,
                    outputTokens = stats.outputTokens,
                    requestCount = stats.requestCount,
                    pricingCurrency = pricing.currency.name,
                    costInPricingCurrency = estimatedCost,
                    isEstimated = true,
                    fingerprint = fingerprint,
                    importedAtMs = nowMs,
                    frozenBillingMode = pricing.billingMode.name,
                    frozenInputPricePerMillion = pricing.inputPricePerMillion,
                    frozenCachedInputPricePerMillion = pricing.cachedInputPricePerMillion,
                    frozenOutputPricePerMillion = pricing.outputPricePerMillion,
                    frozenPricePerRequest = pricing.pricePerRequest,
                )
        }

        // 快照中消失的模型 → 仅受控补导（forceReplace）删除，且只删除旧系统
        // 迁移身份（configId 为空）的 baseline：
        // - 普通导入绝不删除：当前快照可能因偏好文件缺失/部分恢复而暂缺模型，
        //   删除会造成用户数据丢失；显式删除由 TokenStatsResetCoordinator 提供。
        // - forceReplace 的恢复快照是旧累计统计的权威全量，缺失即旧系统无此
        //   模型；但配置实例身份（configId 非空）的 baseline 不属于旧累计快照
        //   的范围，恢复不得误删。
        // - 身份信息未知时保守保留（外键保证 baseline 必有身份，完整身份表由
        //   调用方传入；缺失该行是数据异常，不应据此删除）。
        val removed =
            if (forceReplace) {
                existingBaselines.keys
                    .filter { it !in presentIdentityIds }
                    .filter { existingIdentities[it]?.configId == "" }
                    .sorted()
            } else {
                emptyList()
            }

        return BaselineImportPlan(
            identities = identities,
            displayModels = displayModels,
            baselines = baselines,
            skippedProviderModels = skipped,
            removedBaselineIdentityIds = removed,
        )
    }

    /** 从已持久化的冻结价格快照重建定价（普通启动计数变化时重估用）。 */
    fun frozenPricingOf(baseline: TokenStatBaselineEntity): ResolvedPricing {
        val billingMode = BillingMode.fromString(baseline.frozenBillingMode)
        val known =
            if (billingMode == BillingMode.COUNT) {
                baseline.frozenPricePerRequest != null
            } else {
                baseline.frozenInputPricePerMillion != null ||
                    baseline.frozenOutputPricePerMillion != null
            }
        return ResolvedPricing(
            billingMode = billingMode,
            currency = parseCurrency(baseline.pricingCurrency),
            inputPricePerMillion = baseline.frozenInputPricePerMillion,
            cachedInputPricePerMillion = baseline.frozenCachedInputPricePerMillion,
            outputPricePerMillion = baseline.frozenOutputPricePerMillion,
            pricePerRequest = baseline.frozenPricePerRequest,
            source = PricingSource.LEGACY_OVERRIDE,
            known = known,
        )
    }

    /**
     * 重导时保留用户已设置的展示分组/别名（单一事实源）：
     * - identity 已存在 → 保留其 displayModelId（不因重导重置为默认分组）；
     * - 展示模型已存在 → 不重写（保留用户别名 [TokenStatDisplayModelEntity.displayName]）。
     */
    fun preserveExistingGroups(
        plan: BaselineImportPlan,
        existingIdentities: Map<String, TokenStatIdentityEntity>,
        existingDisplayModels: Map<String, TokenStatDisplayModelEntity>,
    ): BaselineImportPlan {
        val identities =
            plan.identities.map { identity ->
                val existing = existingIdentities[identity.identityId]
                if (existing != null) {
                    if (existing.displayModelId == identity.displayModelId) identity else
                        identity.copy(displayModelId = existing.displayModelId)
                } else {
                    identity
                }
            }
        val displayModels = plan.displayModels.filter { it.displayModelId !in existingDisplayModels }
        return plan.copy(identities = identities, displayModels = displayModels)
    }

    /**
     * baseline 估算费用：按导入时可用的**旧配置链**估算（旧 DataStore 价格
     * → 内置默认价，不读取新系统当前价格覆盖），并始终标记为估算。
     * TOKEN 模式下旧 inputTokens 为总输入（含缓存），按 legacy 公式
     * （非缓存输入 × 输入价 + 缓存 × 缓存价 + 输出 × 输出价）估算；
     * 旧系统不跟踪缓存写入，估算按 legacy 计费语义视为 0（文档化，非猜测）；
     * 定价未知时成本为 null（未知），不得静默为 0。
     * 全部使用 Long/Double 计算，避免累计值超过 Int.MAX_VALUE 溢出。
     */
    private fun estimateCost(
        stats: LegacyProviderModelStats,
        pricing: ResolvedPricing,
    ): Double? {
        if (!pricing.known) return null
        return when (pricing.billingMode) {
            BillingMode.COUNT -> {
                pricing.pricePerRequest?.times(stats.requestCount.toDouble())
            }
            BillingMode.TOKEN -> {
                val uncachedInput = (stats.inputTokens - stats.cachedInputTokens).coerceAtLeast(0L)
                val cachedInput = stats.cachedInputTokens
                val output = stats.outputTokens
                val inputPrice = pricing.inputPricePerMillion ?: return null
                val cachedPrice = pricing.cachedInputPricePerMillion ?: return null
                val outputPrice = pricing.outputPricePerMillion ?: return null
                uncachedInput / 1_000_000.0 * inputPrice +
                    cachedInput / 1_000_000.0 * cachedPrice +
                    output / 1_000_000.0 * outputPrice
            }
        }
    }

    /**
     * 幂等指纹：只摘要**旧迁移源的累计计数**。
     * 旧 DataStore 价格设置、新系统当前价格覆盖、展示别名/分组的任何变化
     * 都不改变指纹（价格变化不触发重导，见类注释的冻结语义）。
     */
    fun fingerprint(stats: LegacyProviderModelStats): String {
        val canonical =
            buildString {
                append(stats.inputTokens).append('|')
                append(stats.cachedInputTokens).append('|')
                append(stats.outputTokens).append('|')
                append(stats.requestCount)
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun parseCurrency(raw: String): PricingCurrency =
        if (raw.equals("CNY", ignoreCase = true)) PricingCurrency.CNY else PricingCurrency.USD

    /** 展示模型默认分组：以规范化模型名为默认别名。 */
    fun defaultDisplayModel(providerModel: String): TokenStatDisplayModelEntity {
        val (_, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
        val normalized = TokenStatIdentityResolver.normalizeModelName(model)
        return TokenStatDisplayModelEntity(
            displayModelId = normalized,
            normalizedModel = normalized,
            displayName = normalized,
        )
    }
}

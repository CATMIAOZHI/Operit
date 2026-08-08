package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.ModelPricingDefaults
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBaselineMigratorTest {

    private val knownDefaults =
        ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            cachedInputPricePerMillion = 0.5,
            pricePerRequest = 0.01,
            currency = PricingCurrency.USD,
        )

    private val zeroDefaults =
        ModelPricingDefaults(
            billingMode = BillingMode.TOKEN,
            inputPricePerMillion = 0.0,
            outputPricePerMillion = 0.0,
            cachedInputPricePerMillion = 0.0,
            pricePerRequest = 0.01,
            currency = PricingCurrency.CNY,
        )

    private fun snapshotOf(
        providerModel: String,
        input: Long = 0L,
        cached: Long = 0L,
        output: Long = 0L,
        requests: Long = 0L,
        priceSettings: LegacyPriceSettings = LegacyPriceSettings(),
    ) = LegacyTokenStatsSnapshot(
        providerModels =
            mapOf(
                providerModel to
                    LegacyProviderModelStats(
                        providerModel = providerModel,
                        inputTokens = input,
                        cachedInputTokens = cached,
                        outputTokens = output,
                        requestCount = requests,
                        priceSettings = priceSettings,
                    )
            )
    )

    private fun plan(
        snapshot: LegacyTokenStatsSnapshot,
        existingBaselines: Map<String, TokenStatBaselineEntity> = emptyMap(),
        defaults: ModelPricingDefaults = knownDefaults,
        nowMs: Long = 1_000L,
        forceReplace: Boolean = false,
        legacyOverrideOverrides: List<com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity> = emptyList(),
        existingIdentities: Map<String, TokenStatIdentityEntity> = emptyMap(),
    ) =
        TokenBaselineMigrator.planImport(
            snapshot = snapshot,
            existingBaselines = existingBaselines,
            nowMs = nowMs,
            forceReplace = forceReplace,
            resolveIdentity = { providerModel ->
                val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
                TokenStatIdentityEntity(
                    identityId = TokenStatIdentityResolver.identityId("", provider, model),
                    configId = "",
                    provider = provider,
                    model = model,
                    displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
                )
            },
            resolveDisplayModel = { TokenBaselineMigrator.defaultDisplayModel(it) },
                resolvePricing = { providerModel ->
                    val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
                    TokenPriceResolver.resolve(
                        provider = provider,
                        model = model,
                        configId = null,
                        overrides = legacyOverrideOverrides,
                        legacyOverride = snapshot.providerModels[providerModel]?.priceSettings,
                        defaults = defaults,
                    )
                },
                existingIdentities = existingIdentities,
            )

    /**
     * 与导入执行器一致：已有 baseline 都对应旧系统迁移身份（configId 空）。
     * 快照中仍存在的身份用快照解析结果，快照外的 baseline 身份用合成旧系统身份
     * （删除过滤只依赖 configId 是否为空，provider/model 内容不影响语义）。
     */
    private fun legacyIdentitiesFor(
        baselines: Map<String, TokenStatBaselineEntity>,
        snapshot: LegacyTokenStatsSnapshot = snapshotOf("DEEPSEEK:deepseek-chat", input = 1_000_000, output = 500_000, requests = 3),
    ): Map<String, TokenStatIdentityEntity> {
        val result =
            snapshot.providerModels.keys.map { providerModel ->
                val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
                TokenStatIdentityEntity(
                    identityId = TokenStatIdentityResolver.identityId("", provider, model),
                    configId = "",
                    provider = provider,
                    model = model,
                    displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
                )
            }.associateBy { it.identityId }.toMutableMap()
        baselines.keys.forEach { id ->
            result.putIfAbsent(
                id,
                TokenStatIdentityEntity(
                    identityId = id,
                    configId = "",
                    provider = "",
                    model = "",
                    displayModelId = id,
                )
            )
        }
        return result
    }

    @Test
    fun `first import creates estimated baseline with fingerprint`() {
        val p = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3))

        assertEquals(1, p.baselines.size)
        val baseline = p.baselines.single()
        assertTrue(baseline.isEstimated)
        assertTrue(baseline.fingerprint.isNotBlank())
        assertEquals(1, p.identities.size)
        assertEquals(1, p.displayModels.size)
    }

    @Test
    fun `baseline cost is estimated from legacy pricing chain in native currency`() {
        val p =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    cached = 200_000,
                    output = 500_000,
                    requests = 3,
                )
            )

        val baseline = p.baselines.single()
        // 800k*1 + 200k*0.5 + 500k*2 = 0.8 + 0.1 + 1.0 (每百万计价)
        assertEquals(1.9, baseline.costInPricingCurrency!!, 1e-9)
        assertEquals(PricingCurrency.USD.name, baseline.pricingCurrency)
    }

    @Test
    fun `count mode baseline cost is per request price times request count`() {
        val legacy =
            LegacyPriceSettings(
                billingMode = BillingMode.COUNT,
                pricePerRequest = 0.02,
            )
        val p = plan(snapshotOf("OPENAI:gpt-4o", requests = 5, priceSettings = legacy))

        val baseline = p.baselines.single()
        assertEquals(0.1, baseline.costInPricingCurrency!!, 1e-9)
        assertEquals(5L, baseline.requestCount)
    }

    @Test
    fun `unknown pricing yields null estimated cost not zero`() {
        val p = plan(snapshotOf("MYSTERY:model-x", input = 1000), defaults = zeroDefaults)

        val baseline = p.baselines.single()
        assertNull(baseline.costInPricingCurrency)
        assertTrue(baseline.isEstimated)
    }

    @Test
    fun `same snapshot imported twice skips second time`() {
        val first = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3))
        val existing = first.baselines.associateBy { it.identityId }

        val second = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3), existing)

        assertEquals(0, second.baselines.size)
        assertEquals(0, second.identities.size)
    }

    @Test
    fun `growing legacy counters re-estimate with frozen pricing`() {
        val first = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3))
        val existing = first.baselines.associateBy { it.identityId }

        // 普通导入：计数增长 → 用冻结价重估（此处默认价 1.0/0.5/2.0）
        val second =
            plan(
                snapshotOf("DEEPSEEK:deepseek-chat", input = 1500, output = 700, requests = 4),
                existing,
            )
        assertEquals(1, second.baselines.size)
        val regrown = second.baselines.single()
        assertEquals(1500L, regrown.inputTokens)
        assertEquals(4L, regrown.requestCount)
        assertFalse(regrown.fingerprint == existing.getValue(regrown.identityId).fingerprint)

        // 受控补导（forceReplace）：以当前快照重新解析定价并整体替换
        val forced =
            plan(
                snapshotOf("DEEPSEEK:deepseek-chat", input = 1500, output = 700, requests = 4),
                existing,
                forceReplace = true,
            )
        assertEquals(1, forced.baselines.size)
    }

    @Test
    fun `database restore triggers full reimport`() {
        // 第一次导入成功，随后数据库被恢复到导入前的状态（baseline 表为空）
        val first = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3))

        val afterRestore = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3))

        assertEquals(1, first.baselines.size)
        assertEquals(1, afterRestore.baselines.size)
        assertEquals(first.baselines.single().fingerprint, afterRestore.baselines.single().fingerprint)
        assertEquals(1000L, afterRestore.baselines.single().inputTokens)
    }

    @Test
    fun `interrupted import reruns and converges to single baseline`() {
        // 中断 = 什么都没写入（existing 为空），重跑与首次结果一致且只产生一行
        val interruptedRun = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 800, output = 300, requests = 2))
        val retry =
            plan(
                snapshotOf("DEEPSEEK:deepseek-chat", input = 800, output = 300, requests = 2),
                emptyMap(),
            )

        assertEquals(interruptedRun.baselines.single().fingerprint, retry.baselines.single().fingerprint)
        assertEquals(1, retry.baselines.size)
    }

    @Test
    fun `preferences changed later without lifecycle signal still track counts with frozen pricing`() {
        val first = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3))
        val existing = first.baselines.associateBy { it.identityId }

        // 快照计数变化（偏好文件被覆盖）但没有恢复生命周期信号：
        // 普通导入用冻结价跟踪计数；只有受控补导（forceReplace）才重解析价格
        val restored =
            plan(
                snapshotOf("DEEPSEEK:deepseek-chat", input = 600, output = 200, requests = 1),
                existing,
            )

        assertEquals(1, restored.baselines.size)
        val baseline = restored.baselines.single()
        assertEquals(600L, baseline.inputTokens)
        assertEquals(1L, baseline.requestCount)
    }

    @Test
    fun `count growth re-estimates with frozen pricing while keeping frozen prices`() {
        val first =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                )
            )
        val existing = first.baselines.associateBy { it.identityId }
        val before = first.baselines.single()
        assertEquals(2.0, before.costInPricingCurrency!!, 1e-9)

        // 普通启动：累计 setter 增长计数（同时快照价格被普通 setter 改为 99）——
        // 计数变化触发重估，但必须用行内冻结价 (1.0/2.0)，冻结价格列不变。
        val regrown =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 2_000_000,
                    output = 1_000_000,
                    requests = 6,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 99.0,
                            outputPricePerMillion = 99.0,
                        ),
                ),
                existing,
            )

        assertEquals(1, regrown.baselines.size)
        val baseline = regrown.baselines.single()
        // 按冻结价 (1.0/2.0) 重估：2M*1.0 + 1M*2.0 = 2.0 + 2.0
        assertEquals(4.0, baseline.costInPricingCurrency!!, 1e-9)
        assertEquals(2_000_000L, baseline.inputTokens)
        assertEquals(1_000_000L, baseline.outputTokens)
        assertEquals(6L, baseline.requestCount)
        // 冻结价格列不被普通启动替换（仍是 1.0/2.0，而非 99）
        assertEquals(1.0, baseline.frozenInputPricePerMillion!!, 1e-9)
        assertEquals(2.0, baseline.frozenOutputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `count drop after user reset replaces baseline with absolute value`() {
        val first =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                )
            )
        val existing = first.baselines.associateBy { it.identityId }

        // 用户 reset 旧统计：计数变小（绝对值替换，不产生负增量/不拒绝）
        val dropped =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 100_000,
                    output = 50_000,
                    requests = 1,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                ),
                existing,
            )

        assertEquals(1, dropped.baselines.size)
        val baseline = dropped.baselines.single()
        assertEquals(100_000L, baseline.inputTokens)
        assertEquals(50_000L, baseline.outputTokens)
        assertEquals(1L, baseline.requestCount)
        // 冻结价重估：100k*1.0 + 50k*2.0 = 0.1 + 0.1
        assertEquals(0.2, baseline.costInPricingCurrency!!, 1e-9)
        assertEquals(1.0, baseline.frozenInputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `normal import never removes baseline for model missing from snapshot`() {
        val first =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                )
            )
        val existing = first.baselines.associateBy { it.identityId }
        val identities = legacyIdentitiesFor(existing)

        // 普通启动：当前快照不再包含该模型（偏好文件暂时缺失/部分恢复/被清空）——
        // 必须保留其 baseline，绝不因快照缺失删除（显式删除走用户重置路径）。
        val normal = plan(emptySnapshot(), existing, existingIdentities = identities)
        // 快照中 OTHER:model-x 正常导入；DEEPSEEK 的 baseline 不得被更新或删除
        assertEquals(1, normal.baselines.size)
        assertTrue(normal.baselines.none { it.identityId == existing.keys.single() })
        assertEquals("normal import must not delete missing baselines", emptyList<String>(), normal.removedBaselineIdentityIds)
    }

    @Test
    fun `controlled restore removes legacy baseline missing from restored snapshot but not config baselines`() {
        val legacy =
            snapshotOf(
                "DEEPSEEK:deepseek-chat",
                input = 1_000_000,
                output = 500_000,
                requests = 3,
                priceSettings =
                    LegacyPriceSettings(
                        inputPricePerMillion = 1.0,
                        outputPricePerMillion = 2.0,
                    ),
            )
        val first = plan(legacy)
        val legacyBaseline = first.baselines.single()

        // 配置实例身份（configId 非空）的 baseline：不属于旧累计快照范围，
        // 受控补导也必须保留
        val configIdentity =
            TokenStatIdentityEntity(
                identityId = "config-identity",
                configId = "cfg-1",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                displayModelId = "deepseek-chat",
            )
        val configBaseline = legacyBaseline.copy(identityId = configIdentity.identityId)
        val existing = mapOf(legacyBaseline.identityId to legacyBaseline, configBaseline.identityId to configBaseline)
        val identities = legacyIdentitiesFor(existing, legacy) + mapOf(configIdentity.identityId to configIdentity)

        // 恢复快照只含另一个模型（DEEPSEEK:deepseek-chat 消失）→ forceReplace：
        // 只删除旧系统身份（configId 空）的 baseline，配置身份 baseline 保留
        val restoredSnapshot =
            LegacyTokenStatsSnapshot(
                providerModels = mapOf(
                    "OTHER:model-x" to
                        LegacyProviderModelStats(
                            providerModel = "OTHER:model-x",
                            inputTokens = 10L,
                            cachedInputTokens = 0L,
                            outputTokens = 0L,
                            requestCount = 0L,
                            priceSettings = LegacyPriceSettings(),
                        )
                )
            )
        val forced = plan(restoredSnapshot, existing, forceReplace = true, existingIdentities = identities)

        assertEquals(listOf(legacyBaseline.identityId), forced.removedBaselineIdentityIds)
    }

    @Test
    fun `model disappearing from snapshot is marked for removal only on controlled restore`() {
        val first =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                )
            )
        val existing = first.baselines.associateBy { it.identityId }
        val identities = legacyIdentitiesFor(existing)

        // 恢复快照不再包含该模型（旧统计在备份中已清空）→ 受控补导删除其 baseline
        val empty =
            LegacyTokenStatsSnapshot(
                providerModels = mapOf(
                    "OTHER:model-x" to
                        LegacyProviderModelStats(
                            providerModel = "OTHER:model-x",
                            inputTokens = 10L,
                            cachedInputTokens = 0L,
                            outputTokens = 0L,
                            requestCount = 0L,
                            priceSettings = LegacyPriceSettings(),
                        )
                )
            )
        val forcedPlan = plan(empty, existing, forceReplace = true, existingIdentities = identities)

        assertEquals(
            listOf(existing.keys.single()),
            forcedPlan.removedBaselineIdentityIds
        )
    }

    @Test
    fun `controlled restore with empty snapshot removes all legacy baselines but keeps config baselines`() {
        val legacy =
            snapshotOf(
                "DEEPSEEK:deepseek-chat",
                input = 1_000_000,
                output = 500_000,
                requests = 3,
                priceSettings =
                    LegacyPriceSettings(
                        inputPricePerMillion = 1.0,
                        outputPricePerMillion = 2.0,
                    ),
            )
        val first = plan(legacy)
        val legacyBaseline = first.baselines.single()

        val configIdentity =
            TokenStatIdentityEntity(
                identityId = "config-identity",
                configId = "cfg-1",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                displayModelId = "deepseek-chat",
            )
        val configBaseline = legacyBaseline.copy(identityId = configIdentity.identityId)
        val existing = mapOf(legacyBaseline.identityId to legacyBaseline, configBaseline.identityId to configBaseline)
        val identities = legacyIdentitiesFor(existing, legacy) + mapOf(configIdentity.identityId to configIdentity)

        // 恢复后的权威旧偏好快照完全为空：forceReplace 仍产出删除计划——
        // 全部 legacy（configId 空）baseline 被删除，config baseline 保留
        val empty = LegacyTokenStatsSnapshot(providerModels = emptyMap())
        val forced = plan(empty, existing, forceReplace = true, existingIdentities = identities)

        assertEquals(0, forced.baselines.size)
        assertEquals(listOf(legacyBaseline.identityId), forced.removedBaselineIdentityIds)
    }

    private fun emptySnapshot() =
        LegacyTokenStatsSnapshot(
            providerModels = mapOf(
                "OTHER:model-x" to
                    LegacyProviderModelStats(
                        providerModel = "OTHER:model-x",
                        inputTokens = 10L,
                        cachedInputTokens = 0L,
                        outputTokens = 0L,
                        requestCount = 0L,
                        priceSettings = LegacyPriceSettings(),
                    )
            )
        )

    @Test
    fun `existing baseline is frozen when counts and prices change`() {
        val first =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                )
            )
        val existing = first.baselines.associateBy { it.identityId }
        val baselineBefore = first.baselines.single()

        // 普通用户从未自定义价格也代表完整状态；首次迁移无价格也冻结。
        val noCustomPrice =
            plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1_000_000, output = 500_000, requests = 3))
        assertEquals(1, noCustomPrice.baselines.size)

        // 计数不变 + 价格变化（普通 setter 改价）：不重估（指纹只含计数）
        val repriced =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 99.0,
                            outputPricePerMillion = 99.0,
                        ),
                ),
                existing,
            )

        assertEquals(0, repriced.baselines.size)
        assertEquals(2.0, baselineBefore.costInPricingCurrency!!, 1e-9)
    }

    @Test
    fun `force replace re-imports all baselines from the current snapshot`() {
        val first =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 1.0,
                            outputPricePerMillion = 2.0,
                        ),
                )
            )
        val existing = first.baselines.associateBy { it.identityId }

        // 受控补导（恢复后）：forceReplace 忽略已有 baseline，用当前快照重估
        val restored =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = 1_000_000,
                    output = 500_000,
                    requests = 3,
                    priceSettings =
                        LegacyPriceSettings(
                            inputPricePerMillion = 2.0,
                            outputPricePerMillion = 4.0,
                        ),
                ),
                existing,
                forceReplace = true,
            )

        assertEquals(1, restored.baselines.size)
        val baseline = restored.baselines.single()
        // 1000k*2 + 500k*4 = 2.0 + 2.0
        assertEquals(4.0, baseline.costInPricingCurrency!!, 1e-9)
    }

    @Test
    fun `current price override change does not re-estimate imported baseline`() {
        // 首次导入时旧配置链与当前覆盖并存（覆盖为空 → 走旧配置链）
        val first = plan(snapshotOf("DEEPSEEK:deepseek-chat", input = 1_000_000, output = 500_000, requests = 3))
        val existing = first.baselines.associateBy { it.identityId }
        val baselineBefore = first.baselines.single()

        // 用户后续新增当前价格覆盖（新系统价格）：指纹与估算都必须不受影响
        val override =
            TokenPriceResolver.normalizedOverride(
                scope = "PROVIDER_MODEL",
                provider = "DEEPSEEK",
                model = "deepseek-chat",
                configId = null,
                billingMode = BillingMode.TOKEN,
                pricingCurrency = PricingCurrency.USD.name,
                inputPricePerMillion = 99.0,
                outputPricePerMillion = 99.0,
            )

        val second =
            plan(
                snapshotOf("DEEPSEEK:deepseek-chat", input = 1_000_000, output = 500_000, requests = 3),
                existing,
                legacyOverrideOverrides = listOf(override),
            )

        assertEquals(0, second.baselines.size)
        assertEquals(2.0, baselineBefore.costInPricingCurrency!!, 1e-9)
    }

    @Test
    fun `provider-only legacy keys are skipped without crash`() {
        val p = plan(snapshotOf("DEEPSEEK", input = 10L))

        assertEquals(0, p.baselines.size)
        assertEquals(listOf("DEEPSEEK"), p.skippedProviderModels)
    }

    @Test
    fun `multiple provider models produce one baseline each`() {
        val snapshot =
            LegacyTokenStatsSnapshot(
                providerModels =
                    mapOf(
                        "DEEPSEEK:deepseek-chat" to
                            LegacyProviderModelStats(
                                providerModel = "DEEPSEEK:deepseek-chat",
                                inputTokens = 10L,
                                cachedInputTokens = 0L,
                                outputTokens = 5L,
                                requestCount = 1L,
                                priceSettings = LegacyPriceSettings(),
                            ),
                        "OPENAI:gpt-4o" to
                            LegacyProviderModelStats(
                                providerModel = "OPENAI:gpt-4o",
                                inputTokens = 20L,
                                cachedInputTokens = 0L,
                                outputTokens = 8L,
                                requestCount = 2L,
                                priceSettings = LegacyPriceSettings(),
                            ),
                    )
            )

        val p = plan(snapshot)

        assertEquals(2, p.baselines.size)
    }

    @Test
    fun `baseline estimate is safe for cumulative values beyond Int max`() {
        val hugeInput = 3_000_000_000L
        val hugeCached = 1_500_000_000L
        val hugeOutput = 2_000_000_000L
        val p =
            plan(
                snapshotOf(
                    "DEEPSEEK:deepseek-chat",
                    input = hugeInput,
                    cached = hugeCached,
                    output = hugeOutput,
                    requests = 4,
                )
            )

        val baseline = p.baselines.single()
        val uncached = hugeInput - hugeCached
        val expected = uncached / 1_000_000.0 * 1.0 + hugeCached / 1_000_000.0 * 0.5 + hugeOutput / 1_000_000.0 * 2.0
        assertEquals(expected, baseline.costInPricingCurrency!!, 1e-9)
        assertEquals(hugeInput, baseline.inputTokens)
    }

    @Test
    fun `reimport preserves manually merged display group and alias`() {
        val snapshot = snapshotOf("DEEPSEEK:deepseek-chat", input = 1000, output = 500, requests = 3)
        val first = plan(snapshot)
        val identity = first.identities.single()
        val displayModel = first.displayModels.single()

        // 用户手动合并展示组：别名 + 新的分组 ID
        val mergedGroupId = "my-deepseek-group"
        val existingIdentities =
            mapOf(
                identity.identityId to
                    identity.copy(displayModelId = mergedGroupId)
            )
        val existingDisplayModels =
            mapOf(
                mergedGroupId to
                    TokenStatDisplayModelEntity(
                        displayModelId = mergedGroupId,
                        normalizedModel = "deepseek-chat",
                        displayName = "我的 DeepSeek",
                    ),
                displayModel.displayModelId to displayModel,
            )

        // 受控补导（恢复后 forceReplace）：分组与别名必须保留
        val repricedSnapshot =
            snapshotOf("DEEPSEEK:deepseek-chat", input = 1500, output = 700, requests = 4)
        val plan =
            TokenBaselineMigrator.planImport(
                snapshot = repricedSnapshot,
                existingBaselines = first.baselines.associateBy { it.identityId },
                nowMs = 2_000L,
                forceReplace = true,
                resolveIdentity = { pm ->
                    val (provider, model) = TokenStatIdentityResolver.splitProviderModel(pm)
                    TokenStatIdentityEntity(
                        identityId = TokenStatIdentityResolver.identityId("", provider, model),
                        configId = "",
                        provider = provider,
                        model = model,
                        displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
                    )
                },
                resolveDisplayModel = { TokenBaselineMigrator.defaultDisplayModel(it) },
                resolvePricing = { pm ->
                    TokenPriceResolver.resolve(
                        provider = "DEEPSEEK",
                        model = "deepseek-chat",
                        configId = null,
                        overrides = emptyList(),
                        legacyOverride = repricedSnapshot.providerModels[pm]?.priceSettings,
                        defaults = knownDefaults,
                    )
                },
            )

        val preserved =
            TokenBaselineMigrator.preserveExistingGroups(
                plan = plan,
                existingIdentities = existingIdentities,
                existingDisplayModels = existingDisplayModels,
            )

        assertEquals(mergedGroupId, preserved.identities.single().displayModelId)
        // 已存在的展示模型不重写（别名保留），只补缺省模型行
        assertTrue(preserved.displayModels.none { it.displayModelId == mergedGroupId })
        assertEquals(1, preserved.baselines.size)
        assertEquals(1500L, preserved.baselines.single().inputTokens)
    }

    @Test
    fun `fingerprint covers counts only and ignores legacy price settings`() {
        val statsA =
            snapshotOf("DEEPSEEK:deepseek-chat", input = 10L)
                .providerModels.getValue("DEEPSEEK:deepseek-chat")
        val statsB =
            snapshotOf(
                "DEEPSEEK:deepseek-chat",
                input = 10L,
                priceSettings = LegacyPriceSettings(inputPricePerMillion = 2.0),
            ).providerModels.getValue("DEEPSEEK:deepseek-chat")
        val statsC =
            snapshotOf(
                "DEEPSEEK:deepseek-chat",
                input = 20L,
                priceSettings = LegacyPriceSettings(inputPricePerMillion = 2.0),
            ).providerModels.getValue("DEEPSEEK:deepseek-chat")

        // 价格设置变化不改变指纹（价格编辑不触发重导）
        assertEquals(TokenBaselineMigrator.fingerprint(statsA), TokenBaselineMigrator.fingerprint(statsB))
        // 计数变化改变指纹（计数变化触发重导）
        assertFalse(
            TokenBaselineMigrator.fingerprint(statsB) ==
                TokenBaselineMigrator.fingerprint(statsC)
        )
    }
}

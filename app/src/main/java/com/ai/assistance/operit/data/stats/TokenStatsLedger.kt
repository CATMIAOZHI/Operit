package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 冻结的“发生时”价格/成本快照（P1-1）：请求收尾解析一次，随 spool 行持久化；
 * 排空与重放只使用该快照，绝不重读当前价格。成本在冻结时计算。
 */
data class FrozenEventPricing(
    val pricing: ResolvedPricing,
    val cost: Double?,
)

/**
 * 统一 usage 记录器（阶段 2）：把 [TokenStatRequestContext] 落成
 * [TokenStatEventEntity] 账本事件。
 *
 * - **幂等**：事件以 [TokenStatEventEntity.eventId] 为主键 IGNORE 插入；同一
 *   eventId 重复落账不会重复入账。
 * - **失败不破坏业务**：数据库/价格解析失败只记录日志并返回，绝不向上抛出
 *   （[CancellationException] 除外——协程取消必须向上传播，不能当作写入失败吞掉）。
 * - 身份（configId+provider+model）不存在时自动 INSERT IGNORE 创建；展示模型分组
 *   缺失时自动补齐（默认规范化模型名分组）。
 * - 价格：CONFIG 覆盖 > PROVIDER_MODEL 覆盖 > 旧 DataStore 用户价格 > 内置默认价
 *   （[TokenPriceResolver] 层级）；成本用事件发生时的原币价格快照计算。
 * - 诊断字段 [TokenStatEventEntity.diagnosticsJson] 只保存脱敏来源标签与计数，
 *   不保存正文、API key、Cookie 或 endpoint 凭据。
 *
 * [databaseProvider] / [legacyPriceProvider] 为测试注入缝：生产代码始终为 null
 * （真实 [AppDatabase] 与真实 DataStore 读取）；测试注入真实 Room 数据库与
 * 桩价格来源验证语义。
 */
object TokenStatsLedger {

    private const val TAG = "TokenStatsLedger"

    internal var databaseProvider: ((Context) -> AppDatabase)? = null

    internal var legacyPriceProvider: (suspend (Context, String) -> LegacyPriceSettings?)? = null

    /** Linearization token captured before a model invocation starts. Failure aborts that call. */
    suspend fun currentResetGeneration(context: Context): Long {
        val appContext = context.applicationContext
        val database = databaseProvider?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
        return database.tokenStatsDao().currentResetGeneration()
    }

    /**
     * 请求接受边界（P1-1 修复）：在**同一 Room 事务**内确保身份存在（INSERT IGNORE +
     * 默认展示分组补齐）并读取当前 generation。展示分组删除与请求开始因此按事务原子
     * 串行化：删除要么看见该身份并写 IDENTITY tombstone（删除前接受的事件被跳过），
     * 要么请求捕获 ≥ tombstone 的新 generation（删除后请求正常入账）——首次请求的
     * 身份绝不可能绕过分组删除 tombstone 复活旧事件。
     * @throws CancellationException 协程取消向上传播。
     */
    internal suspend fun ensureIdentityAndCaptureGeneration(
        context: Context,
        configId: String,
        provider: String,
        model: String,
    ): Long {
        val appContext = context.applicationContext
        val database = databaseProvider?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
        return database.tokenStatsDao().ensureIdentityAndCaptureGenerationTx(
            identityEntityFor(configId, provider, model),
            displayModelEntityFor(model),
        )
    }

    /**
     * 记录一个请求事件。写入失败（非取消）只记录日志，不影响原响应/取消传播。
     * @throws CancellationException 协程取消时向上传播，不吞掉。
     */
    suspend fun record(context: Context, request: TokenStatRequestContext) {
        try {
            val appContext = context.applicationContext
            val injected = databaseProvider
            val database =
                injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
            recordWith(appContext, database.tokenStatsDao(), request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(
                TAG,
                "统计事件写入失败（不影响业务）: eventId=${request.eventId}, " +
                    "category=${request.category}, status=${request.status}, " +
                    "provider=${request.provider}, model=${request.model}",
                e,
            )
        }
    }

    /**
     * 请求收尾：解析并冻结“发生时”价格/成本快照，生成 spool v2 行（P1-1）。
     * 数据库/DataStore 不可用时生成明确 UNKNOWN 快照，价格和成本保持 null；默认价
     * 不能冒充当时可能存在但未能读取的用户覆盖。
     * @throws CancellationException 协程取消向上传播。
     */
    internal suspend fun prepareEventLine(
        appContext: Context,
        request: TokenStatRequestContext,
        baseJson: JSONObject,
    ): String {
        val detached = prepareEventLineDetached(appContext, request)
        request.frozenPricing = detached.frozenPricing
        request.pricingResolutionDiagnostic = detached.diagnostic
        return detached.line
    }

    /**
     * Detached 版本的收尾行生成：worker 线程在完全独立的 base JSON 上构建行，绝不读写
     * 调用方的 [TokenStatRequestContext] 或共享 JSON，因此超时后被弃置的任务不可能与
     * 调用方的 UNKNOWN 回退路径竞争同一组可变对象（P2-1）。
     */
    internal data class DetachedEventLine(
        val line: String,
        val frozenPricing: FrozenEventPricing,
        val diagnostic: String?,
    )

    internal suspend fun prepareEventLineDetached(
        appContext: Context,
        request: TokenStatRequestContext,
    ): DetachedEventLine {
        val frozen = resolveFrozenPricing(appContext, request)
        val line =
            request.toSpoolBaseJson()
                .apply {
                    put(
                        "pricing",
                        TokenStatRequestContext.pricingToJson(
                            frozen.pricing,
                            frozen.cost,
                            request.pricingResolutionDiagnostic,
                        ),
                    )
                }
                .toString()
        return DetachedEventLine(line, frozen, request.pricingResolutionDiagnostic)
    }

    private suspend fun resolveFrozenPricing(
        appContext: Context,
        request: TokenStatRequestContext,
    ): FrozenEventPricing {
        val pricing = resolvePricing(appContext, request)
        val usage = request.aggregatedUsage()
        val cost = usage?.let { TokenCostCalculator.computeCost(it.toTokenUsageInput(), pricing)?.amount }
        return FrozenEventPricing(pricing, cost)
    }

    /** 价格读取失败/超时：完整 usage 仍持久化，但价格与成本明确 unresolved。 */
    internal fun prepareUnresolvedEventLine(
        request: TokenStatRequestContext,
        baseJson: JSONObject,
        diagnostic: String,
    ): String {
        val pricing =
            ResolvedPricing(
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                source = PricingSource.UNKNOWN,
                known = false,
            )
        request.pricingResolutionDiagnostic = diagnostic
        request.frozenPricing = FrozenEventPricing(pricing, null)
        return baseJson
            .apply {
                put(
                    "pricing",
                    TokenStatRequestContext.pricingToJson(pricing, null, diagnostic),
                )
            }
            .toString()
    }

    private suspend fun resolvePricing(
        appContext: Context,
        request: TokenStatRequestContext,
    ): ResolvedPricing {
        val injected = databaseProvider
        val database = injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
        val dao = database.tokenStatsDao()
        val providerModel = "${request.provider}:${request.model}"
        val overrides = dao.getAllPriceOverrides()
        val legacyOverride =
            legacyPriceProvider?.invoke(appContext, providerModel)
                ?: ApiPreferences.getInstance(appContext).legacyPriceSettingsFor(providerModel)
        return resolvePricingFrom(overrides, legacyOverride, request)
    }

    private fun resolvePricingFrom(
        overrides: List<com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity>,
        legacyOverride: LegacyPriceSettings?,
        request: TokenStatRequestContext,
    ): ResolvedPricing {
        val providerModel = "${request.provider}:${request.model}"
        return TokenPriceResolver.resolve(
            provider = request.provider,
            model = request.model,
            configId = request.configId,
            overrides = overrides,
            legacyOverride = legacyOverride,
            defaults = DefaultModelPricingCollect.getDefaultPricing(providerModel),
            contextInputTokens = TokenCostCalculator.contextInputTokens(
                request.aggregatedUsage()?.toTokenUsageInput(), request.attemptCount,
            ),
            selectContextTier = true,
        )
    }

    /**
     * 实际落账（含身份创建；错误直接向上抛，由调用方决定重试边界）。
     * 事件携带冻结价格快照（spool 重放）时直接使用，否则现场解析（直接路径）。
     * 插入经 [TokenStatsDao.insertEventIfNotResetCovered] 与 reset tombstone 同
     * 事务检查：被 reset 覆盖的事件跳过（视为已处理），不会复活（P1-3）。
     */
    internal suspend fun recordWith(
        appContext: Context,
        dao: TokenStatsDao,
        request: TokenStatRequestContext,
    ) {
        val identity = ensureIdentity(dao, request)

        val frozen = request.frozenPricing
        val pricing: ResolvedPricing
        val cost: Double?
        if (frozen != null) {
            pricing = frozen.pricing
            cost = frozen.cost
        } else {
            pricing = resolvePricing(appContext, request)
            val usage = request.aggregatedUsage()
            cost = usage?.let { TokenCostCalculator.computeCost(it.toTokenUsageInput(), pricing)?.amount }
        }
        val usage = request.aggregatedUsage()

        dao.insertEventIfNotResetCovered(
            TokenStatEventEntity(
                eventId = request.eventId,
                statIdentityId = identity.identityId,
                category = request.category.name,
                status = (request.status ?: TokenStatStatus.FAILED).name,
                acceptedGeneration = request.acceptedGeneration,
                startedAtMs = request.startedAtMs,
                endedAtMs = request.endedAtMs,
                firstTokenAtMs = request.firstTokenAtMs,
                uncachedInputTokens = usage?.uncachedInputTokens,
                cachedInputTokens = usage?.cachedInputTokens,
                cacheWriteTokens = usage?.cacheWriteTokens,
                totalInputTokens = usage?.totalInputTokens,
                outputTokens = usage?.outputTokens,
                reasoningTokens = usage?.reasoningTokens,
                reasoningIncludedInOutput = usage?.reasoningIncludedInOutput,
                // 结构化保存缓存写入计费模型：当前价格重估直接读取，不解析 JSON
                cacheWriteSeparateBilling = usage?.cacheWriteSeparateBilling,
                billingMode = pricing.billingMode.name,
                pricingCurrency = pricing.currency.name,
                inputPricePerMillion = pricing.inputPricePerMillion,
                cachedInputPricePerMillion = pricing.cachedInputPricePerMillion,
                cacheWritePricePerMillion = pricing.cacheWritePricePerMillion,
                outputPricePerMillion = pricing.outputPricePerMillion,
                pricePerRequest = pricing.pricePerRequest,
                pricingSource = pricing.source.name,
                costInPricingCurrency = cost,
                diagnosticsJson = buildDiagnosticsJson(request),
            )
        )
    }

    /** 身份不存在时创建（INSERT IGNORE，绝不 REPLACE），并补齐默认展示分组。 */
    private suspend fun ensureIdentity(
        dao: TokenStatsDao,
        request: TokenStatRequestContext,
    ): TokenStatIdentityEntity {
        val identity = identityEntityFor(request.configId, request.provider, request.model)
        dao.insertIdentityIfAbsent(identity)
        dao.insertDisplayModelIfAbsent(displayModelEntityFor(request.model))
        return identity
    }

    /** 身份行构造（请求边界与落账共用，与既有 [ensureIdentity] 规范化完全一致）。 */
    private fun identityEntityFor(
        configId: String,
        provider: String,
        model: String,
    ): TokenStatIdentityEntity =
        TokenStatIdentityEntity(
            identityId = TokenStatIdentityResolver.identityId(configId, provider, model),
            configId = configId,
            provider = provider,
            model = model,
            displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
        )

    /** 默认展示分组行构造（默认规范化模型名分组，与既有 [ensureIdentity] 完全一致）。 */
    private fun displayModelEntityFor(model: String): TokenStatDisplayModelEntity =
        TokenStatDisplayModelEntity(
            displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
            normalizedModel = TokenStatIdentityResolver.normalizeModelName(model),
            displayName = model,
        )

    /** 脱敏诊断字段：来源标签、是否观察到 usage、上报次数、attempt 数；无正文/凭据。 */
    private fun buildDiagnosticsJson(request: TokenStatRequestContext): String? {
        val usage = request.aggregatedUsage()
        return JSONObject().apply {
            if (usage != null) {
                put("source", usage.source)
                put("reasoningIncludedInOutput", usage.reasoningIncludedInOutput)
                put("cacheWriteSeparateBilling", usage.cacheWriteSeparateBilling)
            }
            put("usageObserved", usage != null)
            put("usageReportCount", request.usageReportCount)
            put("attemptCount", request.attemptCount)
            request.pricingResolutionDiagnostic?.let { put("pricingResolution", it) }
        }.toString()
    }
}

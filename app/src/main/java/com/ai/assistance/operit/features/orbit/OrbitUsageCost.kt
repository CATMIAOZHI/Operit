package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.model.supportsModelProtocolOverrides
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.preferences.OrbitModelPreferences
import com.ai.assistance.operit.data.preferences.OrbitModelSettings
import com.ai.assistance.operit.data.stats.LegacyPriceSettings
import com.ai.assistance.operit.data.stats.PricingSource
import com.ai.assistance.operit.data.stats.ResolvedPricing
import com.ai.assistance.operit.data.stats.TokenCostCalculator
import com.ai.assistance.operit.data.stats.TokenCostCurrency
import com.ai.assistance.operit.data.stats.TokenPriceResolver
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenUsageInput
import com.ai.assistance.operit.plugins.toolpkg.ToolPkgAiProviderRegistry
import com.ai.assistance.operit.util.AppLogger
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What Orbit's use cost, for its usage page and its usage tool, so both always agree.
 *
 * Calls that got a cost when they ended keep it. Every other call is priced at today's price and
 * shown as estimated: calls that got none (no price yet, or no usage reported) at their model's
 * price, and calls from before costs were kept, which kept no model, at the price of the model
 * the Orbit that made them uses now for that kind of work.
 */
internal object OrbitUsageCost {
    private const val TAG = "OrbitUsageCost"

    /** The currency and USD to CNY rate the app's own usage page shows costs in. */
    class Money(val currency: PricingCurrency, val rate: Double, val rateEstimated: Boolean) {
        fun convert(cny: Double, usd: Double): Double = when (currency) {
            PricingCurrency.CNY -> cny + usd * rate
            PricingCurrency.USD -> usd + cny / rate
        }
    }

    suspend fun money(context: Context): Money {
        return try {
            val api = ApiPreferences.getInstance(context)
            val (rate, estimated) = api.usdToCnyRateWithEstimate()
            val safe = rate.takeIf { it.isFinite() && it > 0.0 } ?: TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE
            Money(api.getStatsTargetCurrency(), safe, estimated || safe != rate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read the currency settings: ${e.message}")
            Money(PricingCurrency.CNY, TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE, true)
        }
    }

    /**
     * Today's price for one model, from every connection the app's ledger has for it: every Orbit
     * call is also there, under the connection it used, which Orbit's own records do not keep.
     */
    class ModelPrice internal constructor(private val connections: List<(Long?) -> ResolvedPricing>) {
        /**
         * The price for a call with [contextTokens] input, when every connection has one and they
         * agree; null otherwise, so no call is priced with another connection's price.
         */
        fun at(contextTokens: Long?): ResolvedPricing? {
            if (connections.isEmpty()) return null
            val prices = connections.map { it(contextTokens) }
            if (prices.any { !it.known }) return null
            // Where a price came from does not matter, only what it is.
            return prices.distinctBy { it.copy(source = PricingSource.DEFAULT) }.singleOrNull()
        }
    }

    /** Older calls are priced by their Orbit and whether they were chat, the one split they kept. */
    data class OlderKey(val orbitId: String, val chat: Boolean) {
        companion object {
            fun of(row: OrbitQuota.Usage) = OlderKey(row.orbitId, row.purpose == OrbitQuota.Purpose.CHAT)
        }
    }

    /** A price with the model it is for, so older calls can be listed under that model. */
    class NamedPrice(val model: String, val price: ModelPrice)

    /** Today's prices for every call without a cost: by model, and for older calls by [OlderKey]. */
    class Prices(
        val models: Map<String, ModelPrice> = emptyMap(),
        val older: Map<OlderKey, NamedPrice> = emptyMap(),
    )

    private fun normalize(model: String) = TokenStatIdentityResolver.normalizeModelName(model)

    /** One connection's price, worked out once per price tier so calls in the same tier share it. */
    private fun connection(
        identity: TokenStatIdentityEntity,
        overrides: List<TokenStatPriceOverrideEntity>,
        legacy: Map<String, LegacyPriceSettings?>,
    ): (Long?) -> ResolvedPricing {
        val catalog = DefaultModelPricingCollect.getDefaultPricing(identity.providerModel)
        val legacyPrice = legacy[identity.providerModel]
        val byTier = HashMap<Long, ResolvedPricing>()
        return { tokens ->
            byTier.getOrPut(catalog.contextTierKey(tokens)) {
                TokenPriceResolver.resolve(
                    provider = identity.provider,
                    model = identity.model,
                    configId = identity.configId,
                    overrides = overrides,
                    legacyOverride = legacyPrice,
                    defaults = catalog,
                    contextInputTokens = tokens,
                    selectContextTier = true,
                )
            }
        }
    }

    /**
     * Today's [Prices] for the calls from [first] to [last] that have no cost. An older call's
     * Orbit is priced at its chat model for chat and at its assistant model for everything else,
     * as it is set now, with the connection's current provider, whether or not it has been used yet.
     */
    suspend fun prices(
        context: Context,
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
    ): Prices {
        val from = first.toString()
        val to = last.toString()
        val wanted = data.models.filter { it.day >= from && it.day <= to && it.calls > it.pricedCalls }
            .mapTo(HashSet()) { it.model }
        val olderKeys = data.rows.filter { it.day >= from && it.day <= to && it.legacyCalls > 0L }
            .mapTo(HashSet()) { OlderKey.of(it) }
        if (wanted.isEmpty() && olderKeys.isEmpty()) return Prices()
        return try {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val legacy = ApiPreferences.getInstance(appContext).allLegacyPriceSettings()
                val dao = AppDatabase.getDatabase(appContext).tokenStatsDao()
                val overrides = dao.getAllPriceOverrides()
                val models = if (wanted.isEmpty()) emptyMap() else {
                    val byModel = dao.getAllIdentities().groupBy { normalize(it.model) }
                    wanted.associateWith { model ->
                        ModelPrice(byModel[normalize(model)].orEmpty().map { connection(it, overrides, legacy) })
                    }
                }
                if (olderKeys.isEmpty()) return@withContext Prices(models)
                val configs = try {
                    ModelConfigManager(appContext).getAllConfigSummaries().associateBy { it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Older calls stay unknown; the other prices still count.
                    AppLogger.w(TAG, "Could not read the model connections: ${e.message}")
                    return@withContext Prices(models)
                }
                val preferences = OrbitModelPreferences(appContext)
                val settings = HashMap<String, OrbitModelSettings?>()
                olderKeys.mapTo(HashSet()) { it.orbitId }.forEach { id ->
                    settings[id] = try {
                        preferences.observe(id).first()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Could not read an Orbit's models: ${e.message}")
                        null
                    }
                }
                // Orbits on the same connection and model share one price.
                val shared = HashMap<Triple<String, String, String>, ModelPrice>()
                val older = olderKeys.mapNotNull { key ->
                    val set = settings[key.orbitId] ?: return@mapNotNull null
                    val ref = (if (key.chat) set.main else set.assistant ?: set.main) ?: return@mapNotNull null
                    // A plugin's unnamed default model cannot be priced.
                    val model = ref.modelName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val config = configs[ref.configId] ?: return@mapNotNull null
                    // A model taken off its connection is no longer what the Orbit uses.
                    if (ref.resolveIndex(config.modelName, config.apiProviderTypeId) < 0) return@mapNotNull null
                    // The provider the ledger records for this connection's calls, after any
                    // per-model protocol, as when the call is made.
                    val stored = config.apiProviderTypeId.trim()
                    // A retired provider can no longer serve the Orbit either.
                    if (ApiProviderType.isRetiredId(stored)) return@mapNotNull null
                    val typeId = if (supportsModelProtocolOverrides(stored)) {
                        config.modelProtocolSettings[model]?.protocol?.providerType?.name ?: stored
                    } else stored
                    val provider = ToolPkgAiProviderRegistry.get(typeId)?.providerId
                        ?: ApiProviderType.fromProviderTypeId(typeId)?.name ?: return@mapNotNull null
                    val price = shared.getOrPut(Triple(ref.configId, provider, model)) {
                        val identity = TokenStatIdentityEntity(identityId = "", configId = ref.configId,
                            provider = provider, model = model, displayModelId = normalize(model))
                        ModelPrice(listOf(connection(identity, overrides, legacy)))
                    }
                    key to NamedPrice(model, price)
                }.toMap()
                Prices(models, older)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read today's prices: ${e.message}")
            Prices()
        }
    }

    /**
     * What [unpriced] of [calls] calls come to at today's [price], in its currency, with its
     * currency, given the tokens of all [calls] together: the price tier is picked by the average
     * input per call. Cache writes are not kept apart and count as ordinary input. Null when it
     * cannot be worked out.
     */
    internal fun estimate(
        input: Long,
        output: Long,
        cached: Long,
        calls: Long,
        unpriced: Long,
        price: ModelPrice,
    ): Pair<Double, PricingCurrency>? {
        if (unpriced <= 0L || calls <= 0L) return null
        val inTokens = input.coerceAtLeast(0L)
        val pricing = price.at(inTokens / calls) ?: return null
        val cachedIn = cached.coerceIn(0L, inTokens)
        val usage = TokenUsageInput(
            uncachedInputTokens = inTokens - cachedIn,
            cachedInputTokens = cachedIn,
            cacheWriteTokens = 0L,
            outputTokens = output.coerceAtLeast(0L),
            // Orbit's output already includes any reasoning.
            reasoningIncludedInOutput = true,
            cacheWriteSeparateBilling = false,
        )
        val amount = TokenCostCalculator.computeCost(usage, pricing).amount?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        // A price per request is for one call; a price per token is for all the calls.
        val perCall = if (pricing.billingMode == BillingMode.COUNT) amount else amount / calls
        // The same limit as when calls are recorded, so a mistyped price stays unknown here too.
        if (!perCall.isFinite() || perCall > OrbitQuota.MAX_CALL_COST) return null
        val share = perCall * unpriced.coerceAtMost(calls).toDouble()
        return if (share.isFinite()) share to pricing.currency else null
    }

    /** [row]'s calls that got no cost when they ended, at today's [price]; a row's priced calls get their share by count. */
    internal fun backfill(row: OrbitQuota.ModelUsage, price: ModelPrice): Pair<Double, PricingCurrency>? =
        estimate(row.input, row.output, row.cached, row.calls, row.calls - row.pricedCalls, price)

    /**
     * [row]'s calls from before costs were kept, at today's [price]. They kept only their tokens
     * together, so these are split into input and output like the whole row's, with no cache reads.
     */
    internal fun older(row: OrbitQuota.Usage, price: ModelPrice): Pair<Double, PricingCurrency>? {
        val tokens = row.legacyTokens
        val all = row.input.coerceAtLeast(0L) + row.output.coerceAtLeast(0L)
        val input = if (all > 0L) (tokens * (row.input.coerceAtLeast(0L).toDouble() / all)).roundToLong() else 0L
        return estimate(input, tokens - input, 0L, row.legacyCalls, row.legacyCalls, price)
    }

    class ModelCost(
        val model: String,
        val cost: Double,
        val tokens: Long,
        val calls: Long,
        val unpricedCalls: Long,
        /** Calls priced at today's price because they had no cost. */
        val estimatedCalls: Long = 0L,
    )

    class View(
        val money: Money,
        /** Every call in the range. */
        val calls: Long,
        /** Known and estimated together, in [Money.currency]. */
        val total: Double,
        /** The part of [total] that is known, and the calls it covers. */
        val known: Double,
        val pricedCalls: Long,
        /** The part of [total] for calls without a cost, at today's price, and the calls it covers. */
        val estimated: Double,
        val estimatedCalls: Long,
        /** Calls whose cost could not be worked out, even at today's price: in no total. */
        val unpricedCalls: Long,
        /** Cache reads over the input of calls whose provider reported them; null with none. */
        val cacheRate: Double?,
        val models: List<ModelCost>,
        /** Cost by usage page line: a purpose's name for this Orbit's own use, "others" for other Orbits. */
        val byLine: Map<String, Double>,
        /** Lines with any priced or estimated call; the others' cost is unknown. */
        val coveredLines: Set<String>,
        /** Lines whose cost includes an estimate. */
        val estimatedLines: Set<String>,
        /** True when some cost was in the other currency and converted at [Money.rate]. */
        val converted: Boolean,
    ) {
        /** Calls whose cost is known or estimated. */
        val coveredCalls: Long get() = pricedCalls + estimatedCalls

        /** Some of [total] is an estimate. */
        val approximate: Boolean get() = estimatedCalls > 0L
    }

    /** The cost of every Orbit's use from [first] to [last], both included. */
    fun view(
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
        orbitId: String,
        money: Money,
        prices: Prices = Prices(),
    ): View {
        val from = first.toString()
        val to = last.toString()
        val other = if (money.currency == PricingCurrency.CNY) PricingCurrency.USD else PricingCurrency.CNY
        var converted = false
        fun amount(cny: Double, usd: Double): Double {
            if ((if (other == PricingCurrency.USD) usd else cny) > 0.0) converted = true
            return money.convert(cny, usd)
        }
        fun amount(estimate: Pair<Double, PricingCurrency>): Double? {
            val (raw, currency) = estimate
            return (if (currency == PricingCurrency.CNY) amount(raw, 0.0) else amount(0.0, raw)).takeIf { it.isFinite() }
        }
        fun lineOf(row: OrbitQuota.Usage) = if (row.orbitId == orbitId) row.purpose.name else "others"

        var known = 0.0
        var estimated = 0.0
        var calls = 0L
        var priced = 0L
        var estimatedCalls = 0L
        var unpriced = 0L
        val byLine = HashMap<String, Double>()
        val coveredLines = HashSet<String>()
        val estimatedLines = HashSet<String>()
        fun addEstimate(key: String, value: Double) {
            byLine[key] = (byLine[key] ?: 0.0) + value
            coveredLines += key
            estimatedLines += key
        }

        /** Each model's totals as the model list shows them, by normalized name. */
        class ModelSum(val name: String) {
            var cost = 0.0
            var tokens = 0L
            var calls = 0L
            var priced = 0L
            var estimated = 0L
        }
        val byModel = LinkedHashMap<String, ModelSum>()
        fun modelSum(model: String) = byModel.getOrPut(normalize(model)) { ModelSum(model) }

        val rows = data.rows.filter { it.day >= from && it.day <= to }
        rows.forEach { row ->
            val key = lineOf(row)
            val rowKnown = amount(row.costCny, row.costUsd)
            known += rowKnown
            calls += row.calls
            priced += row.pricedCalls
            if (row.pricedCalls > 0) coveredLines += key
            byLine[key] = (byLine[key] ?: 0.0) + rowKnown
            unpriced += (row.trackedCalls - row.pricedCalls).coerceAtLeast(0L)
            if (row.legacyCalls <= 0L) return@forEach
            val named = prices.older[OlderKey.of(row)]
            if (named == null) {
                unpriced += row.legacyCalls
                return@forEach
            }
            val sum = modelSum(named.model)
            sum.tokens += row.legacyTokens
            sum.calls += row.legacyCalls
            val value = older(row, named.price)?.let { amount(it) }
            if (value == null) {
                // Listed under its model, with no cost.
                unpriced += row.legacyCalls
                return@forEach
            }
            estimated += value
            estimatedCalls += row.legacyCalls
            addEstimate(key, value)
            sum.cost += value
            sum.estimated += row.legacyCalls
        }

        val modelRows = data.models.filter { it.day >= from && it.day <= to }
        // Each amount is shared out over that day's lines of the same Orbit by their calls without a cost.
        val linesByDay by lazy { rows.groupBy { it.day to it.orbitId } }
        modelRows.forEach { row ->
            val sum = modelSum(row.model)
            sum.cost += amount(row.costCny, row.costUsd)
            sum.tokens += row.tokens
            sum.calls += row.calls
            sum.priced += row.pricedCalls
            val missing = (row.calls - row.pricedCalls).coerceAtLeast(0L)
            if (missing == 0L) return@forEach
            val value = prices.models[row.model]?.let { backfill(row, it) }?.let { amount(it) } ?: return@forEach
            sum.cost += value
            sum.estimated += missing
            estimated += value
            estimatedCalls += missing
            unpriced -= missing
            val lines = linesByDay[row.day to row.orbitId].orEmpty()
            val weights = lines.map { (it.trackedCalls - it.pricedCalls).coerceAtLeast(0L) }
            val weight = weights.sum()
            if (weight > 0L) lines.forEachIndexed { index, line ->
                if (weights[index] > 0L) addEstimate(lineOf(line), value * weights[index] / weight)
            }
        }

        val cacheInput = modelRows.sumOf { it.cacheKnownInput }
        val models = byModel.values.map { sum ->
            ModelCost(sum.name, sum.cost, sum.tokens, sum.calls,
                (sum.calls - sum.priced - sum.estimated).coerceAtLeast(0L), sum.estimated)
        }.sortedWith(compareByDescending<ModelCost> { it.cost }.thenByDescending { it.tokens })
        return View(
            money = money,
            calls = calls,
            total = known + estimated,
            known = known,
            pricedCalls = priced,
            estimated = estimated,
            estimatedCalls = estimatedCalls,
            unpricedCalls = unpriced.coerceAtLeast(0L),
            cacheRate = if (cacheInput > 0L) (modelRows.sumOf { it.cached }.toDouble() / cacheInput).coerceIn(0.0, 1.0) else null,
            models = models,
            byLine = byLine,
            coveredLines = coveredLines,
            estimatedLines = estimatedLines,
            converted = converted,
        )
    }

    /** Reads the money settings and today's prices, then works out [view], all off the main thread. */
    suspend fun compute(
        context: Context,
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
        orbitId: String,
    ): View = withContext(Dispatchers.Default) {
        val money = money(context)
        val prices = prices(context, data, first, last)
        view(data, first, last, orbitId, money, prices)
    }

    /** An amount with its currency sign: "<¥0.01" below a cent, two decimals up to 10,000, whole above. */
    fun format(amount: Double, currency: PricingCurrency): String {
        val sign = currency.symbol
        return when {
            !amount.isFinite() || amount <= 0.0 -> "${sign}0"
            amount < 0.01 -> "<${sign}0.01"
            amount < 10_000 -> sign + String.format(Locale.US, "%.2f", amount)
            else -> sign + String.format(Locale.US, "%,.0f", amount)
        }
    }
}

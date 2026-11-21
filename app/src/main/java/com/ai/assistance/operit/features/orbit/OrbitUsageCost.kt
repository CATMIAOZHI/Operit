package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
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
import java.lang.ref.WeakReference
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What Orbit's use cost, for its usage page and its usage tool, so both always agree.
 *
 * Calls that got a cost when they ended keep it. Every other call is priced at today's price and
 * shown as estimated: calls that got none (no price yet, or no usage reported) at their model's
 * price, and calls from before costs were kept, which kept no model, shared out over the models
 * the app's ledger shows in use that day, each at its own price; on days the ledger has nothing,
 * at the price of the model the Orbit that made them uses now for that kind of work.
 *
 * Calls whose cache reads are not known are taken to read from cache as much as calls on the same
 * model did: for older calls in the ledger that day, else in the ledger for these days, else
 * Orbit's own calls that reported it. Pricing their whole input as uncached would make a model
 * that mostly reads from cache look many times dearer than it is.
 */
internal object OrbitUsageCost {
    private const val TAG = "OrbitUsageCost"
    private const val LEDGER_FRESH_MS = 5 * 60_000L
    private const val MAX_LEDGER_READS = 4

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
            if (connections.size == 1) return connections[0](contextTokens).takeIf { it.known }
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

    /**
     * A model the app's ledger shows in use on a day, on one connection: [share] of that day's
     * input, its price today, and the share of its input read from cache that day when known.
     */
    class DayModel(val model: String, val price: ModelPrice, val share: Double, val cacheRate: Double?)

    /**
     * Today's prices for every call without a cost: by model, and for older calls by [OlderKey];
     * by normalized model, the share of input read from cache in the app's ledger for these days;
     * and by day, the models the ledger shows in use, which older calls on that day are shared out over.
     */
    class Prices(
        val models: Map<String, ModelPrice> = emptyMap(),
        val older: Map<OlderKey, NamedPrice> = emptyMap(),
        val cacheRates: Map<String, Double> = emptyMap(),
        val olderDays: Map<String, List<DayModel>> = emptyMap(),
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
     * Today's [Prices] for the calls from [first] to [last] that have no cost. Older calls go by the
     * models the app's ledger shows in use on their day. On a day it has none, an older call's
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
        val olderRows = data.rows.filter { it.day >= from && it.day <= to && it.legacyCalls > 0L }
        if (wanted.isEmpty() && olderRows.isEmpty()) return Prices()
        // The ledger is read only for calls that kept no model, or did not all say what they read from cache.
        val needLedger = olderRows.isNotEmpty() || data.models.any {
            it.day >= from && it.day <= to && it.calls > it.pricedCalls && it.cacheKnownInput < it.input
        }
        return try {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val legacy = ApiPreferences.getInstance(appContext).allLegacyPriceSettings()
                val dao = AppDatabase.getDatabase(appContext).tokenStatsDao()
                val overrides = dao.getAllPriceOverrides()
                val identities = try {
                    dao.getAllIdentities()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Older calls then go by the Orbit's models; calls with a model stay unknown.
                    AppLogger.w(TAG, "Could not read the ledger's connections: ${e.message}")
                    emptyList()
                }
                val byId = identities.associateBy { it.identityId }
                val models = if (wanted.isEmpty()) emptyMap() else {
                    val byModel = identities.groupBy { normalize(it.model) }
                    wanted.associateWith { model ->
                        ModelPrice(byModel[normalize(model)].orEmpty().map { connection(it, overrides, legacy) })
                    }
                }
                val use = if (needLedger && byId.isNotEmpty()) ledgerUse(dao, first, last) else emptyMap()
                val cacheRates = cacheRates(use, identities.associate { it.identityId to normalize(it.model) })
                // Each connection is priced once, however many days it was in use.
                val priceOf = HashMap<String, ModelPrice>()
                val olderDays = olderDays(use, olderRows.mapTo(HashSet()) { it.day }, byId) { identity ->
                    priceOf.getOrPut(identity.identityId) { ModelPrice(listOf(connection(identity, overrides, legacy))) }
                }
                val olderKeys = olderRows.filter { it.day !in olderDays }.mapTo(HashSet()) { OlderKey.of(it) }
                if (olderKeys.isEmpty()) {
                    return@withContext Prices(models, cacheRates = cacheRates, olderDays = olderDays)
                }
                val configs = try {
                    ModelConfigManager(appContext).getAllConfigSummaries().associateBy { it.id }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Older calls stay unknown; the other prices still count.
                    AppLogger.w(TAG, "Could not read the model connections: ${e.message}")
                    return@withContext Prices(models, cacheRates = cacheRates, olderDays = olderDays)
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
                Prices(models, older, cacheRates, olderDays)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read today's prices: ${e.message}")
            Prices()
        }
    }

    /** One connection's calls on one day in the app's ledger, counted as the ledger query counts them. */
    private class DayUse(val calls: Long, val inputCalls: Long, val input: Double, val cached: Double, val cacheKnown: Double)

    /**
     * A read of the ledger, good while it is fresh and the ledger is the same one, with nothing
     * cleared since: a restore opens a new database, and every clearing moves the generation on.
     */
    private class LedgerRead(
        val atMs: Long,
        val dao: WeakReference<TokenStatsDao>,
        val generation: Long,
        val days: Map<String, Map<String, DayUse>>,
    )

    /** Recent reads of the ledger, by range and time zone; the page offers four ranges. Only under [ledgerLock]. */
    private val ledgerReads = LinkedHashMap<Triple<Long, Long, String>, LedgerRead>()

    /** One read of the ledger at a time, so the page and the usage tool do not both read it, nor an older read replace a newer one. */
    private val ledgerLock = Mutex()

    /**
     * The app's ledger from [first] to [last], by day and connection. Days are as Orbit counts
     * them, by when calls ended, here in the device's current time zone; older calls kept the day
     * of the zone they were made in, so after a change of zone the two can be some hours apart.
     *
     * Reading it goes through every call the ledger has, so a read is reused for a few minutes:
     * the page works its numbers out again each time a call ends. Days gone by only change when the
     * ledger is cleared or restored, which ends the reuse at once; calls since a read show up the
     * first time the numbers are worked out after it is a few minutes old. Empty when it cannot be read.
     */
    private suspend fun ledgerUse(dao: TokenStatsDao, first: LocalDate, last: LocalDate): Map<String, Map<String, DayUse>> {
        val zone = ZoneId.systemDefault()
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val key = Triple(start, end, zone.id)
        return try {
            ledgerLock.withLock {
                val generation = dao.currentResetGeneration()
                ledgerReads[key]?.takeIf {
                    it.dao.get() === dao && it.generation == generation &&
                        System.nanoTime() / 1_000_000L - it.atMs in 0L until LEDGER_FRESH_MS
                }?.let { return@withLock it.days }
                val days = HashMap<String, HashMap<String, DayUse>>()
                dao.getUseByIdentityAndLocalDay(start, end).forEach { row ->
                    days.getOrPut(row.day) { HashMap() }[row.statIdentityId] = DayUse(row.calls, row.inputCalls,
                        row.inputTokens, row.cachedInputTokens, row.cacheKnownInputTokens)
                }
                ledgerReads.remove(key)
                ledgerReads[key] = LedgerRead(System.nanoTime() / 1_000_000L, WeakReference(dao), generation, days)
                while (ledgerReads.size > MAX_LEDGER_READS) ledgerReads.remove(ledgerReads.keys.first())
                days
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read the ledger: ${e.message}")
            emptyMap()
        }
    }

    /**
     * By normalized model, the share of input read from cache in [use], over the calls whose cache
     * reads and whole input are known, as Orbit counts them. [modelOf] is each connection's normalized model.
     */
    private fun cacheRates(use: Map<String, Map<String, DayUse>>, modelOf: Map<String, String>): Map<String, Double> {
        val cached = HashMap<String, Double>()
        val known = HashMap<String, Double>()
        use.values.forEach { day ->
            day.forEach { (id, dayUse) ->
                val model = modelOf[id] ?: return@forEach
                cached.merge(model, dayUse.cached, Double::plus)
                known.merge(model, dayUse.cacheKnown, Double::plus)
            }
        }
        return known.mapNotNull { (model, input) ->
            if (!(input > 0.0)) return@mapNotNull null
            val rate = (cached[model] ?: 0.0) / input
            if (rate.isFinite()) model to rate.coerceIn(0.0, 1.0) else null
        }.toMap()
    }

    /**
     * For each of [days], the models in use that day in [use], by connection. A model's share is
     * its share of the day's input; a call that kept no input counts as the day's average call,
     * and on a day with no input kept at all every call counts the same. Days with no use are left
     * out: their older calls go by the Orbit's models.
     */
    private fun olderDays(
        use: Map<String, Map<String, DayUse>>,
        days: Set<String>,
        byId: Map<String, TokenStatIdentityEntity>,
        priceOf: (TokenStatIdentityEntity) -> ModelPrice,
    ): Map<String, List<DayModel>> = days.mapNotNull { day ->
        // In a fixed order, so the same day always splits the same way.
        val rows = use[day].orEmpty().filter { (id, dayUse) -> id in byId && dayUse.calls > 0L }
            .toSortedMap().toList()
        val inputCalls = rows.sumOf { it.second.inputCalls }
        val average = if (inputCalls > 0L) rows.sumOf { it.second.input } / inputCalls else 0.0
        val byInput = rows.map { (_, u) -> u.input + (u.calls - u.inputCalls).coerceAtLeast(0L) * average }
        val weights = if (byInput.sum().let { it > 0.0 && it.isFinite() }) byInput
            else rows.map { it.second.calls.toDouble() }
        val total = weights.sum()
        if (!(total > 0.0) || !total.isFinite()) return@mapNotNull null
        val models = rows.mapIndexedNotNull { index, (id, u) ->
            val share = weights[index] / total
            if (!(share > 0.0)) return@mapIndexedNotNull null
            val identity = byId.getValue(id)
            val cacheRate = if (u.cacheKnown > 0.0) {
                (u.cached / u.cacheKnown).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            } else null
            DayModel(identity.model, priceOf(identity), share, cacheRate)
        }
        if (models.isEmpty()) null else day to models
    }.toMap()

    /**
     * What [unpriced] of [calls] calls come to at today's [price], in its currency, with its
     * currency, given the tokens of all [calls] together: the price tier is picked by the average
     * input per call. [cacheRate] of the input is taken as read from cache. Cache writes are not
     * kept apart and count as ordinary input. Null when it cannot be worked out.
     */
    internal fun estimate(
        input: Long,
        output: Long,
        cacheRate: Double,
        calls: Long,
        unpriced: Long,
        price: ModelPrice,
    ): Pair<Double, PricingCurrency>? {
        if (unpriced <= 0L || calls <= 0L) return null
        val inTokens = input.coerceAtLeast(0L)
        val pricing = price.at(inTokens / calls) ?: return null
        val rate = cacheRate.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val cachedIn = (inTokens * rate).roundToLong().coerceIn(0L, inTokens)
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

    /**
     * [row]'s calls that got no cost when they ended, at today's [price]; a row's priced calls get
     * their share by count. The cache reads the row's calls reported are kept; the input of calls
     * that did not say reads [fallbackCacheRate] of it from cache, so it does not count as all uncached.
     */
    internal fun backfill(
        row: OrbitQuota.ModelUsage,
        price: ModelPrice,
        fallbackCacheRate: Double = 0.0,
    ): Pair<Double, PricingCurrency>? {
        val input = row.input.coerceAtLeast(0L)
        val known = row.cacheKnownInput.coerceIn(0L, input)
        val fallback = fallbackCacheRate.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        val cached = row.cached.coerceIn(0L, known) + (input - known) * fallback
        val rate = if (input > 0L) cached / input else 0.0
        return estimate(row.input, row.output, rate, row.calls, row.calls - row.pricedCalls, price)
    }

    /**
     * [row]'s calls from before costs were kept, at today's [price]. They kept only their tokens
     * together, so these are split into input and output like the whole row's, with [cacheRate] of
     * the input read from cache.
     */
    internal fun older(row: OrbitQuota.Usage, price: ModelPrice, cacheRate: Double = 0.0): Pair<Double, PricingCurrency>? {
        val (input, output) = olderTokens(row)
        return estimate(input, output, cacheRate, row.legacyCalls, row.legacyCalls, price)
    }

    /** [row]'s older tokens as input and output, split like the whole row's. */
    private fun olderTokens(row: OrbitQuota.Usage): Pair<Long, Long> {
        val tokens = row.legacyTokens
        val all = row.input.coerceAtLeast(0L) + row.output.coerceAtLeast(0L)
        val input = if (all > 0L) (tokens * (row.input.coerceAtLeast(0L).toDouble() / all)).roundToLong()
            .coerceIn(0L, tokens) else 0L
        return input to tokens - input
    }

    /**
     * [total] split by [shares] into whole parts that add up to it, the largest remainders
     * rounding up. Shares need not add up to 1; with none above 0 the parts are all 0. The parts
     * always add up to [total]; worked out in doubles, so with very large totals or remainders
     * nearly alike, which part rounds up can be off by one.
     */
    internal fun split(total: Long, shares: List<Double>): List<Long> {
        val usable = shares.map { if (it.isFinite() && it > 0.0) it else 0.0 }
        // Scaled to the largest first, so huge shares cannot add up past the largest double.
        val top = usable.maxOrNull() ?: 0.0
        val weights = if (top > 0.0) usable.map { it / top } else usable
        val sum = weights.sum()
        if (total <= 0L || !(sum > 0.0) || !sum.isFinite()) return shares.map { 0L }
        val raw = weights.map { total * (it / sum) }
        val parts = LongArray(raw.size) { floor(raw[it]).toLong().coerceIn(0L, total) }
        var left = total - parts.sum()
        val order = raw.indices.filter { weights[it] > 0.0 }.sortedByDescending { raw[it] - parts[it] }
        // Rounding leaves less than one per part; anything more is spread evenly first.
        if (left >= order.size) {
            val each = left / order.size
            order.forEach { parts[it] += each }
            left -= each * order.size
        }
        var next = 0
        while (left > 0L) {
            parts[order[next++ % order.size]]++
            left--
        }
        while (left < 0L) {
            val most = parts.indices.maxBy { parts[it] }
            parts[most]--
            left++
        }
        return parts.toList()
    }

    /**
     * A day's [calls] older calls split over [parts] in whole calls: first by model, so a model on
     * several connections is not passed over for one with less on its own, then by connection.
     */
    internal fun olderCalls(calls: Long, parts: List<DayModel>): List<Long> {
        val groups = parts.indices.groupBy { normalize(parts[it].model) }.values.toList()
        val byModel = split(calls, groups.map { group -> group.sumOf { parts[it].share } })
        val out = LongArray(parts.size)
        groups.forEachIndexed { index, group ->
            split(byModel[index], group.map { parts[it].share }).forEachIndexed { at, part -> out[group[at]] = part }
        }
        return out.toList()
    }

    /**
     * [parts]' shares for their tokens: one that got none of [callsOf] hands its share to the
     * connection with the most calls on its model, or overall when its model got none, so no
     * tokens go where no call went.
     */
    internal fun tokenShares(parts: List<DayModel>, callsOf: List<Long>): List<Double> {
        val shares = DoubleArray(parts.size) { if (callsOf[it] > 0L) parts[it].share else 0.0 }
        val most = parts.indices.maxByOrNull { callsOf[it] } ?: return shares.toList()
        val models = parts.map { normalize(it.model) }
        // Each model's connection with the most calls.
        val busiest = HashMap<String, Int>()
        parts.indices.forEach { index ->
            if (callsOf[index] <= 0L) return@forEach
            val best = busiest[models[index]]
            if (best == null || callsOf[index] > callsOf[best]) busiest[models[index]] = index
        }
        parts.indices.forEach { index ->
            if (callsOf[index] <= 0L) shares[busiest[models[index]] ?: most] += parts[index].share
        }
        return shares.toList()
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
        class ModelSum(var name: String) {
            var cost = 0.0
            var tokens = 0L
            var calls = 0L
            var priced = 0L
            var estimated = 0L
        }
        val byModel = LinkedHashMap<String, ModelSum>()
        fun modelSum(model: String) = byModel.getOrPut(normalize(model)) { ModelSum(model) }

        // Share of input read from cache, by model, for calls that did not say: the app's ledger
        // for these days first, else every Orbit call on the model that reported it.
        val ownCacheRates by lazy {
            data.models.groupBy { normalize(it.model) }.mapNotNull { (model, rows) ->
                val known = rows.sumOf { it.cacheKnownInput }
                if (known <= 0L) return@mapNotNull null
                val rate = rows.sumOf { it.cached }.toDouble() / known
                if (rate.isFinite()) model to rate.coerceIn(0.0, 1.0) else null
            }.toMap()
        }
        fun cacheRateOf(model: String): Double =
            normalize(model).let { prices.cacheRates[it] ?: ownCacheRates[it] } ?: 0.0

        val rows = data.rows.filter { it.day >= from && it.day <= to }
        val olderDayModels = prices.olderDays.mapValues { (_, parts) ->
            parts.filter { it.share.isFinite() && it.share > 0.0 }
        }.filterValues { it.isNotEmpty() }
        // Older calls on a day the ledger shows models for are shared out a whole day at a time, below.
        val olderByDay = LinkedHashMap<String, MutableList<OrbitQuota.Usage>>()
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
            if (row.day in olderDayModels) {
                olderByDay.getOrPut(row.day) { mutableListOf() } += row
                return@forEach
            }
            val named = prices.older[OlderKey.of(row)]
            if (named == null) {
                unpriced += row.legacyCalls
                return@forEach
            }
            val sum = modelSum(named.model)
            sum.tokens += row.legacyTokens
            sum.calls += row.legacyCalls
            val value = older(row, named.price, cacheRateOf(named.model))?.let { amount(it) }
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
        olderByDay.forEach { (day, dayRows) ->
            val parts = olderDayModels[day] ?: return@forEach
            // Each model is priced for its own whole calls and its tokens, so prices per request
            // and price tiers go by what it got.
            val callsOf = olderCalls(dayRows.sumOf { it.legacyCalls }, parts)
            val shares = tokenShares(parts, callsOf)
            val tokens = dayRows.map { olderTokens(it) }
            val inputOf = split(tokens.sumOf { it.first }, shares)
            val outputOf = split(tokens.sumOf { it.second }, shares)
            // What the day's models cost by the token, and by the request.
            var byTokenValue = 0.0
            var byCallValue = 0.0
            var dayEstimated = false
            parts.forEachIndexed { index, part ->
                val partCalls = callsOf[index]
                if (partCalls <= 0L) return@forEachIndexed
                val sum = modelSum(part.model)
                sum.tokens += inputOf[index] + outputOf[index]
                sum.calls += partCalls
                val value = estimate(inputOf[index], outputOf[index], part.cacheRate ?: cacheRateOf(part.model),
                    partCalls, partCalls, part.price)?.let { amount(it) }
                if (value == null) {
                    // Listed under its model, with no cost.
                    unpriced += partCalls
                    return@forEachIndexed
                }
                estimated += value
                estimatedCalls += partCalls
                sum.cost += value
                sum.estimated += partCalls
                val perRequest = part.price.at(inputOf[index] / partCalls)?.billingMode == BillingMode.COUNT
                if (perRequest) byCallValue += value else byTokenValue += value
                dayEstimated = true
            }
            // The day's amount goes to its lines: what is paid by the request by their older calls,
            // what is paid by the token by their older tokens (by calls when none were counted).
            // Every line of a day with an estimate is estimated, even at nothing.
            if (dayEstimated) {
                val byCalls = dayRows.map { it.legacyCalls.toDouble() }
                val byTokens = dayRows.map { it.legacyTokens.toDouble() }
                val tokenWeights = if (byTokens.sum() > 0.0) byTokens else byCalls
                val callWeight = byCalls.sum()
                val tokenWeight = tokenWeights.sum()
                dayRows.forEachIndexed { index, line ->
                    addEstimate(lineOf(line), byCallValue * byCalls[index] / callWeight +
                        byTokenValue * tokenWeights[index] / tokenWeight)
                }
            }
        }

        val modelRows = data.models.filter { it.day >= from && it.day <= to }
        // Each amount is shared out over that day's lines of the same Orbit by their calls without a cost.
        val linesByDay by lazy { rows.groupBy { it.day to it.orbitId } }
        modelRows.forEach { row ->
            val sum = modelSum(row.model)
            // The name Orbit kept wins over the ledger's spelling for older calls.
            sum.name = row.model
            sum.cost += amount(row.costCny, row.costUsd)
            sum.tokens += row.tokens
            sum.calls += row.calls
            sum.priced += row.pricedCalls
            val missing = (row.calls - row.pricedCalls).coerceAtLeast(0L)
            if (missing == 0L) return@forEach
            val price = prices.models[row.model] ?: return@forEach
            // Only input that did not say what it read from cache needs the model's rate.
            val fallback = if (row.cacheKnownInput >= row.input) 0.0 else cacheRateOf(row.model)
            val value = backfill(row, price, fallback)?.let { amount(it) } ?: return@forEach
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

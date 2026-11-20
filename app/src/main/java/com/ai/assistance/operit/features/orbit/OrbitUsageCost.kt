package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.stats.PricingSource
import com.ai.assistance.operit.data.stats.ResolvedPricing
import com.ai.assistance.operit.data.stats.TokenCostCalculator
import com.ai.assistance.operit.data.stats.TokenCostCurrency
import com.ai.assistance.operit.data.stats.TokenPriceResolver
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenUsageInput
import com.ai.assistance.operit.util.AppLogger
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Orbit's use cost, for its usage page and its usage tool, so both always agree.
 *
 * Calls since costs were kept carry the cost the app's ledger gave them when they ended, at the
 * prices set for their model; those that got none (no price yet, or no usage reported) are priced
 * at today's price for their model when it has one, and shown as estimated. Older calls kept only
 * their tokens: they are estimated at that day's average price over the whole app's priced calls
 * (from the same ledger), and shown as estimated too.
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

    /** A day's average price over the app's priced calls: what a token cost, by price currency. */
    class DayPrice(val cnyPerToken: Double, val usdPerToken: Double)

    /** The last day prices read, by the past days asked for; they rarely change, so new use needs no new read. */
    private class PriceCache(val key: String, val readAt: Long, val prices: Map<String, DayPrice>)

    @Volatile private var priceCache: PriceCache? = null

    /** How long past days' prices are kept, so a cleared ledger shows within minutes. */
    private const val PAST_PRICE_TTL_MS = 10 * 60_000L

    /**
     * The average price of every day in [first]..[last] that has older (uncosted) Orbit use and
     * priced calls in the ledger. A ledger that cannot be read leaves those days unpriced.
     */
    suspend fun dayPrices(
        context: Context,
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
        zone: ZoneId,
    ): Map<String, DayPrice> {
        val from = first.toString()
        val to = last.toString()
        val days = data.rows.filter { it.legacyCalls > 0 && it.day >= from && it.day <= to }
            .mapNotNullTo(sortedSetOf()) { row -> runCatching { LocalDate.parse(row.day) }.getOrNull() }
        if (days.isEmpty()) return emptyMap()
        val today = LocalDate.now(zone)
        val key = zone.id + ":" + days.joinToString(",")
        val now = System.currentTimeMillis()
        priceCache?.let { cache ->
            if (cache.key == key && now - cache.readAt in 0 until PAST_PRICE_TTL_MS) return cache.prices
        }
        val start = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = days.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val rows = try {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context.applicationContext).tokenStatsDao().getDailyKnownCost(start, end)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read the ledger's prices by day: ${e.message}")
            return emptyMap()
        }
        val wanted = days.mapTo(HashSet()) { it.toString() }
        val prices = rows.filter { it.day in wanted }.groupBy { it.day }.mapNotNull { (day, list) ->
            val tokens = list.sumOf { it.tokens.takeIf { t -> t.isFinite() && t > 0.0 } ?: 0.0 }
            // A day whose sums overflowed is left unpriced, never estimated as free.
            if (tokens <= 0.0 || list.any { !it.cost.isFinite() || !it.tokens.isFinite() }) return@mapNotNull null
            fun sum(currency: PricingCurrency) = list.filter { it.currency.equals(currency.name, ignoreCase = true) }
                .sumOf { it.cost.takeIf { c -> c.isFinite() && c >= 0.0 } ?: 0.0 }
            day to DayPrice(sum(PricingCurrency.CNY) / tokens, sum(PricingCurrency.USD) / tokens)
        }.toMap()
        // Today still takes calls (this very call may not be in the ledger yet), so only past days are kept.
        if (today !in days) priceCache = PriceCache(key, now, prices)
        return prices
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

    /**
     * [ModelPrice] for each model with calls since costs were kept that got no cost when they ended
     * (no price yet, or no usage reported, so Orbit's own count is all there is).
     */
    suspend fun modelPrices(
        context: Context,
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
    ): Map<String, ModelPrice> {
        val from = first.toString()
        val to = last.toString()
        val wanted = data.models.filter { it.day >= from && it.day <= to && it.calls > it.pricedCalls }
            .mapTo(HashSet()) { it.model }
        if (wanted.isEmpty()) return emptyMap()
        return try {
            withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val legacy = ApiPreferences.getInstance(appContext).allLegacyPriceSettings()
                val dao = AppDatabase.getDatabase(appContext).tokenStatsDao()
                val identities = dao.getAllIdentities()
                val overrides = dao.getAllPriceOverrides()
                val byModel = identities.groupBy { TokenStatIdentityResolver.normalizeModelName(it.model) }
                wanted.associateWith { model ->
                    val connections = byModel[TokenStatIdentityResolver.normalizeModelName(model)].orEmpty()
                        .map { identity ->
                            val catalog = DefaultModelPricingCollect.getDefaultPricing(identity.providerModel)
                            val legacyPrice = legacy[identity.providerModel]
                            // By price tier, so calls of any size in the same tier share one answer.
                            val byTier = HashMap<Long, ResolvedPricing>()
                            val price: (Long?) -> ResolvedPricing = { tokens ->
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
                            price
                        }
                    ModelPrice(connections)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not read today's prices: ${e.message}")
            emptyMap()
        }
    }

    /**
     * What [row]'s calls without a cost come to at today's [price], in its currency, with its
     * currency. The row keeps its tokens for all its calls together, so a row with some priced
     * calls gets their share by count, and the price tier is picked by the average input per call.
     * Cache writes are not kept apart and count as ordinary input. Null when it cannot be worked out.
     */
    internal fun backfill(row: OrbitQuota.ModelUsage, price: ModelPrice): Pair<Double, PricingCurrency>? {
        val unpriced = (row.calls - row.pricedCalls).coerceAtLeast(0L)
        if (unpriced == 0L || row.calls <= 0L) return null
        val pricing = price.at(row.input.coerceAtLeast(0L) / row.calls) ?: return null
        val cached = row.cached.coerceIn(0L, row.input.coerceAtLeast(0L))
        val usage = TokenUsageInput(
            uncachedInputTokens = row.input.coerceAtLeast(0L) - cached,
            cachedInputTokens = cached,
            cacheWriteTokens = 0L,
            outputTokens = row.output.coerceAtLeast(0L),
            // Orbit's output already includes any reasoning.
            reasoningIncludedInOutput = true,
            cacheWriteSeparateBilling = false,
        )
        val amount = TokenCostCalculator.computeCost(usage, pricing).amount?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return null
        // A price per request is for one call; a price per token is for the whole row.
        val perCall = if (pricing.billingMode == BillingMode.COUNT) amount else amount / row.calls
        // The same limit as when calls are recorded, so a mistyped price stays unknown here too.
        if (!perCall.isFinite() || perCall > OrbitQuota.MAX_CALL_COST) return null
        val share = perCall * unpriced.toDouble()
        return if (share.isFinite()) share to pricing.currency else null
    }

    class ModelCost(
        val model: String,
        val cost: Double,
        val tokens: Long,
        val calls: Long,
        val unpricedCalls: Long,
        /** Calls priced at today's price because they got no cost when they ended. */
        val backfilledCalls: Long = 0L,
    )

    class View(
        val money: Money,
        /** Every call in the range. */
        val calls: Long,
        /** Known and estimated together, in [Money.currency]. */
        val total: Double,
        /** The part of [total] estimated for older calls. */
        val estimated: Double,
        /** Older calls with an estimate. */
        val estimatedCalls: Long,
        /** The part of [total] that is known, and the calls it covers. */
        val known: Double,
        val pricedCalls: Long,
        /** Calls since costs were kept whose cost could not be worked out, even at today's price: in no total. */
        val unpricedCalls: Long,
        /** Older calls on days the ledger has no prices for: in no total. */
        val unknownCalls: Long,
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
        /** The part of [total] for calls that got no cost when they ended, at today's price. */
        val backfilled: Double = 0.0,
        /** The calls [backfilled] covers. */
        val backfilledCalls: Long = 0L,
    ) {
        /** Calls whose cost is known or estimated. */
        val coveredCalls: Long get() = pricedCalls + estimatedCalls + backfilledCalls

        /** Some of [total] is an estimate, of either kind. */
        val approximate: Boolean get() = estimatedCalls > 0L || backfilledCalls > 0L
    }

    /** The cost of every Orbit's use from [first] to [last], both included. */
    fun view(
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
        orbitId: String,
        prices: Map<String, DayPrice>,
        money: Money,
        modelPrices: Map<String, ModelPrice> = emptyMap(),
    ): View {
        val from = first.toString()
        val to = last.toString()
        val other = if (money.currency == PricingCurrency.CNY) PricingCurrency.USD else PricingCurrency.CNY
        var converted = false
        fun amount(cny: Double, usd: Double): Double {
            if ((if (other == PricingCurrency.USD) usd else cny) > 0.0) converted = true
            return money.convert(cny, usd)
        }
        var known = 0.0
        var estimated = 0.0
        var calls = 0L
        var priced = 0L
        var estimatedCalls = 0L
        var unpriced = 0L
        var unknown = 0L
        val byLine = HashMap<String, Double>()
        val coveredLines = HashSet<String>()
        val estimatedLines = HashSet<String>()
        data.rows.filter { it.day >= from && it.day <= to }.forEach { row ->
            val key = if (row.orbitId == orbitId) row.purpose.name else "others"
            val rowKnown = amount(row.costCny, row.costUsd)
            known += rowKnown
            calls += row.calls
            priced += row.pricedCalls
            if (row.pricedCalls > 0) coveredLines += key
            unpriced += (row.trackedCalls - row.pricedCalls).coerceAtLeast(0L)
            var rowCost = rowKnown
            if (row.legacyCalls > 0L || row.legacyTokens > 0L) {
                val price = prices[row.day]
                if (price == null) {
                    unknown += row.legacyCalls
                } else {
                    val guess = amount(price.cnyPerToken * row.legacyTokens, price.usdPerToken * row.legacyTokens)
                    estimated += guess
                    estimatedCalls += row.legacyCalls
                    rowCost += guess
                    coveredLines += key
                    estimatedLines += key
                }
            }
            byLine[key] = (byLine[key] ?: 0.0) + rowCost
        }
        val modelRows = data.models.filter { it.day >= from && it.day <= to }
        // Calls that got no cost when they ended, at today's price. Each amount is shared out over
        // that day's lines of the same Orbit by their calls without a cost.
        val linesByDay by lazy { data.rows.filter { it.day >= from && it.day <= to }.groupBy { it.day to it.orbitId } }
        var backfilled = 0.0
        var backfilledCalls = 0L
        val rowBackfill = HashMap<OrbitQuota.ModelUsage, Double>()
        modelRows.forEach { row ->
            val price = modelPrices[row.model] ?: return@forEach
            val (raw, currency) = backfill(row, price) ?: return@forEach
            val value = if (currency == PricingCurrency.CNY) amount(raw, 0.0) else amount(0.0, raw)
            if (!value.isFinite()) return@forEach
            rowBackfill[row] = value
            backfilled += value
            backfilledCalls += (row.calls - row.pricedCalls).coerceAtLeast(0L)
            val lines = linesByDay[row.day to row.orbitId].orEmpty()
            val weights = lines.map { (it.trackedCalls - it.pricedCalls).coerceAtLeast(0L) }
            val weight = weights.sum()
            if (weight > 0L) lines.forEachIndexed { index, line ->
                if (weights[index] == 0L) return@forEachIndexed
                val key = if (line.orbitId == orbitId) line.purpose.name else "others"
                byLine[key] = (byLine[key] ?: 0.0) + value * weights[index] / weight
                coveredLines += key
                estimatedLines += key
            }
        }
        unpriced = (unpriced - backfilledCalls).coerceAtLeast(0L)
        val cacheInput = modelRows.sumOf { it.cacheKnownInput }
        val models = modelRows.groupBy { it.model }.map { (model, rows) ->
            val calls = rows.sumOf { it.calls }
            val filled = rows.filter { it in rowBackfill }
            val filledCalls = filled.sumOf { (it.calls - it.pricedCalls).coerceAtLeast(0L) }
            ModelCost(model, amount(rows.sumOf { it.costCny }, rows.sumOf { it.costUsd }) + filled.sumOf { rowBackfill[it]!! },
                rows.sumOf { it.tokens }, calls, (calls - rows.sumOf { it.pricedCalls } - filledCalls).coerceAtLeast(0L),
                filledCalls)
        }.sortedWith(compareByDescending<ModelCost> { it.cost }.thenByDescending { it.tokens })
        return View(
            money = money,
            calls = calls,
            total = known + estimated + backfilled,
            estimated = estimated,
            estimatedCalls = estimatedCalls,
            known = known,
            pricedCalls = priced,
            unpricedCalls = unpriced,
            unknownCalls = unknown,
            cacheRate = if (cacheInput > 0L) (modelRows.sumOf { it.cached }.toDouble() / cacheInput).coerceIn(0.0, 1.0) else null,
            models = models,
            byLine = byLine,
            coveredLines = coveredLines,
            estimatedLines = estimatedLines,
            converted = converted,
            backfilled = backfilled,
            backfilledCalls = backfilledCalls,
        )
    }

    /** Reads the money settings and the ledger's day prices, then works out [view], all off the main thread. */
    suspend fun compute(
        context: Context,
        data: OrbitQuota.UsageData,
        first: LocalDate,
        last: LocalDate,
        orbitId: String,
        zone: ZoneId,
    ): View = withContext(Dispatchers.Default) {
        val money = money(context)
        val prices = dayPrices(context, data, first, last, zone)
        val modelPrices = modelPrices(context, data, first, last)
        view(data, first, last, orbitId, prices, money, modelPrices)
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

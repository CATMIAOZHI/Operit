package com.ai.assistance.operit.data.api

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The subscription windows Operit knows how to display. */
enum class QuotaWindow { FIVE_HOUR, WEEKLY, MONTHLY }

/** One window exactly as the provider reports it, normalized to a 0–100 percentage. */
data class ProviderQuotaWindow(
    val window: QuotaWindow,
    val percent: Double,
    /** Rollover time, or null when the provider sent no usable clock (0 and negative are sentinels). */
    val resetAtEpochMillis: Long? = null,
)

/** Account-level quota for a subscription provider, as reported by that provider. */
data class ProviderQuota(
    val windows: List<ProviderQuotaWindow> = emptyList(),
    val creditsUsedUsd: Double? = null,
    val creditsLimitUsd: Double? = null,
    val creditsRemainingUsd: Double? = null,
) {
    val isEmpty: Boolean get() = windows.isEmpty() && creditsLimitUsd == null
}

/**
 * Shared normalization for provider quota payloads. Providers disagree on units and on how they
 * express "no reset"; these helpers are the single place that decides what a number means.
 */
internal object QuotaParsing {
    fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    /** Clamp provider-reported percentages: a few surfaces send values outside 0–100. */
    fun percent(value: Any?): Double? = number(value)?.coerceIn(0.0, 100.0)

    /**
     * Accepts epoch milliseconds, epoch seconds, or an ISO-8601 string. Unix 0 and negative values
     * are sentinels for "no reset" rather than a real clock (Command Code sends fiveHour.resetAt: 0).
     */
    fun resetAt(value: Any?): Long? {
        val direct = number(value)
        if (direct != null) return millis(direct)
        val text = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // RFC-3339 with a "Z" first; some providers emit a numeric offset instead of UTC.
        val parsed = runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: return null
        return millis(parsed.toDouble())
    }

    fun millis(value: Double): Long? {
        if (!value.isFinite() || value <= 0) return null
        return if (value > 10_000_000_000.0) value.toLong() else (value * 1000).toLong()
    }

    /** Rolling windows carry `{ cap, used, resetAt }`; percent is derived, not reported. */
    fun windowFromUsage(limit: JSONObject?): Pair<Double, Long?>? {
        val row = limit ?: return null
        val cap = number(row.opt("cap")) ?: return null
        val used = number(row.opt("used")) ?: return null
        if (cap <= 0 || used < 0) return null
        val percent = percent(used / cap * 100) ?: return null
        return percent to resetAt(row.opt("resetTime") ?: row.opt("resetAt"))
    }
}

/**
 * Command Code's billing rules, split away from the network calls so the two decisions that are
 * easy to get silently wrong — how `orgId` joins a query string, and when a USD figure is
 * meaningful at all — are directly testable.
 */
internal object CommandCodeQuota {
    data class Spend(val used: Double, val limit: Double, val remaining: Double)

    /** `orgId` is optional: an account without one still answers, just unscoped. Encoded by caller. */
    fun orgIdQuery(encodedOrgId: String?): String =
        encodedOrgId?.takeIf { it.isNotEmpty() }?.let { "?orgId=$it" }.orEmpty()

    /** Appends a query parameter to a URL that may or may not already carry one. */
    fun appendQuery(url: String, parameter: String): String =
        "$url${if (url.contains('?')) "&" else "?"}$parameter"

    /** Rolling windows carry `{ cap, used, resetAt }`; a malformed window is dropped, not zeroed. */
    fun windows(limits: JSONObject?): List<ProviderQuotaWindow> = listOf(
        QuotaWindow.FIVE_HOUR to limits?.optJSONObject("fiveHour"),
        QuotaWindow.WEEKLY to limits?.optJSONObject("weekly"),
    ).mapNotNull { (window, row) ->
        val parsed = QuotaParsing.windowFromUsage(row) ?: return@mapNotNull null
        ProviderQuotaWindow(window, parsed.first, parsed.second)
    }

    /**
     * Spend against the remaining credit pools. An unscoped usage summary is lifetime spend, so
     * "used" only has a denominator once a billing period start scopes the query; without one the
     * whole USD figure is omitted rather than measured against the wrong baseline.
     */
    fun spend(pools: JSONObject?, periodStart: String?, summary: JSONObject?): Spend? {
        if (pools == null) return null
        if (periodStart.isNullOrBlank()) return null
        val used = summary?.let {
            QuotaParsing.number(it.opt("totalCost")) ?: QuotaParsing.number(it.opt("totalMonthlyCredits"))
        } ?: return null
        if (used < 0) return null
        // A pool counts only when it carries a number: an exhausted all-zero account still reports
        // zero remaining, while a non-numeric or absent field means there is nothing to meter.
        val present = listOf("monthlyCredits", "purchasedCredits", "freeCredits")
            .mapNotNull { key -> QuotaParsing.number(pools.opt(key)) }
        if (present.isEmpty()) return null
        val remaining = present.sumOf { it.coerceAtLeast(0.0) }
        val limit = used + remaining
        // A fully spent period (used 0, pools 0) is still a real balance to report, not missing
        // data: the reference shows it against a 0 denominator rather than hiding the row.
        return Spend(used, limit, remaining)
    }
}

/**
 * Grok's billing rules. The weekly credits window is the meter that actually gates prompting, so it
 * is preferred; the legacy monthly dollar pool is the fallback when the weekly envelope is absent.
 */
internal object GrokQuota {
    private const val WEEKLY_PERIOD = "USAGE_PERIOD_TYPE_WEEKLY"

    /**
     * The weekly-credits call is scoped by the JWT subject. The stored account row has no such
     * field, so it is read from the access token itself; without it there is no weekly meter.
     */
    fun userIdFromAccessToken(accessToken: String): String? {
        val payload = accessToken.split('.').getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val decoded = try {
            java.util.Base64.getUrlDecoder().decode(padded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Read as a raw value, not optString: JSON null would stringify to "null" and be sent as a
        // user id, and a numeric subject is not a user id either.
        val subject = try {
            JSONObject(String(decoded, Charsets.UTF_8)).opt("sub") as? String
        } catch (_: Exception) {
            return null
        }
        return subject?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Weekly credits envelope: `{ config: { creditUsagePercent?, currentPeriod: { type, end } } }`.
     * An omitted percentage is the proto3 default, i.e. nothing used yet, but a period that is not
     * weekly carries no weekly meter at all.
     */
    fun weeklyCredits(body: JSONObject?): Pair<Double, Long?>? {
        val config = body?.optJSONObject("config") ?: return null
        val period = config.optJSONObject("currentPeriod") ?: return null
        if (period.optString("type") != WEEKLY_PERIOD) return null
        val resetAt = QuotaParsing.resetAt(period.opt("end")) ?: return null
        val percent = if (config.has("creditUsagePercent")) {
            QuotaParsing.percent(config.opt("creditUsagePercent")) ?: return null
        } else {
            0.0
        }
        return percent to resetAt
    }

    /** Legacy monthly pool: money arrives as `{ val: <cents> }`; a zero limit is not a meter. */
    fun monthlyDollars(body: JSONObject?): Pair<Double, Long?>? {
        val config = body?.optJSONObject("config") ?: return null
        val limit = cents(config.optJSONObject("monthlyLimit")) ?: return null
        val used = cents(config.optJSONObject("used")) ?: return null
        if (limit <= 0) return null
        val percent = QuotaParsing.percent(used / limit * 100) ?: return null
        return percent to QuotaParsing.resetAt(config.opt("billingPeriodEnd"))
    }

    private fun cents(value: JSONObject?): Double? = QuotaParsing.number(value?.opt("val"))
}

/**
 * OpenCode Go publishes the signed-in key's own subscription windows as JSON, so the app can show
 * usage without scraping the dashboard HTML or handling browser cookies.
 */
class OpenCodeGoQuotaClient(private val client: OkHttpClient = quotaHttpClient()) {
    suspend fun fetch(apiKey: String, endpoint: String): ProviderQuota {
        val url = usageUrl(endpoint) ?: throw IOException("Unexpected OpenCode Go endpoint")
        val json = get(url.toString(), apiKey)
        val usage = json.optJSONObject("usage") ?: throw IOException("OpenCode Go usage is missing")
        // The wire calls the 5-hour window "rolling"; the other two are already named for their span.
        val windows = listOf(
            QuotaWindow.FIVE_HOUR to usage.optJSONObject("rolling"),
            QuotaWindow.WEEKLY to usage.optJSONObject("weekly"),
            QuotaWindow.MONTHLY to usage.optJSONObject("monthly"),
        ).mapNotNull { (window, row) ->
            val percent = QuotaParsing.percent(row?.opt("percent")) ?: return@mapNotNull null
            ProviderQuotaWindow(window, percent, QuotaParsing.resetAt(row.opt("resetsAt")))
        }
        if (windows.isEmpty()) throw IOException("OpenCode Go reported no quota windows")
        return ProviderQuota(windows)
    }

    private suspend fun get(url: String, apiKey: String): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                continuation.resumeWithException(error)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val json = response.use {
                        if (!it.isSuccessful) throw AccountHttpException(it.code)
                        val source = it.body?.source() ?: throw IOException("Empty quota response")
                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw IOException("Quota response too large")
                        try { JSONObject(source.readUtf8()) }
                        catch (_: JSONException) { throw IOException("Invalid quota response") }
                    }
                    continuation.resume(json)
                } catch (error: Exception) { continuation.resumeWithException(error) }
            }
        })
    }

    companion object {
        private const val QUOTA_PATH = "/zen/go/v1/usage"
        private const val MAX_RESPONSE_BYTES = 512L * 1024

        /**
         * Rewrites any Go endpoint under the official host into the usage endpoint. Returns null for
         * anything else so a user-edited host never receives the configured key.
         */
        fun usageUrl(endpoint: String): HttpUrl? {
            val parsed = endpoint.trim().toHttpUrlOrNull() ?: return null
            if (parsed.scheme != "https" || parsed.host != "opencode.ai") return null
            // Ports other than the https default are not the provider we validated against.
            if (parsed.port != 443) return null
            // Matches what the request path itself accepts, so a config that can chat can also meter.
            if (parsed.encodedPath != "/zen/go" && !parsed.encodedPath.startsWith("/zen/go/")) return null
            return parsed.newBuilder().encodedPath(QUOTA_PATH).query(null).fragment(null).build()
        }
    }
}

/**
 * Shared OkHttp client for quota probes; short timeout because these are best-effort reads.
 * Redirects are refused rather than followed: a quota probe carries a live API key, so it must not
 * be replayed to whatever host a redirect names.
 */
internal fun quotaHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

package com.ai.assistance.operit.core.tools

import android.content.Context
import android.content.res.Configuration
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitQuota
import com.ai.assistance.operit.ui.features.orbit.usageFirstDay
import com.ai.assistance.operit.ui.features.orbit.usageViewBetween
import com.ai.assistance.operit.ui.features.orbit.usageWeekStart
import com.ai.assistance.operit.util.LocaleUtils
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the numbers of Orbit's usage page: the allowances, and for a range the requests, tokens,
 * busiest hours, busiest day, active days, streak, use by purpose and, if asked, each day's use.
 * It only reads; it is worked out by the same code as the page, so both always agree.
 */
internal object OrbitUsageTools {
    const val NAME = "orbit_usage"

    /** Longer custom ranges are refused: ten years of days is already far more than any record. */
    private const val MAX_RANGE_DAYS = 3660L
    /** At most this many days are listed with daily=true; the newest are kept. */
    private const val MAX_DAILY_ROWS = 400

    val prompt = ToolPrompt(
        name = NAME,
        description = "Read your own token usage, the same numbers as the usage page in settings. " +
            "查看你自己用了多少 token：每周/额外额度，以及某段时间的请求次数、累计 token、请求高峰时段、峰值那天、活跃天数、连续天数、各用途用量和每天用量。只读。",
        parametersStructured = listOf(
            ToolParameterSchema("range", description =
                "today / yesterday / week (this allowance week, from Monday) / 30d / 90d / all / a day YYYY-MM-DD / a span YYYY-MM-DD..YYYY-MM-DD. Default week",
                required = false),
            ToolParameterSchema("daily", "boolean", "true also lists each day with use in the range (newest $MAX_DAILY_ROWS at most). Default false", false),
        ),
        details = """
  - The figures are every Orbit's model calls counted by the app (chat, side chats, subagents, background tasks, memory, titles, reviews and so on); tokens = input + output. They match Settings → Usage, where 30d and 90d end today.
  - allowance is always the current week: weekly_used/limit/left, extra_used/limit/left, resets_at (local time) and paused. Token amounts are raw tokens.
  - peak_hours are the busiest hours of the day in the range, busiest first (at most 3), with their request counts. peak_day is the day with the most tokens in the range. streak_days counts days in a row with use up to today, whatever the range.
  - by_purpose lists what the tokens went to, most first; "other_orbits" is use by Orbits other than you.
  - For one day's use, pass range=YYYY-MM-DD. Days before the first record or after today have no use.
""",
    )

    private fun textContext(context: Context): Context =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale(if (LocaleUtils.usesEnglishPrompt(context)) "en" else "zh"))
        })

    private class Span(val label: String, val first: LocalDate, val last: LocalDate)

    private fun span(raw: String, today: LocalDate, weekStart: LocalDate, firstRecord: LocalDate?): Span {
        val value = raw.trim().lowercase(Locale.ROOT).ifEmpty { "week" }
        fun date(text: String): LocalDate = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("\"$text\" is not a date; write it as YYYY-MM-DD, e.g. ${today}")
        return when (value) {
            "today" -> Span(value, today, today)
            "yesterday" -> today.minusDays(1).let { Span(value, it, it) }
            "week" -> Span(value, weekStart.coerceAtMost(today), today)
            "30d" -> Span(value, today.minusDays(29), today)
            "90d" -> Span(value, today.minusDays(89), today)
            "all" -> Span(value, (firstRecord ?: today).coerceAtMost(today), today)
            else -> {
                val parts = value.split("..")
                require(parts.size in 1..2) {
                    "range must be today, yesterday, week, 30d, 90d, all, YYYY-MM-DD or YYYY-MM-DD..YYYY-MM-DD; got \"$raw\""
                }
                val first = date(parts[0])
                val last = if (parts.size == 2) date(parts[1]) else first
                require(!first.isAfter(last)) { "The start ${first} is after the end ${last}" }
                require(!first.isAfter(today)) { "${first} is in the future; today is ${today}" }
                val end = last.coerceAtMost(today)
                require(ChronoUnit.DAYS.between(first, end) < MAX_RANGE_DAYS) {
                    "That span is longer than $MAX_RANGE_DAYS days; use range=all or a shorter span"
                }
                Span(if (first == end) "day" else "span", first, end)
            }
        }
    }

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext {
        try {
            val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
            val args = tool.parameters.associate { it.name to it.value.trim() }
            val dailyRaw = args["daily"].orEmpty().lowercase(Locale.ROOT)
            require(dailyRaw.isEmpty() || dailyRaw == "true" || dailyRaw == "false") { "daily must be true or false; got \"$dailyRaw\"" }
            val daily = dailyRaw == "true"
            val text = textContext(context)
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val state = OrbitQuota.state(context)
            val data = OrbitQuota.usageFlow(context).first()
            // "all" starts at the oldest day on record, counting days older versions kept only as a total.
            val oldestTotal = data.totals.filterValues { it > 0 }.keys
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.minOrNull()
            val firstRecord = listOfNotNull(usageFirstDay(data), oldestTotal).minOrNull()
            val span = span(args["range"].orEmpty(), today, usageWeekStart(state.weekStart, zone), firstRecord)
            val view = usageViewBetween(text, data, span.first, span.last, orbit.id,
                text.getString(R.string.orbit_usage_other_orbits), zone)
            val input = view.days.sumOf { it.input }
            val output = view.days.sumOf { it.output }
            val peak = view.days.filter { it.input + it.output > 0 }.maxByOrNull { it.input + it.output }
            val result = JSONObject()
                .put("range", JSONObject().put("name", span.label).put("from", span.first.toString())
                    .put("to", span.last.toString()).put("days", view.days.size))
                .put("allowance", JSONObject()
                    .put("weekly_used", state.weeklyUsed).put("weekly_limit", state.weeklyLimit).put("weekly_left", state.weeklyLeft)
                    .put("extra_used", state.extraUsed).put("extra_limit", state.extraLimit).put("extra_left", state.extraLeft)
                    .put("extra_resets_weekly", state.extraWeekly)
                    .put("resets_at", Instant.ofEpochMilli(state.weekEnd).atZone(zone).toLocalDateTime().toString())
                    .put("paused", state.paused))
                .put("requests", view.calls)
                .put("tokens", JSONObject().put("total", input + output).put("input", input).put("output", output))
                .put("peak_hours", JSONArray().apply {
                    view.topHours.forEach { hour ->
                        put(JSONObject().put("hours", String.format(Locale.ROOT, "%02d:00-%02d:00", hour, (hour + 1) % 24))
                            .put("requests", view.callsByHour.getOrElse(hour) { 0L }))
                    }
                })
                .put("peak_day", peak?.let { JSONObject().put("date", it.date.toString()).put("tokens", it.input + it.output) }
                    ?: JSONObject.NULL)
                .put("active_days", view.activeDays)
                .put("streak_days", view.streak)
                .put("by_purpose", JSONArray().apply {
                    view.lines.forEach { line ->
                        put(JSONObject().put("purpose", if (line.key == "others") "other_orbits" else line.key.lowercase(Locale.ROOT))
                            .put("label", line.label).put("tokens", line.tokens).put("requests", line.calls))
                    }
                })
            if (daily) {
                val used = view.days.filter { it.calls > 0 || it.input + it.output > 0 }
                val kept = used.takeLast(MAX_DAILY_ROWS)
                result.put("daily", JSONArray().apply {
                    kept.forEach { day ->
                        put(JSONObject().put("date", day.date.toString()).put("tokens", day.input + day.output)
                            .put("input", day.input).put("output", day.output).put("requests", day.calls))
                    }
                })
                if (kept.size < used.size) {
                    result.put("daily_note", "Only the newest ${kept.size} of ${used.size} days with use are listed; ask for a shorter range for older ones.")
                }
            }
            // Older versions kept only a token total for some days; those count on the heatmap but
            // have no requests, input/output split or purposes, so they are reported apart.
            val detailedDays = data.rows.mapTo(HashSet()) { it.day }
            val from = span.first.toString()
            val to = span.last.toString()
            val totalsOnly = data.totals.filter { (day, tokens) -> tokens > 0 && day >= from && day <= to && day !in detailedDays }
                .toSortedMap()
            if (totalsOnly.isNotEmpty()) {
                result.put("total_only_days", JSONObject()
                    .put("days", totalsOnly.size)
                    .put("tokens", totalsOnly.values.sum())
                    .put("list", JSONArray().apply {
                        totalsOnly.entries.toList().takeLast(MAX_DAILY_ROWS).forEach { (day, tokens) ->
                            put(JSONObject().put("date", day).put("tokens", tokens))
                        }
                    })
                    .put("note", "Days an older version kept only as a token total. They are not in requests, tokens, peak_day or by_purpose above; their requests, input/output and purposes are unknown."))
            }
            if (view.calls == 0L && view.total == 0L && totalsOnly.isEmpty()) result.put("note", "No use was recorded in this range.")
            ToolResult(NAME, true, StringResultData(result.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
        }
    }
}

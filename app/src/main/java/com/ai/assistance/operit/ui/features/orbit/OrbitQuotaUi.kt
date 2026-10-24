package com.ai.assistance.operit.ui.features.orbit

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.stats.TokenActivityAggregator
import com.ai.assistance.operit.data.stats.TokenActivityRecord
import com.ai.assistance.operit.data.stats.TokenActivityViewMode
import com.ai.assistance.operit.data.stats.TokenActivityYearData
import com.ai.assistance.operit.data.stats.TokenStatsCostMode
import com.ai.assistance.operit.data.stats.TokenStatsCostSummary
import com.ai.assistance.operit.data.stats.TokenStatsDurationAggregate
import com.ai.assistance.operit.data.stats.TokenStatsGranularity
import com.ai.assistance.operit.data.stats.TokenStatsPerformance
import com.ai.assistance.operit.data.stats.TokenStatsTokenAggregate
import com.ai.assistance.operit.data.stats.TokenStatsTotals
import com.ai.assistance.operit.data.stats.TokenStatsTrendBucket
import com.ai.assistance.operit.features.orbit.OrbitQuota
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.tokenstats.LocalTokenStatsColors
import com.ai.assistance.operit.ui.features.tokenstats.TokenActivityHeatmap
import com.ai.assistance.operit.ui.features.tokenstats.TokenActivityUiState
import com.ai.assistance.operit.ui.features.tokenstats.TokenStatsColorsProvider
import com.ai.assistance.operit.ui.features.tokenstats.TokenStatsLineChart
import com.ai.assistance.operit.ui.features.tokenstats.TokenStatsStackedBarChart
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Today's date and time zone, kept current while the page is shown: read again at midnight, on
 * coming back to the page and at least hourly, so a new day or another zone redoes what hangs on it.
 */
private data class Today(val date: LocalDate, val zone: ZoneId)

@Composable
private fun rememberToday(): Today {
    var today by remember { mutableStateOf(ZoneId.systemDefault().let { Today(LocalDate.now(it), it) }) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val zone = ZoneId.systemDefault()
                val now = Today(LocalDate.now(zone), zone)
                today = now
                val next = now.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                delay((next - System.currentTimeMillis() + 1_000L).coerceIn(1_000L, 3_600_000L))
            }
        }
    }
    return today
}

/**
 * The allowances, starting from the last ones read so the card opens with real numbers, and seen
 * again each new day, so a page left open over Monday shows the new week without waiting for a call.
 */
@Composable
private fun rememberQuotaState(): OrbitQuota.State? {
    val context = LocalContext.current
    val flow = remember { OrbitQuota.stateFlow(context) }
    // A cached state from last week starts over now, so Monday never opens on last week's bars.
    val state by flow.collectAsState(initial = OrbitQuota.lastState?.at(System.currentTimeMillis()))
    val today = rememberToday()
    return remember(state, today) { state?.at(System.currentTimeMillis()) }
}

/** The usage by day, parsed off the main thread; null until the first read. */
@Composable
private fun rememberQuotaUsage(): OrbitQuota.UsageData? {
    val context = LocalContext.current
    val flow = remember { OrbitQuota.usageFlow(context) }
    val usage by flow.collectAsState(initial = null as OrbitQuota.UsageData?)
    return usage
}

/** "已使用 5%"; a little use never shows as 0%, and an allowance of nothing shows as off. */
@Composable
private fun usedLabel(used: Long, limit: Long): String {
    if (limit <= 0L) return stringResource(R.string.orbit_quota_extra_off)
    val percent = (used.toDouble() * 100 / limit).coerceIn(0.0, 100.0)
    val shown = when {
        used > 0 && percent < 1 -> "<1"
        else -> percent.toInt().toString()
    }
    return stringResource(R.string.orbit_quota_used_percent, shown)
}

@Composable
private fun QuotaBar(used: Long, limit: Long) {
    val fraction = if (limit <= 0L) 0f else (used.toDouble() / limit).toFloat().coerceIn(0f, 1f)
    val full = limit > 0L && used >= limit
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = if (full) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun QuotaHeading(title: String, subtitle: String, trailing: String, info: String) {
    var explaining by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false))
                // The small dot beside the name says what this allowance is for.
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.orbit_quota_what_is)) {
                            explaining = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(orbitIcon("info"), contentDescription = stringResource(R.string.orbit_quota_what_is_named, title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End, modifier = Modifier.padding(top = 6.dp))
    }
    if (explaining) {
        OrbitInfoDialog(title = title, message = info, onDismiss = { explaining = false })
    }
}

/**
 * The allowance at the top of Orbit's settings, like Muse's plan card: the week's allowance and
 * the extra one, each with a bar, then the usage page and the place to change them.
 */
@Composable
internal fun OrbitQuotaCard(
    orbit: OrbitProfile,
    cardColor: androidx.compose.ui.graphics.Color,
    onOpenUsage: () -> Unit,
) {
    val context = LocalContext.current
    val loaded = rememberQuotaState()
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var resetting by rememberSaveable { mutableStateOf(false) }
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        // Before the very first read the card keeps its shape with blank numbers, so nothing jumps
        // and no made-up 0% flashes.
        val state = loaded ?: OrbitQuota.State(weeklyLimit = 0L, extraLimit = 0L).at(System.currentTimeMillis())
        val ready = loaded != null
        Column(Modifier.padding(horizontal = 4.dp)) {
            QuotaHeading(
                title = stringResource(R.string.orbit_quota_weekly),
                subtitle = stringResource(R.string.orbit_quota_weekly_reset, OrbitQuota.resetText(context, state.weekEnd)),
                trailing = if (ready) usedLabel(state.weeklyUsed, state.weeklyLimit) else "",
                info = stringResource(R.string.orbit_quota_weekly_info, orbitName(orbit)),
            )
            Spacer(Modifier.height(12.dp))
            QuotaBar(state.weeklyUsed, state.weeklyLimit)
            Spacer(Modifier.height(20.dp))
            QuotaHeading(
                title = stringResource(R.string.orbit_quota_extra),
                subtitle = if (!ready) "" else if (state.extraWeekly)
                    stringResource(R.string.orbit_quota_extra_weekly_reset, OrbitQuota.resetText(context, state.weekEnd))
                else stringResource(R.string.orbit_quota_extra_never_expires),
                trailing = if (!ready) "" else if (state.extraLimit <= 0L) usedLabel(0, 0) else
                    usedLabel(state.extraUsed, state.extraLimit) + stringResource(
                        R.string.orbit_quota_left, OrbitQuota.amount(context, state.extraLeft)),
                info = stringResource(R.string.orbit_quota_extra_info, orbitName(orbit)),
            )
            Spacer(Modifier.height(12.dp))
            QuotaBar(state.extraUsed, state.extraLimit)
            // The line below the bars is always there, so the card does not grow when it changes.
            val status = when {
                !ready -> ""
                state.paused -> stringResource(R.string.orbit_quota_status_paused, orbitName(orbit))
                state.weeklyExhausted -> stringResource(R.string.orbit_quota_status_extra)
                else -> stringResource(R.string.orbit_quota_status_ok, OrbitQuota.amount(context, state.weeklyLeft))
            }
            Spacer(Modifier.height(12.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, minLines = 1,
                color = when {
                    state.paused -> MaterialTheme.colorScheme.error
                    state.weeklyExhausted -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
        }
        Spacer(Modifier.height(12.dp))
        SettingsRowDivider(inset = false)
        SettingsNavRow(
            title = stringResource(R.string.orbit_quota_usage),
            subtitle = stringResource(R.string.orbit_quota_usage_desc, orbitName(orbit)),
            icon = orbitIcon("chart-column"),
            searchable = false,
            onClick = onOpenUsage,
        )
        SettingsRowDivider(inset = false)
        SettingsNavRow(
            title = stringResource(R.string.orbit_quota_adjust),
            subtitle = if (ready) stringResource(R.string.orbit_quota_adjust_desc,
                OrbitQuota.amount(context, state.weeklyLimit), OrbitQuota.amount(context, state.extraLimit)) else " ",
            icon = orbitIcon("sliders-horizontal"),
            searchable = false,
            onClick = { adjusting = true },
        )
        SettingsRowDivider(inset = false)
        // The extra allowance can be filled again at any time, not only once it has run out.
        SettingsNavRow(
            title = stringResource(R.string.orbit_quota_reset_extra),
            subtitle = if (!ready) " " else if (state.extraUsed > 0L)
                stringResource(R.string.orbit_quota_reset_extra_desc, OrbitQuota.amount(context, state.extraUsed))
            else stringResource(R.string.orbit_quota_reset_extra_none),
            icon = orbitIcon("rotate-ccw"),
            searchable = false,
            onClick = { if (ready) resetting = true },
        )
        if (adjusting && ready) {
            OrbitQuotaSheet(state = state, orbitName = orbitName(orbit), onDismiss = { adjusting = false })
        }
        if (resetting && ready) {
            OrbitDialog(
                onDismissRequest = { resetting = false },
                title = stringResource(R.string.orbit_quota_reset_extra),
                text = {
                    Text(if (state.extraUsed > 0L)
                        stringResource(R.string.orbit_quota_reset_extra_confirm, OrbitQuota.amount(context, state.extraUsed))
                    else stringResource(R.string.orbit_quota_reset_extra_none))
                },
                confirmButton = {
                    OrbitDialogAction(onClick = {
                        resetting = false
                        // Runs past the dialog, which closes at once.
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { OrbitQuota.refillExtra(context) }
                    }) { Text(stringResource(R.string.orbit_quota_reset_extra_action)) }
                },
                dismissButton = { OrbitDialogAction(onClick = { resetting = false }, secondary = true) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}

/**
 * Changes both allowances. Amounts are typed in 万 (or millions in English), the unit people use for
 * numbers this big; the week's allowance cannot go under its floor and the extra one can be 0.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OrbitQuotaSheet(state: OrbitQuota.State, orbitName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // The same strings that name the unit say its size, so every language types what it reads.
    val unit = remember { OrbitQuota.unitSize(context) }
    fun inUnits(value: Long) = (value / unit).toString()
    var weekly by rememberSaveable { mutableStateOf(inUnits(state.weeklyLimit)) }
    var extra by rememberSaveable { mutableStateOf(inUnits(state.extraLimit)) }
    // A field the user never touched keeps the exact amount, even one that is not a whole unit.
    var weeklyEdited by rememberSaveable { mutableStateOf(false) }
    var extraEdited by rememberSaveable { mutableStateOf(false) }
    var extraWeekly by rememberSaveable { mutableStateOf(state.extraWeekly) }
    val maxUnits = OrbitQuota.MAX_LIMIT / unit
    val weeklyValue = if (!weeklyEdited) state.weeklyLimit
        else weekly.toLongOrNull()?.takeIf { it in 0..maxUnits }?.times(unit)
    val extraValue = if (!extraEdited) state.extraLimit
        else extra.toLongOrNull()?.takeIf { it in 0..maxUnits }?.times(unit)
    val weeklyOk = weeklyValue != null && weeklyValue >= OrbitQuota.MIN_WEEKLY
    val extraOk = extraValue != null
    OrbitModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls, and rises above the keyboard, so Save stays reachable on a small screen or large text.
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.orbit_quota_adjust), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.orbit_quota_adjust_help, orbitName), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = weekly,
                onValueChange = { weekly = it.filter(Char::isDigit).take(9); weeklyEdited = true },
                label = { Text(stringResource(R.string.orbit_quota_weekly_field)) },
                suffix = { Text(stringResource(R.string.orbit_quota_unit)) },
                supportingText = {
                    Text(
                        if (weeklyOk) "= " + OrbitQuota.amount(context, weeklyValue!!)
                        else stringResource(R.string.orbit_quota_weekly_min, OrbitQuota.amount(context, OrbitQuota.MIN_WEEKLY)),
                        color = if (weeklyOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                },
                isError = !weeklyOk,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = extra,
                onValueChange = { extra = it.filter(Char::isDigit).take(9); extraEdited = true },
                label = { Text(stringResource(R.string.orbit_quota_extra_field)) },
                suffix = { Text(stringResource(R.string.orbit_quota_unit)) },
                supportingText = {
                    Text(
                        when {
                            !extraOk -> stringResource(R.string.orbit_quota_extra_invalid)
                            extraValue == 0L -> stringResource(R.string.orbit_quota_extra_zero)
                            else -> "= " + OrbitQuota.amount(context, extraValue!!)
                        },
                        color = if (extraOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                },
                isError = !extraOk,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.orbit_quota_extra_mode), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.selectableGroup()) {
                ExtraModeRow(
                    selected = !extraWeekly,
                    title = stringResource(R.string.orbit_quota_extra_mode_keep),
                    description = stringResource(R.string.orbit_quota_extra_mode_keep_desc),
                    onClick = { extraWeekly = false },
                )
                ExtraModeRow(
                    selected = extraWeekly,
                    title = stringResource(R.string.orbit_quota_extra_mode_weekly),
                    description = stringResource(R.string.orbit_quota_extra_mode_weekly_desc),
                    onClick = { extraWeekly = true },
                )
            }
            Button(
                enabled = weeklyOk && extraOk,
                onClick = {
                    val w = weeklyValue ?: return@Button
                    val e = extraValue ?: return@Button
                    val weeklyReset = extraWeekly
                    onDismiss()
                    // Closing the sheet ends its scope; saving runs past it.
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        OrbitQuota.setLimits(context, w, e, weeklyReset)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text(stringResource(R.string.save)) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable
private fun ExtraModeRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private enum class UsageRange(@StringRes val label: Int, val days: Int?) {
    WEEK(R.string.orbit_quota_range_week, null),
    MONTH(R.string.orbit_quota_range_30, 30),
    ALL(R.string.orbit_quota_range_90, OrbitQuota.KEEP_DAYS),
}

@StringRes
private fun purposeLabel(purpose: OrbitQuota.Purpose): Int = when (purpose) {
    OrbitQuota.Purpose.CHAT -> R.string.orbit_usage_chat
    OrbitQuota.Purpose.SUBAGENT -> R.string.orbit_usage_subagent
    OrbitQuota.Purpose.TASK -> R.string.orbit_usage_task
    OrbitQuota.Purpose.PROACTIVE -> R.string.orbit_usage_proactive
    OrbitQuota.Purpose.ALIGNMENT -> R.string.orbit_usage_alignment
    OrbitQuota.Purpose.FEED -> R.string.orbit_usage_feed
    OrbitQuota.Purpose.IDEAS -> R.string.orbit_usage_ideas
    OrbitQuota.Purpose.GOALS -> R.string.orbit_usage_goals
    OrbitQuota.Purpose.SELF_REVIEW -> R.string.orbit_usage_self_review
    OrbitQuota.Purpose.MEMORY -> R.string.orbit_usage_memory
    OrbitQuota.Purpose.REVIEW -> R.string.orbit_usage_review
    OrbitQuota.Purpose.SUMMARY -> R.string.orbit_usage_summary
    OrbitQuota.Purpose.TITLE -> R.string.orbit_usage_title
    OrbitQuota.Purpose.OTHER -> R.string.orbit_usage_other
}

private fun purposeIcon(purpose: OrbitQuota.Purpose): String = when (purpose) {
    OrbitQuota.Purpose.CHAT -> "message-circle"
    OrbitQuota.Purpose.SUBAGENT -> "users"
    OrbitQuota.Purpose.TASK -> "list-checks"
    OrbitQuota.Purpose.PROACTIVE -> "radar"
    OrbitQuota.Purpose.ALIGNMENT -> "moon"
    OrbitQuota.Purpose.FEED -> "newspaper"
    OrbitQuota.Purpose.IDEAS -> "lightbulb"
    OrbitQuota.Purpose.GOALS -> "target"
    OrbitQuota.Purpose.SELF_REVIEW -> "sparkles"
    OrbitQuota.Purpose.MEMORY -> "brain"
    OrbitQuota.Purpose.REVIEW -> "shield-check"
    OrbitQuota.Purpose.SUMMARY -> "archive"
    OrbitQuota.Purpose.TITLE -> "heading"
    OrbitQuota.Purpose.OTHER -> "circle-ellipsis"
}

private class UsageLine(val key: String, val label: String, val icon: String, val tokens: Long, val calls: Long)

/** One day of the chart: everything every Orbit spent that day. */
private class UsageDay(val date: LocalDate, val input: Long, val output: Long, val calls: Long)

private class UsageView(
    val lines: List<UsageLine>,
    val total: Long,
    val calls: Long,
    /** Every day of the range, oldest first, days without use as zeros. */
    val days: List<UsageDay>,
    /** The busiest hours of the day in the range, busiest first; at most three. */
    val topHours: List<Int>,
    val peakDayTokens: Long,
    val activeDays: Int,
    /** Days in a row with use, up to today (or yesterday, before today's first call). */
    val streak: Int,
)

private fun usageView(
    context: android.content.Context,
    data: OrbitQuota.UsageData,
    range: UsageRange,
    /** The allowance's stored week start; its Monday is found in [zone], the same zone as the chart. */
    weekStart: Long,
    orbitId: String,
    otherLabel: String,
    zone: ZoneId,
): UsageView {
    val today = LocalDate.now(zone)
    val first = range.days?.let { today.minusDays((it - 1).toLong()) }
        ?: Instant.ofEpochMilli(weekStart + 2 * 86_400_000L).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val from = first.toString()
    val inRange = data.rows.filter { it.day >= from }
    val mine = inRange.filter { it.orbitId == orbitId }
    val others = inRange.filter { it.orbitId != orbitId }
    val lines = buildList {
        mine.groupBy { it.purpose }.forEach { (purpose, rows) ->
            add(UsageLine(purpose.name, context.getString(purposeLabel(purpose)), purposeIcon(purpose),
                rows.sumOf { it.tokens }, rows.sumOf { it.calls }))
        }
        if (others.isNotEmpty()) add(UsageLine("others", otherLabel, "bot", others.sumOf { it.tokens }, others.sumOf { it.calls }))
    }.sortedByDescending { it.tokens }
    val byDay = inRange.groupBy { it.day }
    val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { date ->
        val rows = byDay[date.toString()].orEmpty()
        UsageDay(date, rows.sumOf { it.input }, rows.sumOf { it.output }, rows.sumOf { it.calls })
    }.toList()
    val perHour = LongArray(24)
    data.hours.filter { it.day >= from }.forEach { h -> h.calls.forEachIndexed { i, n -> if (i < 24) perHour[i] += n } }
    val topHours = (0 until 24).filter { perHour[it] > 0 }
        .sortedWith(compareByDescending<Int> { perHour[it] }.thenBy { it }).take(3)
    val used = data.rows.filter { it.tokens > 0 || it.calls > 0 }.map { it.day }.toSet()
    var cursor = if (today.toString() in used) today else today.minusDays(1)
    var streak = 0
    while (cursor.toString() in used) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return UsageView(
        lines = lines,
        total = lines.sumOf { it.tokens },
        calls = lines.sumOf { it.calls },
        days = days,
        topHours = topHours,
        peakDayTokens = days.maxOfOrNull { it.input + it.output } ?: 0L,
        activeDays = days.count { it.calls > 0 || it.input + it.output > 0 },
        streak = streak,
    )
}

private fun aggregate(value: Long, events: Long) = TokenStatsTokenAggregate(value, events, 0L, events)

private val NO_COST = TokenStatsCostSummary(PricingCurrency.CNY, 0.0, 0L, 0L, 1.0, false,
    TokenStatsCostMode.HISTORICAL, emptyMap())

private val NO_TIME = TokenStatsDurationAggregate(0L, 0L, 0L, 0.0)

/** The days as the main app's charts take them: one daily bucket each, input and output only. */
private fun chartBuckets(days: List<UsageDay>, zone: ZoneId): List<TokenStatsTrendBucket> {
    return days.map { day ->
        val start = day.date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        TokenStatsTrendBucket(
            bucketStartMs = start,
            bucketEndMs = end,
            totals = TokenStatsTotals(
                requests = day.calls,
                uncachedInput = aggregate(day.input, day.calls),
                cachedInput = aggregate(0L, day.calls),
                cacheWrite = aggregate(0L, day.calls),
                totalInput = aggregate(day.input, day.calls),
                output = aggregate(day.output, day.calls),
                reasoning = aggregate(0L, day.calls),
                totalTokens = aggregate(day.input + day.output, day.calls),
                cost = NO_COST,
            ),
            byModel = emptyMap(),
            performance = TokenStatsPerformance(NO_TIME, NO_TIME),
        )
    }
}

/**
 * Orbit's own usage page: its picture and name, then activity insights like the main app's (total
 * requests, busiest hours), the main app's activity heatmap, a way into the trend charts, and what
 * the tokens went to.
 */
@Composable
internal fun OrbitUsagePage(orbit: OrbitProfile, onBack: () -> Unit, onOpenTrends: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val loaded = rememberQuotaState()
    val usage = rememberQuotaUsage()
    var range by rememberSaveable { mutableStateOf(UsageRange.WEEK) }
    val state = loaded ?: OrbitQuota.State().at(System.currentTimeMillis())
    val otherLabel = stringResource(R.string.orbit_usage_other_orbits)
    val today = rememberToday()
    val zone = today.zone
    // Grouping is redone only when the usage, the range, the week, the day or the zone changes, not on every frame.
    val view = remember(usage, range, state.weekStart, orbit.id, otherLabel, today) {
        usageView(context, usage ?: OrbitQuota.UsageData(emptyList(), emptyList()), range, state.weekStart,
            orbit.id, otherLabel, zone)
    }
    var heatmapMode by rememberSaveable { mutableStateOf(TokenActivityViewMode.DAILY) }
    val heatmapData = remember(usage, today) { usage?.let { heatmapYear(it, today) } }
    val heatmap = TokenActivityUiState(
        loading = heatmapData == null,
        viewMode = heatmapMode,
        recentSelected = true,
        selectedYear = today.date.year,
        yearData = heatmapData,
    )
    val ready = usage != null
    val cardColor = rememberOrbitCardColor()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The picture sits right under the title bar, without the page's usual gap.
            Column(Modifier.fillMaxWidth()) {
                OrbitPageHeader(stringResource(R.string.orbit_quota_usage), onBack, Modifier.padding(start = 4.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OrbitAvatar(orbit, 64)
                    Text(orbitName(orbit), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Blank (but keeping its line) until the allowances are read, so no made-up 0 shows.
                    Text(
                        if (loaded == null) " " else stringResource(R.string.orbit_usage_summary_line,
                            OrbitQuota.amount(context, state.weeklyUsed), OrbitQuota.amount(context, state.weeklyLimit),
                            OrbitQuota.amount(context, state.extraUsed), OrbitQuota.amount(context, state.extraLimit)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            UsageInsightsCard(view, ready, range, cardColor, onRange = { range = it })
            UsageHeatmapCard(heatmap, today.zone, cardColor, onMode = { heatmapMode = it })
            UsageTrendsRow(cardColor, onOpenTrends)
            Text(stringResource(R.string.orbit_usage_by_purpose), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
            SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                val lines = view.lines
                // Nothing is said before the usage is read, so "no usage yet" never flashes.
                if (lines.isEmpty() && ready) {
                    Text(stringResource(R.string.orbit_usage_empty), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
                val top = lines.maxOfOrNull { it.tokens }?.coerceAtLeast(1L) ?: 1L
                lines.forEachIndexed { index, line ->
                    if (index > 0) SettingsRowDivider(inset = false)
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center) {
                            Icon(orbitIcon(line.icon), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(OrbitQuota.amount(context, line.tokens), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                            }
                            LinearProgressIndicator(
                                progress = { (line.tokens.toDouble() / top).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                gapSize = 0.dp,
                                drawStopIndicator = {},
                            )
                            Text(stringResource(R.string.orbit_usage_calls, line.calls), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(stringResource(R.string.orbit_usage_footer), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/**
 * The main app's activity insights for the chosen range: total requests, the busiest hours, then
 * tokens in all, the busiest day, active days and the current streak. The range is picked here.
 */
@Composable
private fun UsageInsightsCard(
    view: UsageView,
    ready: Boolean,
    range: UsageRange,
    cardColor: androidx.compose.ui.graphics.Color,
    onRange: (UsageRange) -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.token_activity_insights), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box {
                Text(
                    stringResource(range.label) + " ▾",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.DropdownList) { menu = true }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                OrbitDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    UsageRange.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(stringResource(option.label),
                                    fontWeight = if (option == range) FontWeight.Bold else FontWeight.Normal)
                            },
                            onClick = {
                                menu = false
                                onRange(option)
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.token_activity_total_requests), style = MaterialTheme.typography.bodyMedium,
                color = muted, modifier = Modifier.weight(1f))
            Text(if (ready) view.calls.toString() else "–", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.token_activity_peak_hours), style = MaterialTheme.typography.bodyMedium, color = muted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { index ->
                val hour = view.topHours.getOrNull(index)
                Text(
                    if (!ready || hour == null) "–"
                    else stringResource(R.string.token_activity_hour_range, hour, (hour + 1) % 24),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageStat(stringResource(R.string.token_activity_total_tokens),
                    if (ready) OrbitQuota.amount(context, view.total) else "–", Modifier.weight(1f))
                UsageStat(stringResource(R.string.token_activity_peak_tokens),
                    if (ready) OrbitQuota.amount(context, view.peakDayTokens) else "–", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageStat(stringResource(R.string.orbit_usage_active_days),
                    if (ready) stringResource(R.string.token_activity_days, view.activeDays) else "–", Modifier.weight(1f))
                UsageStat(stringResource(R.string.token_activity_current_streak),
                    if (ready) stringResource(R.string.token_activity_days, view.streak) else "–", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UsageStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Holds a chart at its full drawn height (legend and detail included, at any text size) whether or
 * not it has anything to show: before the usage is read, or with none in the range, the chart is
 * drawn invisible and silent, with [note] over it, so the card never changes height.
 */
@Composable
private fun ChartSlot(shown: Boolean, note: String?, chart: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Box(if (shown) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}) { chart() }
        if (!shown) {
            // Covers the hidden chart so its taps and drags do nothing.
            Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
            if (note != null) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
/** A chart in the page's own card, titled like the main app's: the name, and the range's total on the right. */
@Composable
private fun UsageChartCard(
    title: String,
    summary: String,
    cardColor: androidx.compose.ui.graphics.Color,
    content: @Composable () -> Unit,
) {
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(summary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = LocalTokenStatsColors.current.chartAccent)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}


@Composable
private fun rememberOrbitCardColor() =
    com.ai.assistance.operit.ui.features.settings.components.settingsCardColor(false)

/**
 * The main app's activity heatmap data for the last year: every Orbit's tokens by day, from the
 * year-long day totals, with the 90 days of detailed use taking over the days they cover (the
 * totals only started being kept with this version).
 */
private fun heatmapYear(data: OrbitQuota.UsageData, today: Today): TokenActivityYearData {
    val byDay = HashMap<String, Long>(data.totals)
    data.rows.groupBy { it.day }.forEach { (day, rows) -> byDay[day] = rows.sumOf { it.tokens } }
    val records = byDay.mapNotNull { (day, tokens) ->
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@mapNotNull null
        TokenActivityRecord(date.atTime(12, 0).atZone(today.zone).toInstant().toEpochMilli(), tokens)
    }
    return TokenActivityAggregator.recentData(records, today.zone)
}

/** The main app's activity heatmap: the last year by day, by week, or added up, in one card. */
@Composable
private fun UsageHeatmapCard(
    state: TokenActivityUiState,
    zone: ZoneId,
    cardColor: androidx.compose.ui.graphics.Color,
    onMode: (TokenActivityViewMode) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.orbit_usage_heatmap), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.orbit_usage_heatmap_range), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TokenActivityViewMode.entries.forEach { mode ->
                val selected = state.viewMode == mode
                Text(
                    stringResource(when (mode) {
                        TokenActivityViewMode.DAILY -> R.string.token_activity_daily
                        TokenActivityViewMode.WEEKLY -> R.string.token_activity_weekly
                        TokenActivityViewMode.CUMULATIVE -> R.string.token_activity_cumulative
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .selectable(selected = selected, role = Role.Tab) { onMode(mode) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TokenActivityHeatmap(state = state, zone = zone, locale = locale,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The way into the trend charts, which have a page of their own. */
@Composable
private fun UsageTrendsRow(cardColor: androidx.compose.ui.graphics.Color, onOpen: () -> Unit) {
    SettingsCard(backgroundColor = cardColor, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
        SettingsNavRow(
            title = stringResource(R.string.orbit_usage_trends),
            subtitle = stringResource(R.string.orbit_usage_trends_desc),
            icon = orbitIcon("chart-line"),
            searchable = false,
            onClick = onOpen,
        )
    }
}

/** The main app's token and request trend charts, by day, for this week or the last 30 or 90 days. */
@Composable
internal fun OrbitUsageTrendsPage(orbit: OrbitProfile, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val loaded = rememberQuotaState()
    val usage = rememberQuotaUsage()
    var range by rememberSaveable { mutableStateOf(UsageRange.WEEK) }
    val state = loaded ?: OrbitQuota.State().at(System.currentTimeMillis())
    val otherLabel = stringResource(R.string.orbit_usage_other_orbits)
    val today = rememberToday()
    val zone = today.zone
    val view = remember(usage, range, state.weekStart, orbit.id, otherLabel, today) {
        usageView(context, usage ?: OrbitQuota.UsageData(emptyList(), emptyList()), range, state.weekStart,
            orbit.id, otherLabel, zone)
    }
    // The charts always draw every day of the range, so they keep their full height while loading
    // or empty; they are then hidden behind a note instead of shown as made-up zeros.
    val buckets = remember(view, zone) { chartBuckets(view.days, zone) }
    val hasData = view.days.any { it.calls > 0 || it.input + it.output > 0 }
    val ready = usage != null
    val cardColor = rememberOrbitCardColor()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp).navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OrbitPageHeader(stringResource(R.string.orbit_usage_trends), onBack, Modifier.padding(start = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageRange.entries.forEach { option ->
                    FilterChip(selected = range == option, onClick = { range = option },
                        label = { Text(stringResource(option.label)) })
                }
            }
            TokenStatsColorsProvider {
                val colors = LocalTokenStatsColors.current
                val inputLabel = stringResource(R.string.orbit_usage_input)
                val outputLabel = stringResource(R.string.token_stats_token_output)
                val emptyText = stringResource(R.string.token_stats_no_data_in_range)
                val tokensTitle = stringResource(R.string.token_stats_chart_tokens)
                UsageChartCard(tokensTitle, if (ready) OrbitQuota.amount(context, view.total) else "–", cardColor) {
                    ChartSlot(shown = ready && hasData, note = if (ready) emptyText else null) {
                        TokenStatsStackedBarChart(
                        buckets = buckets,
                        granularity = TokenStatsGranularity.DAILY,
                        zone = zone,
                        formatValue = { OrbitQuota.amount(context, it.toLong()) },
                        emptyText = emptyText,
                        chartLabel = tokensTitle,
                        stackSelector = { bucket ->
                            listOf(
                                bucket.totals.output.knownSum.toDouble() to colors.output,
                                bucket.totals.totalInput.knownSum.toDouble() to colors.uncachedInput,
                            )
                        },
                        stackLabels = { listOf(outputLabel, inputLabel) },
                        stackTotalSelector = { bucket -> bucket.totals.totalTokens.knownSum.toDouble() },
                        legendItems = listOf(outputLabel to colors.output, inputLabel to colors.uncachedInput),
                        )
                    }
                }
                val requestsTitle = stringResource(R.string.token_stats_chart_requests)
                UsageChartCard(requestsTitle, if (ready) view.calls.toString() else "–", cardColor) {
                    ChartSlot(shown = ready && hasData, note = if (ready) emptyText else null) {
                        TokenStatsLineChart(
                        buckets = buckets,
                        granularity = TokenStatsGranularity.DAILY,
                        zone = zone,
                        formatValue = { it.toLong().toString() },
                        emptyText = emptyText,
                        chartLabel = requestsTitle,
                        valueSelector = { it.totals.requests.toDouble() },
                        )
                    }
                }
            }
        }
    }
}

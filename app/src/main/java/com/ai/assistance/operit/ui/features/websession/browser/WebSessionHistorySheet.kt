package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionHistoryEntry
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionSessionHistoryItem
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.delay

/** How long "Clear" waits for the second tap that confirms it. */
private const val CLEAR_CONFIRM_MS = 3_000L

private data class HistoryDay(val start: Long, val entries: List<WebSessionHistoryEntry>)

/**
 * The browsing history: a search box, the pages of the tab in front (to jump back or forward),
 * then every visit grouped by day. Each visit can be removed; clearing everything takes a second tap.
 */
@Composable
internal fun WebSessionHistorySheet(
    sessionHistory: List<WebSessionSessionHistoryItem>,
    globalHistory: List<WebSessionHistoryEntry>,
    onSelectSessionHistory: (Int) -> Unit,
    onOpenHistoryUrl: (String) -> Unit,
    onClearHistory: () -> Unit,
    onRemoveEntry: (WebSessionHistoryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(CLEAR_CONFIRM_MS)
            confirmClear = false
        }
    }
    val needle = query.trim()
    // Each visit's day is worked out once per history change, not on every key typed.
    val dated = remember(globalHistory) { globalHistory.map { dayStart(it.visitedAt) to it } }
    val days = remember(dated, needle) {
        val shown = if (needle.isEmpty()) dated else dated.filter { (_, entry) ->
            entry.title.contains(needle, ignoreCase = true) || entry.url.contains(needle, ignoreCase = true)
        }
        shown.groupBy({ it.first }, { it.second }).map { (start, entries) -> HistoryDay(start, entries) }
    }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val today = dayStart(System.currentTimeMillis())
    val yesterday = dayStart(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis)
    val todayLabel = stringResource(R.string.web_session_history_today)
    val yesterdayLabel = stringResource(R.string.web_session_history_yesterday)

    Column(modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).fillMaxWidth(0.12f).height(4.dp)
                .background(colors.outlineVariant.copy(alpha = 0.8f), CircleShape)
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.web_session_history),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            if (globalHistory.isNotEmpty()) {
                TextButton(onClick = {
                    if (confirmClear) {
                        confirmClear = false
                        onClearHistory()
                    } else {
                        confirmClear = true
                    }
                }) {
                    Text(
                        stringResource(
                            if (confirmClear) R.string.web_session_history_clear_confirm else R.string.web_session_history_clear_all
                        ),
                        color = if (confirmClear) colors.error else colors.primary,
                    )
                }
            }
        }
        HistorySearchField(query, onQueryChange = { query = it })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)) {
            // The tab's own pages, for going back or forward several at once.
            if (needle.isEmpty() && sessionHistory.size > 1) {
                item(key = "tab-title") { DayTitle(stringResource(R.string.web_session_history_this_tab)) }
                items(sessionHistory, key = { "tab-${it.index}" }) { item ->
                    HistoryRow(
                        url = item.url,
                        title = item.title,
                        detail = if (item.isCurrent) stringResource(R.string.web_session_current_page) else siteHost(item.url),
                        highlighted = item.isCurrent,
                        onClick = { onSelectSessionHistory(item.index) },
                        onRemove = null,
                    )
                }
            }
            days.forEach { day ->
                item(key = "day-${day.start}") {
                    DayTitle(
                        when (day.start) {
                            today -> todayLabel
                            yesterday -> yesterdayLabel
                            else -> dateFormat.format(Date(dayMillis(day.start)))
                        }
                    )
                }
                items(day.entries, key = { "visit-${it.url}-${it.visitedAt}" }) { entry ->
                    HistoryRow(
                        url = entry.url,
                        title = entry.title,
                        detail = siteHost(entry.url) + " · " + timeFormat.format(Date(entry.visitedAt)),
                        highlighted = false,
                        onClick = { onOpenHistoryUrl(entry.url) },
                        onRemove = { onRemoveEntry(entry) },
                    )
                }
            }
            if (days.isEmpty()) {
                item(key = "empty") {
                    WebSessionEmptyState(
                        icon = if (needle.isEmpty()) Icons.Filled.History else Icons.Filled.Search,
                        title = stringResource(
                            if (needle.isEmpty()) R.string.web_session_no_history else R.string.web_session_history_no_match
                        ),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(stringResource(R.string.web_session_history_search), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = stringResource(R.string.web_session_clear_input),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun HistoryRow(
    url: String,
    title: String,
    detail: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (highlighted) colors.primaryContainer.copy(alpha = 0.45f) else colors.surface)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 0.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SiteAvatar(url, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                title.ifBlank { siteHost(url) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlighted) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.web_session_history_remove),
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Box(Modifier.size(12.dp))
        }
    }
}

/** A local calendar day as one number (year × 1000 + day of the year), for grouping visits. */
private fun dayStart(millis: Long): Long {
    val calendar = Calendar.getInstance().apply { timeInMillis = millis }
    return calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR)
}

private fun dayMillis(day: Long): Long =
    Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, (day / 1000).toInt())
        set(Calendar.DAY_OF_YEAR, (day % 1000).toInt())
    }.timeInMillis

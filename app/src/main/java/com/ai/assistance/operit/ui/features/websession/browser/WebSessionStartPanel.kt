package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionBookmark
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.WebSessionHistoryEntry

private const val TILE_COLUMNS = 4
private const val TILE_COUNT = 8
private const val MATCH_COUNT = 8

private data class SiteLink(val url: String, val title: String)

/**
 * What the page area shows while the address is typed, and on a blank tab: with text, a search or
 * open row and the matching bookmarks and history; without, the user's bookmarks and recent sites.
 */
@Composable
internal fun WebSessionStartPanel(
    query: String,
    history: List<WebSessionHistoryEntry>,
    bookmarks: List<WebSessionBookmark>,
    onSubmit: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = query.trim()
    val matches = remember(text, history, bookmarks) {
        if (text.isEmpty()) emptyList() else {
            // Stops at the first few matches instead of going through every visit.
            (bookmarks.asSequence().map { SiteLink(it.url, it.title) } + history.asSequence().map { SiteLink(it.url, it.title) })
                .filter { it.url.contains(text, ignoreCase = true) || it.title.contains(text, ignoreCase = true) }
                .distinctBy { it.url }
                .take(MATCH_COUNT)
                .toList()
        }
    }
    val favourites = remember(bookmarks) { bookmarks.take(TILE_COUNT).map { SiteLink(it.url, it.title) } }
    val recent = remember(history) {
        history.distinctBy { siteHost(it.url) }.take(TILE_COUNT).map { SiteLink(it.url, it.title) }
    }
    val colors = MaterialTheme.colorScheme
    // Covers the page: taps here never reach it.
    Box(
        modifier.fillMaxSize().background(colors.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (text.isNotEmpty()) {
                item(key = "go") {
                    val isAddress = navigationTargetIsAddress(text)
                    SuggestionRow(
                        leading = {
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    if (isAddress) Icons.Filled.Language else Icons.Filled.Search,
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        title = stringResource(if (isAddress) R.string.web_session_open_site else R.string.web_session_search_for, text),
                        subtitle = null,
                        onClick = onSubmit,
                    )
                }
                items(matches, key = { "match-${it.url}" }) { link ->
                    SuggestionRow(
                        leading = { SiteAvatar(link.url, size = 32.dp) },
                        title = link.title.ifBlank { siteHost(link.url) },
                        subtitle = link.url,
                        onClick = { onOpenUrl(link.url) },
                    )
                }
            } else if (favourites.isEmpty() && recent.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.web_session_start_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp)
                    )
                }
            } else {
                if (favourites.isNotEmpty()) {
                    item(key = "fav-title") { PanelTitle(stringResource(R.string.web_session_bookmarks)) }
                    item(key = "fav") { SiteTiles(favourites, onOpenUrl) }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent-title") { PanelTitle(stringResource(R.string.web_session_recent_history)) }
                    item(key = "recent") { SiteTiles(recent, onOpenUrl) }
                }
            }
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun SiteTiles(links: List<SiteLink>, onOpenUrl: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        links.chunked(TILE_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { link ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onOpenUrl(link.url) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SiteAvatar(link.url, size = 48.dp)
                        Text(
                            link.title.ifBlank { siteHost(link.url) },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(TILE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

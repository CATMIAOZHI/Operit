package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.UserscriptPageMenuCommand

/**
 * The browser menu: the four places (history, bookmarks, downloads, scripts) as tiles on top, then
 * the page's settings and the closing actions as plain rows.
 */
@Composable
internal fun WebSessionMenuSheet(
    isDesktopMode: Boolean,
    activeDownloadCount: Int,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenUserscripts: () -> Unit,
    userscriptMenuCommands: List<UserscriptPageMenuCommand>,
    onInvokeUserscriptMenu: (String) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onCloseCurrentTab: () -> Unit,
    onCloseAllTabs: () -> Unit,
    modifier: Modifier = Modifier
) {
    WebSessionSheetScaffold(title = null, modifier = modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuTile(Icons.Filled.History, stringResource(R.string.web_session_history), onOpenHistory)
            MenuTile(Icons.Filled.Bookmark, stringResource(R.string.web_session_bookmarks), onOpenBookmarks)
            MenuTile(Icons.Filled.Download, stringResource(R.string.web_session_downloads), onOpenDownloads, badge = activeDownloadCount)
            MenuTile(Icons.Filled.Extension, stringResource(R.string.web_session_scripts_short), onOpenUserscripts)
        }
        if (userscriptMenuCommands.isNotEmpty()) {
            WebSessionSectionLabel(
                text = stringResource(R.string.web_session_userscript_page_menu),
                modifier = Modifier.padding(top = 4.dp)
            )
            Column {
                userscriptMenuCommands.forEach { command ->
                    MenuRow(Icons.Filled.Extension, command.title, onClick = { onInvokeUserscriptMenu(command.commandId) })
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column {
            MenuRow(
                icon = Icons.Filled.DesktopWindows,
                title = stringResource(R.string.web_session_desktop_site),
                subtitle = stringResource(R.string.web_session_desktop_site_desc),
                onClick = onToggleDesktopMode,
                trailing = { Switch(checked = isDesktopMode, onCheckedChange = { onToggleDesktopMode() }) },
            )
            MenuRow(Icons.Filled.Close, stringResource(R.string.web_session_close_current_tab), onClick = onCloseCurrentTab)
            MenuRow(
                Icons.Filled.DeleteSweep,
                stringResource(R.string.web_session_close_all_tabs),
                onClick = onCloseAllTabs,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RowScope.MenuTile(icon: ImageVector, label: String, onClick: () -> Unit, badge: Int = 0) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.weight(1f),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    tint: Color = Color.Unspecified,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurface else tint
        Icon(icon, contentDescription = null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) Box { trailing() }
    }
}

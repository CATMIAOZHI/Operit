package com.ai.assistance.operit.ui.features.websession.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R

/** Back, forward, new tab, the tab count and the menu, evenly spaced along the bottom. */
@Composable
internal fun WebSessionBottomToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    tabCount: Int,
    canCreateTab: Boolean = true,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onNewTab: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.web_session_back), onBack, canGoBack)
            ToolbarIcon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.web_session_forward), onForward, canGoForward)
            val newTab = stringResource(R.string.web_session_new_tab)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onNewTab,
                    enabled = canCreateTab,
                    shape = RoundedCornerShape(18.dp),
                    color =
                        MaterialTheme.colorScheme.primaryContainer.copy(
                            alpha = if (canCreateTab) 1f else 0.38f
                        ),
                    contentColor =
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(
                            alpha = if (canCreateTab) 1f else 0.38f
                        ),
                    modifier = Modifier.size(width = 56.dp, height = 40.dp).semantics { contentDescription = newTab },
                ) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Add, contentDescription = null) }
                }
            }
            val tabs = stringResource(R.string.web_session_tabs)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = onTabs,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(44.dp).semantics { contentDescription = tabs },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // A small rounded square with the number of tabs in it.
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.8.dp, MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.size(22.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (tabCount > 99) ":)" else tabCount.coerceAtLeast(0).toString(),
                                    fontSize = if (tabCount > 9) 10.sp else 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
            ToolbarIcon(Icons.Filled.MoreHoriz, stringResource(R.string.web_session_menu), onMenu, true)
        }
    }
}

@Composable
private fun RowScope.ToolbarIcon(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
            modifier = Modifier.size(44.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
        }
    }
}

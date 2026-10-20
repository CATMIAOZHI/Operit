package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import com.ai.assistance.operit.ui.permissions.isOrbitInternalTool
import com.ai.assistance.operit.ui.permissions.orbitDefaultPermissionLevel
import com.ai.assistance.operit.ui.permissions.orbitPermissionLevel
import kotlinx.coroutines.launch

@Immutable
private class ToolFolder(val key: String, val title: String, val icon: String, val tools: List<String>)

/** The three levels a tool can have in Orbit's chats; Orbit never waits on a prompt. */
private val OrbitLevels = listOf(PermissionLevel.ALLOW, PermissionLevel.AUTO_REVIEW, PermissionLevel.FORBID)

/**
 * Orbit's own tool permissions, a developer page. They apply to Orbit's chats and its background
 * tasks only and never touch the main app's tool permissions, nor the other way round. Orbit's own
 * tools start always allowed; every other tool starts on the fast automatic review.
 */
@Composable
internal fun OrbitToolPermissionsPage(onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val system = remember { ToolPermissionSystem.getInstance(context) }
    val toolHandler = remember { AIToolHandler.getInstance(context) }
    val stored by system.orbitToolPermissionOverridesFlow.collectAsState(initial = null)
    val fullAccess by system.orbitFullAccessFlow.collectAsState(initial = false)
    var query by remember { mutableStateOf("") }
    // The internal folder starts open: it is what this page is mostly about.
    var opened by remember { mutableStateOf(setOf("internal")) }
    var picking by remember { mutableStateOf<String?>(null) }
    val internalTitle = stringResource(R.string.orbit_tools_folder_internal)
    val builtinTitle = stringResource(R.string.orbit_tools_folder_builtin)
    val storedKeys = stored?.keys
    val folders = remember(storedKeys, internalTitle, builtinTitle) {
        val names = (toolHandler.getAllToolNames().filterNot {
            it == "package_proxy" || it == "proxy" || it == "search" || it in PermissionReviewInternalTools.names
        } + storedKeys.orEmpty()).distinct().sortedBy { it.lowercase() }
        val internal = names.filter(::isOrbitInternalTool)
        val rest = names.filterNot(::isOrbitInternalTool)
        val byPackage = rest.filter { ':' in it }.groupBy { it.substringBefore(':') }.toSortedMap()
        (listOf(
            ToolFolder("internal", internalTitle, "sparkles", internal),
            ToolFolder("builtin", builtinTitle, "wrench", rest.filter { ':' !in it }),
        ) + byPackage.map { (pkg, tools) -> ToolFolder("pkg:$pkg", pkg, "package", tools) })
            .filter { it.tools.isNotEmpty() }
    }
    fun levelOf(name: String): PermissionLevel =
        orbitPermissionLevel(stored?.get(name), orbitDefaultPermissionLevel(name, fullAccess))

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.orbit_tools_title), onBack)
            Text(
                stringResource(R.string.orbit_tools_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp),
            )
            SearchPill(query, { query = it }, stringResource(R.string.orbit_tools_search))
            Spacer(Modifier.height(16.dp))
        }
        val levels = stored
        if (levels == null) {
            item(key = "loading") { OrbitDelayedSpinner() }
            return@LazyColumn
        }
        val needle = query.trim().lowercase()
        val searching = needle.isNotEmpty()
        val shown = if (!searching) folders else folders.mapNotNull { folder ->
            val tools = if (folder.title.lowercase().contains(needle)) folder.tools
                else folder.tools.filter { it.lowercase().contains(needle) }
            tools.takeIf { it.isNotEmpty() }?.let { ToolFolder(folder.key, folder.title, folder.icon, it) }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.orbit_tools_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
        shown.forEach { folder ->
            val open = searching || folder.key in opened
            item(key = folder.key) {
                FolderSlice(first = true, last = !open) {
                    ToolFolderHeader(folder, changed = folder.tools.count { it in levels }, open = open, enabled = !searching) {
                        opened = if (folder.key in opened) opened - folder.key else opened + folder.key
                    }
                }
            }
            if (open) {
                itemsIndexed(folder.tools, key = { _, name -> folder.key + "/" + name }) { index, name ->
                    FolderSlice(first = false, last = index == folder.tools.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        ToolRow(name, levelOf(name), isDefault = name !in levels) { picking = name }
                    }
                }
            }
            item(key = folder.key + "/gap") { Spacer(Modifier.height(12.dp)) }
        }
    }

    picking?.let { name ->
        val default = orbitDefaultPermissionLevel(name, fullAccess)
        LevelSheet(
            toolName = name,
            current = levelOf(name),
            default = default,
            changed = stored?.containsKey(name) == true,
            onDismiss = { picking = null },
            onPick = { level ->
                picking = null
                scope.launch {
                    // Picking the default stores nothing, so the tool keeps following it.
                    if (level == null || level == default) system.clearOrbitToolPermission(name)
                    else system.saveOrbitToolPermission(name, level)
                }
            },
        )
    }
}

@Composable
private fun levelLabel(level: PermissionLevel): String = stringResource(
    when (level) {
        PermissionLevel.ALLOW -> R.string.orbit_tools_level_allow
        PermissionLevel.FORBID -> R.string.orbit_tools_level_forbid
        else -> R.string.orbit_tools_level_review
    }
)

@Composable
private fun levelColor(level: PermissionLevel): Color = when (level) {
    PermissionLevel.ALLOW -> MaterialTheme.colorScheme.primary
    PermissionLevel.FORBID -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun ToolRow(name: String, level: PermissionLevel, isDefault: Boolean, onClick: () -> Unit) {
    val label = levelLabel(level)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (isDefault) stringResource(R.string.orbit_tools_level_default, label) else label,
            style = MaterialTheme.typography.labelLarge,
            color = levelColor(level),
            fontWeight = if (isDefault) FontWeight.Normal else FontWeight.SemiBold,
        )
        Icon(orbitIcon("chevron-right"), contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToolFolderHeader(folder: ToolFolder, changed: Int, open: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing),
        label = "toolFolderChevron")
    val openedText = stringResource(R.string.orbit_packages_folder_opened)
    val closedText = stringResource(R.string.orbit_packages_folder_closed)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (enabled) Modifier.semantics { stateDescription = if (open) openedText else closedText } else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Icon(orbitIcon(folder.icon), contentDescription = null, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (changed > 0) stringResource(R.string.orbit_tools_count_changed, folder.tools.size, changed)
                else stringResource(R.string.orbit_tools_count, folder.tools.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (enabled) {
            Icon(orbitIcon("chevron-down"), contentDescription = null, modifier = Modifier.size(20.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Orbit's tool access, the choice in Orbit's settings: the fast automatic review, or full access
 * where every tool runs without a review. Orbit never waits on a prompt in either. A page pushed
 * from the settings, like an iOS choice list, so it leaves with the back swipe.
 */
@Composable
internal fun OrbitToolAccessPage(onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val system = remember { ToolPermissionSystem.getInstance(context) }
    val stored by system.orbitFullAccessFlow.collectAsState(initial = null)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OrbitPageHeader(stringResource(R.string.orbit_access_title), onBack)
            Text(stringResource(R.string.orbit_access_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            val fullAccess = stored ?: return@Column
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                Column {
                    listOf(false, true).forEachIndexed { index, full ->
                        if (index > 0) HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        val selected = full == fullAccess
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .clickable(role = Role.RadioButton) { scope.launch { system.setOrbitFullAccess(full) } }
                                .semantics { this.selected = selected }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(orbitAccessLabel(full)), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium)
                                Text(
                                    stringResource(if (full) R.string.orbit_access_full_desc else R.string.orbit_access_review_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(orbitIcon("check"), contentDescription = null, modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun orbitAccessLabel(fullAccess: Boolean): Int =
    if (fullAccess) R.string.orbit_access_full else R.string.orbit_access_review

/** The level choice for one tool, as an iOS-style sheet: three choices and, if changed, a reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LevelSheet(
    toolName: String,
    current: PermissionLevel,
    default: PermissionLevel,
    changed: Boolean,
    onDismiss: () -> Unit,
    onPick: (PermissionLevel?) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(toolName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.orbit_tools_sheet_default, levelLabel(default)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
                Column {
                    OrbitLevels.forEachIndexed { index, level ->
                        if (index > 0) HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        val selected = level == current
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp)
                                .clickable(role = Role.RadioButton) { onPick(level) }
                                .semantics { this.selected = selected }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(levelLabel(level), style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium, color = levelColor(level).takeIf { level != PermissionLevel.AUTO_REVIEW }
                                        ?: MaterialTheme.colorScheme.onSurface)
                                Text(
                                    stringResource(
                                        when (level) {
                                            PermissionLevel.ALLOW -> R.string.orbit_tools_level_allow_desc
                                            PermissionLevel.FORBID -> R.string.orbit_tools_level_forbid_desc
                                            else -> R.string.orbit_tools_level_review_desc
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected) {
                                Icon(orbitIcon("check"), contentDescription = null, modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
            if (changed) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onPick(null) },
                ) {
                    Text(
                        stringResource(R.string.orbit_tools_reset, levelLabel(default)),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

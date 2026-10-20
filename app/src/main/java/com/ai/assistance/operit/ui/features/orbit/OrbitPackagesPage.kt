package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
private class PackageRow(
    val name: String,
    val title: String,
    val description: String,
)

/** One folder of packages: a category of the main app, or one plugin with its subpackages. */
@Immutable
private class PackageFolder(val key: String, val title: String, val icon: String, val rows: List<PackageRow>)

private class PackagesState(val folders: List<PackageFolder>, val enabled: Set<String>, val mainCount: Int) {
    val total = folders.sumOf { it.rows.size }
}

/** The main app's package categories, in its order. */
private val CATEGORY_ORDER = listOf("Automatic", "Experimental", "Draw", "Other")

private fun categoryIcon(category: String) = when (category) {
    "Automatic" -> "zap"
    "Experimental" -> "flask-conical"
    "Draw" -> "palette"
    else -> "folder"
}

/**
 * Orbit's own package switches. They start as a copy of the main app's and change only here, so
 * switching a package off on one side leaves the other side alone.
 */
@Composable
internal fun OrbitPackagesPage(orbit: OrbitProfile, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val packageManager = remember { PackageManager.getInstance(context, AIToolHandler.getInstance(context)) }
    var reload by remember { mutableIntStateOf(0) }
    // null while loading; a failure shows a retry instead of spinning forever.
    val loaded by produceState<Result<PackagesState>?>(null, orbit.id, reload) {
        value = null
        value = withContext(Dispatchers.IO) {
            try { Result.success(run {
                val candidates = OrbitPackages.candidates(packageManager)
                val all = packageManager.getAvailablePackages()
                fun row(name: String, pkg: com.ai.assistance.operit.core.tools.ToolPackage) = PackageRow(
                    name = name,
                    title = pkg.displayName.resolve(context).ifBlank { name },
                    description = pkg.description.resolve(context),
                )
                // A plugin's subpackages share the plugin's folder; the rest follow the main app's categories.
                val byPlugin = linkedMapOf<String, MutableList<PackageRow>>()
                val byCategory = linkedMapOf<String, MutableList<PackageRow>>()
                candidates.forEach { (name, pkg) ->
                    val plugin = packageManager.resolveToolPkgSubpackageRuntimeInternal(name)?.containerPackageName
                    if (plugin != null) byPlugin.getOrPut(plugin) { mutableListOf() } += row(name, pkg)
                    else byCategory.getOrPut(pkg.category.ifBlank { "Other" }) { mutableListOf() } += row(name, pkg)
                }
                val categories = byCategory.entries.sortedWith(compareBy(
                    { CATEGORY_ORDER.indexOf(it.key).let { i -> if (i < 0) CATEGORY_ORDER.size else i } }, { it.key },
                )).map { (category, rows) ->
                    PackageFolder("category:$category", categoryTitle(context, category), categoryIcon(category),
                        rows.sortedBy { it.title.lowercase() })
                }
                val plugins = byPlugin.map { (plugin, rows) ->
                    PackageFolder("plugin:$plugin", all[plugin]?.displayName?.resolve(context)?.ifBlank { null } ?: plugin,
                        "puzzle", rows.sortedBy { it.title.lowercase() })
                }.sortedBy { it.title.lowercase() }
                PackagesState(
                    folders = categories + plugins,
                    enabled = OrbitPackages.enabled(context, orbit.id, packageManager),
                    mainCount = OrbitPackages.mainEnabled(packageManager).size,
                )
            }) } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    // Switches flip at once; the file catches up in the background.
    var overrides by remember(loaded) { mutableStateOf(emptyMap<String, Boolean>()) }
    var query by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf(false) }
    // Folders start closed so a long list stays short; a search opens every folder with a match.
    var opened by remember { mutableStateOf(emptySet<String>()) }

    fun toggle(name: String, on: Boolean, before: Boolean) {
        overrides = overrides + (name to on)
        scope.launch {
            try {
                val added = OrbitPackages.setEnabled(context, orbit.id, packageManager, name, on)
                // Plugins this one needs were switched on with it.
                overrides = overrides + added.associateWith { true }
                OrbitPackages.refreshPromptWhenIdle(context, orbit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Show the switch as it really is, and say so.
                overrides = overrides + (name to before)
                val reason = when (e) {
                    is OrbitPackages.NeedsMissingException ->
                        context.getString(R.string.orbit_packages_needs_missing, e.packageName)
                    is OrbitPackages.StillNeededException ->
                        context.getString(
                            R.string.orbit_packages_still_needed,
                            e.packageName,
                            e.dependents.joinToString(context.getString(R.string.orbit_packages_list_separator)),
                        )
                    else -> context.getString(R.string.orbit_packages_save_failed)
                }
                Toast.makeText(context, reason, Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.orbit_packages_title), onBack)
            Text(
                stringResource(R.string.orbit_packages_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp),
            )
        }
        val result = loaded
        if (result == null) {
            item(key = "loading") { OrbitDelayedSpinner() }
            return@LazyColumn
        }
        val state = result.getOrNull()
        if (state == null) {
            item(key = "failed") { OrbitLoadFailed { reload++ } }
            return@LazyColumn
        }
        fun isOn(name: String) = overrides[name] ?: (name in state.enabled)
        item(key = "import") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .clickable(role = Role.Button) { confirmImport = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) { Icon(orbitIcon("download"), contentDescription = null, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.orbit_packages_import), style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.orbit_packages_import_desc, state.mainCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item(key = "search") {
            Spacer(Modifier.height(18.dp))
            SearchPill(query, { query = it })
            Text(
                stringResource(R.string.orbit_packages_count,
                    state.folders.sumOf { f -> f.rows.count { isOn(it.name) } }, state.total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        val needle = query.trim().lowercase()
        val searching = needle.isNotEmpty()
        val folders = if (!searching) state.folders else state.folders.mapNotNull { folder ->
            val inTitle = folder.title.lowercase().contains(needle)
            val rows = if (inTitle) folder.rows else folder.rows.filter {
                it.title.lowercase().contains(needle) || it.name.lowercase().contains(needle) ||
                    it.description.lowercase().contains(needle)
            }
            rows.takeIf { it.isNotEmpty() }?.let { PackageFolder(folder.key, folder.title, folder.icon, it) }
        }
        if (folders.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.orbit_packages_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
        folders.forEach { folder ->
            val open = searching || folder.key in opened
            // Each folder is one gray card drawn in slices, so each package stays its own lazy item.
            item(key = folder.key) {
                FolderSlice(first = true, last = !open) {
                    FolderHeader(
                        folder = folder,
                        onCount = folder.rows.count { isOn(it.name) },
                        open = open,
                        enabled = !searching,
                    ) { opened = if (folder.key in opened) opened - folder.key else opened + folder.key }
                }
            }
            if (open) {
                itemsIndexed(folder.rows, key = { _, row -> folder.key + "/" + row.name }) { index, row ->
                    FolderSlice(first = false, last = index == folder.rows.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        val on = isOn(row.name)
                        PackageItem(row, on) { toggle(row.name, it, on) }
                    }
                }
            }
            item(key = folder.key + "/gap") { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (confirmImport) {
        TaskConfirmSheet(
            icon = "download",
            title = stringResource(R.string.orbit_packages_import_title),
            message = stringResource(R.string.orbit_packages_import_message),
            confirmText = stringResource(R.string.orbit_packages_import_confirm),
            dismissText = stringResource(R.string.cancel),
            destructive = false,
            onDismiss = { confirmImport = false },
            onConfirm = {
                confirmImport = false
                scope.launch {
                    try {
                        OrbitPackages.importFromMain(context, orbit.id, packageManager)
                        OrbitPackages.refreshPromptWhenIdle(context, orbit)
                        Toast.makeText(context, R.string.orbit_packages_imported, Toast.LENGTH_SHORT).show()
                        reload++
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.orbit_packages_import_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }
}

@Composable
internal fun SearchPill(query: String, onChange: (String) -> Unit, placeholder: String = stringResource(R.string.orbit_packages_search)) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(orbitIcon("search"), contentDescription = null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PackageItem(row: PackageRow, on: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            // One target for screen readers: the package name and its on/off state together.
            .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Icon(orbitIcon("package"), contentDescription = null, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.description.isNotBlank()) {
                Text(
                    row.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = on, onCheckedChange = null)
    }
}

/** A slice of a folder's gray card: the header on top, packages below, round corners at both ends. */
@Composable
internal fun FolderSlice(first: Boolean, last: Boolean, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(
            topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
            bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun FolderHeader(folder: PackageFolder, onCount: Int, open: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(OrbitMotion.PUSH_MS, easing = OrbitMotion.Easing),
        label = "folderChevron")
    val openedText = stringResource(R.string.orbit_packages_folder_opened)
    val closedText = stringResource(R.string.orbit_packages_folder_closed)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(
                // While searching every folder is open and cannot be closed, so there is no state to read out.
                if (enabled) Modifier.semantics { stateDescription = if (open) openedText else closedText } else Modifier
            )
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
            Text(stringResource(R.string.orbit_packages_count, onCount, folder.rows.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (enabled) {
            Icon(orbitIcon("chevron-down"), contentDescription = null, modifier = Modifier.size(20.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun categoryTitle(context: android.content.Context, category: String): String = when (category) {
    "Automatic" -> context.getString(R.string.orbit_packages_folder_automatic)
    "Experimental" -> context.getString(R.string.orbit_packages_folder_experimental)
    "Draw" -> context.getString(R.string.orbit_packages_folder_draw)
    "Other" -> context.getString(R.string.orbit_packages_folder_other)
    else -> category
}

/** Shown when a page could not read its files: a short reason and a way to try again. */
@Composable
internal fun OrbitLoadFailed(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.orbit_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onRetry),
        ) {
            Text(
                stringResource(R.string.orbit_retry),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            )
        }
    }
}

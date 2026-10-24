package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.ToolPackage
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
    /** The main app deleted it; Orbit keeps its own copy. */
    val copy: Boolean = false,
) {
    /** Orbit always keeps it on: no switch, no removal. */
    val required: Boolean get() = name in OrbitPackages.REQUIRED
}

/** One folder of packages: a category of the main app, or one plugin with its subpackages. */
@Immutable
private class PackageFolder(val key: String, val title: String, val icon: String, val rows: List<PackageRow>)

/**
 * What the page shows: the Orbit's imported packages in folders, imported ones the app no longer
 * has, which are on, and the main app's packages that can still be imported.
 */
private class PackagesState(
    val folders: List<PackageFolder>,
    val missing: List<String>,
    val enabled: Set<String>,
    val importable: List<PackageFolder>,
) {
    val total = folders.sumOf { it.rows.size }
    val importableCount = importable.sumOf { it.rows.size }
}

/** The main app's package categories, in its order. */
private val CATEGORY_ORDER = listOf("Automatic", "Experimental", "Draw", "Other")

private fun categoryIcon(category: String) = when (category) {
    "Automatic" -> "zap"
    "Experimental" -> "flask-conical"
    "Draw" -> "palette"
    else -> "folder"
}

/** [packages] in the main app's folders: plugins with their subpackages, the rest by category. */
private fun folders(
    context: android.content.Context,
    packageManager: PackageManager,
    all: Map<String, ToolPackage>,
    packages: Map<String, ToolPackage>,
): List<PackageFolder> {
    fun row(name: String, pkg: ToolPackage) = PackageRow(
        name = name,
        title = pkg.displayName.resolve(context).ifBlank { name },
        description = pkg.description.resolve(context),
        copy = packageManager.isOrbitCopy(name),
    )
    val byPlugin = linkedMapOf<String, MutableList<PackageRow>>()
    val byCategory = linkedMapOf<String, MutableList<PackageRow>>()
    packages.forEach { (name, pkg) ->
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
    return categories + plugins
}

/** [folders] cut down to what matches [needle]; a folder whose name matches keeps every package. */
private fun filtered(folders: List<PackageFolder>, needle: String): List<PackageFolder> =
    if (needle.isEmpty()) folders else folders.mapNotNull { folder ->
        val inTitle = folder.title.lowercase().contains(needle)
        val rows = if (inTitle) folder.rows else folder.rows.filter {
            it.title.lowercase().contains(needle) || it.name.lowercase().contains(needle) ||
                it.description.lowercase().contains(needle)
        }
        rows.takeIf { it.isNotEmpty() }?.let { PackageFolder(folder.key, folder.title, folder.icon, it) }
    }

/**
 * Orbit's own packages: only the ones imported from the main app, each with its own switch. The
 * user picks which of the main app's packages to import; nothing follows the main app afterwards.
 */
@Composable
internal fun OrbitPackagesPage(orbit: OrbitProfile, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val packageManager = remember { PackageManager.getInstance(context, AIToolHandler.getInstance(context)) }
    var reload by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // The picker takes back first while it is open.
    OrbitPageBack(onBack = onBack)
    // null while loading; a failure shows a retry instead of spinning forever.
    val loaded by produceState<Result<PackagesState>?>(null, orbit.id, reload) {
        if (value?.isSuccess != true) value = null
        value = withContext(Dispatchers.IO) {
            try { Result.success(run {
                val all = packageManager.getAvailablePackages()
                val state = OrbitPackages.state(context, orbit.id)
                // What this Orbit has stays usable even if the main app archived or deleted it.
                val candidates = OrbitPackages.candidates(packageManager, state.imported)
                PackagesState(
                    folders = folders(context, packageManager, all, candidates.filterKeys { it in state.imported }),
                    // Dependency containers stay hidden; anything else Orbit can no longer use
                    // (deleted or archived) is listed so it can be removed.
                    missing = state.imported.filter {
                        it !in candidates && it !in OrbitPackages.REQUIRED &&
                            !(it in all && packageManager.isToolPkgContainer(it))
                    }.sorted(),
                    enabled = state.enabled,
                    importable = folders(context, packageManager, all,
                        OrbitPackages.importable(packageManager).filterKeys { it !in state.imported }),
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
    // Folders start closed so a long list stays short; a search opens every folder with a match.
    var opened by remember { mutableStateOf(emptySet<String>()) }
    // The package whose details are open; kept while the page slides away.
    var detail by remember { mutableStateOf<PackageRow?>(null) }
    var detailShown by remember { mutableStateOf(false) }

    fun reason(e: Exception, fallback: Int): String = when (e) {
        is OrbitPackages.NeedsMissingException ->
            resources.getString(R.string.orbit_packages_needs_missing, e.packageName)
        is OrbitPackages.StillNeededException ->
            resources.getString(
                R.string.orbit_packages_still_needed,
                e.packageName,
                e.dependents.joinToString(resources.getString(R.string.orbit_packages_list_separator)),
            )
        is OrbitPackages.RequiredException ->
            resources.getString(R.string.orbit_packages_required_locked)
        else -> resources.getString(fallback)
    }

    fun toggle(name: String, on: Boolean, before: Boolean) {
        overrides = overrides + (name to on)
        scope.launch {
            try {
                val added = OrbitPackages.setEnabled(context, orbit.id, packageManager, name, on)
                // Plugins this one needs were switched on with it.
                overrides = overrides + added.associateWith { true }
                if (added.any { it != name }) reload++
                OrbitPackages.refreshPromptWhenIdle(context, orbit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                // Show the switch as it really is, and say so.
                overrides = overrides + (name to before)
                Toast.makeText(context, reason(e, R.string.orbit_packages_save_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun remove(name: String) {
        scope.launch {
            try {
                OrbitPackages.remove(context, orbit.id, packageManager, name)
                OrbitPackages.refreshPromptWhenIdle(context, orbit)
                reload++
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Toast.makeText(context, reason(e, R.string.orbit_packages_save_failed), Toast.LENGTH_LONG).show()
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
                stringResource(R.string.orbit_packages_help) + "\n" + stringResource(R.string.orbit_packages_help_detail),
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
                        .clickable(role = Role.Button) { picking = true }
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
                        Text(stringResource(R.string.orbit_packages_import_desc, state.importableCount),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(orbitIcon("chevron-right"), contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.total == 0 && state.missing.isEmpty()) {
            item(key = "none") {
                Text(
                    stringResource(R.string.orbit_packages_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 24.dp),
                )
            }
            return@LazyColumn
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
        val shown = filtered(state.folders, needle)
        if (shown.isEmpty() && searching) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.orbit_packages_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
        folderItems(shown, searching, opened, { key -> opened = if (key in opened) opened - key else opened + key },
            countOf = { folder -> folder.rows.count { isOn(it.name) } }) { row ->
            val on = isOn(row.name)
            PackageItem(row, on, onToggle = { toggle(row.name, it, on) }, onRemove = { remove(row.name) },
                onOpen = { detail = row; detailShown = true })
        }
        // Imported packages the app no longer has: they cannot be used, only taken out.
        if (state.missing.isNotEmpty() && !searching) {
            item(key = "missing") {
                SectionTitle(stringResource(R.string.orbit_packages_missing))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    Column {
                        state.missing.forEachIndexed { index, name ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                androidx.compose.material3.TextButton(onClick = { remove(name) }) {
                                    Text(stringResource(R.string.orbit_packages_remove))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // The main app's packages slide in from the right to be picked.
    OrbitCoverPage(visible = picking, onDismiss = { picking = false }) {
        val state = loaded?.getOrNull()
        OrbitPackageImportPage(
            folders = state?.importable.orEmpty(),
            busy = importing,
            onBack = { picking = false },
            onImport = { names ->
                importing = true
                scope.launch {
                    try {
                        val result = OrbitPackages.importSelected(context, orbit.id, packageManager, names)
                        OrbitPackages.refreshPromptWhenIdle(context, orbit)
                        val failed = result.failed.values.firstOrNull()
                        Toast.makeText(
                            context,
                            if (failed != null) reason(failed, R.string.orbit_packages_import_failed)
                            else resources.getString(R.string.orbit_packages_imported, result.added.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                        picking = false
                        reload++
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.orbit_packages_import_failed, Toast.LENGTH_SHORT).show()
                    } finally {
                        importing = false
                    }
                }
            },
        )
    }

    // A package's details slide in from the right: what it does, its tools, its switch.
    OrbitCoverPage(visible = detailShown, onDismiss = { detailShown = false }) {
        detail?.let { row ->
            val state = loaded?.getOrNull()
            val on = overrides[row.name] ?: (state?.enabled?.contains(row.name) == true)
            OrbitPackageDetailPage(
                row = row,
                on = on,
                packageManager = packageManager,
                onBack = { detailShown = false },
                onToggle = { toggle(row.name, it, on) },
                onRemove = { detailShown = false; remove(row.name) },
            )
        }
    }
}

/** What a package does: its description, where it came from, its tools, and Orbit's switch for it. */
@Composable
private fun OrbitPackageDetailPage(
    row: PackageRow,
    on: Boolean,
    packageManager: PackageManager,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val pkg by produceState<ToolPackage?>(null, row.name) {
        value = withContext(Dispatchers.IO) { runCatching { packageManager.resolvePackageForDisplay(row.name) }.getOrNull() }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(row.title, onBack)
            Text(
                row.name + (pkg?.version?.takeIf { it.isNotBlank() }?.let {
                    " · " + resources.getString(R.string.orbit_packages_detail_meta, it)
                }.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
            val description = pkg?.description?.resolve(context)?.ifBlank { null } ?: row.description
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp))
            }
            if (row.copy) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Text(stringResource(R.string.orbit_packages_copy_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            // Orbit's own switch for it, as on the list.
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .then(if (row.required) Modifier else Modifier.toggleable(on, role = Role.Switch, onValueChange = onToggle))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.orbit_packages_detail_switch), style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    if (row.required) {
                        Text(stringResource(R.string.orbit_packages_required), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                    }
                    Switch(checked = on || row.required, onCheckedChange = null, enabled = !row.required)
                }
            }
        }
        val tools = pkg?.tools.orEmpty()
        if (tools.isNotEmpty()) {
            item(key = "tools-title") {
                Text(stringResource(R.string.orbit_packages_detail_tools, tools.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
            }
            itemsIndexed(tools, key = { _, tool -> "tool/" + tool.name }) { index, tool ->
                FolderSlice(first = index == 0, last = index == tools.lastIndex) {
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tool.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val what = tool.description.resolve(context)
                        if (what.isNotBlank()) Text(what, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tool.parameters.isNotEmpty()) Text(
                            stringResource(R.string.orbit_packages_detail_params,
                                tool.parameters.joinToString(stringResource(R.string.orbit_packages_list_separator)) { it.name }),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!row.required) item(key = "remove") {
            androidx.compose.material3.TextButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                Text(stringResource(R.string.orbit_packages_remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** Folders drawn as gray cards in slices, each package its own lazy item. */
private fun LazyListScope.folderItems(
    folders: List<PackageFolder>,
    searching: Boolean,
    opened: Set<String>,
    onFlip: (String) -> Unit,
    countOf: (PackageFolder) -> Int,
    countText: Int = R.string.orbit_packages_count,
    content: @Composable (PackageRow) -> Unit,
) {
    folders.forEach { folder ->
        val open = searching || folder.key in opened
        item(key = folder.key) {
            FolderSlice(first = true, last = !open) {
                FolderHeader(folder = folder, onCount = countOf(folder), countText = countText, open = open, enabled = !searching) {
                    onFlip(folder.key)
                }
            }
        }
        if (open) {
            itemsIndexed(folder.rows, key = { _, row -> folder.key + "/" + row.name }) { index, row ->
                FolderSlice(first = false, last = index == folder.rows.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    content(row)
                }
            }
        }
        item(key = folder.key + "/gap") { Spacer(Modifier.height(12.dp)) }
    }
}

/**
 * The main app's packages that are not in this Orbit yet, in the same folders, each with a check
 * box. The ones picked are imported together, switched on.
 */
@Composable
private fun OrbitPackageImportPage(
    folders: List<PackageFolder>,
    busy: Boolean,
    onBack: () -> Unit,
    onImport: (Set<String>) -> Unit,
) {
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var query by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf(emptySet<String>()) }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
        ) {
            item(key = "header") {
                OrbitPageHeader(stringResource(R.string.orbit_packages_import), onBack)
                Text(
                    stringResource(R.string.orbit_packages_import_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp),
                )
            }
            if (folders.isEmpty()) {
                item(key = "all-in") {
                    Text(
                        stringResource(R.string.orbit_packages_import_all_in),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp),
                    )
                }
                return@LazyColumn
            }
            item(key = "search") {
                SearchPill(query, { query = it })
                Spacer(Modifier.height(16.dp))
            }
            val needle = query.trim().lowercase()
            val searching = needle.isNotEmpty()
            val shown = filtered(folders, needle)
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.orbit_packages_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
            folderItems(shown, searching, opened, { key -> opened = if (key in opened) opened - key else opened + key },
                countOf = { folder -> folder.rows.count { it.name in picked } },
                countText = R.string.orbit_packages_picked_count) { row ->
                PickItem(row, row.name in picked) { on -> picked = if (on) picked + row.name else picked - row.name }
            }
        }
        if (folders.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (picked.isEmpty() || busy) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = picked.isNotEmpty() && !busy, role = Role.Button) {
                        onImport(picked)
                    },
            ) {
                Text(
                    stringResource(R.string.orbit_packages_import_count, picked.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (picked.isEmpty() || busy) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun PickItem(row: PackageRow, picked: Boolean, onPick: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = picked, role = Role.Checkbox, onValueChange = onPick)
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
                Text(row.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(checked = picked, onCheckedChange = null)
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

/**
 * One imported package: tap switches it, a long press offers to take it out of the Orbit. A required
 * package shows a fixed "always on" switch and does nothing on tap or long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PackageItem(row: PackageRow, on: Boolean, onToggle: (Boolean) -> Unit, onRemove: () -> Unit, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val requiredText = stringResource(R.string.orbit_packages_required)
    val copyText = stringResource(R.string.orbit_packages_copy)
    Box {
    Row(
        Modifier.fillMaxWidth()
            // Tapping the package opens its details, like the main app; its switch turns it on or off.
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.orbit_packages_detail_open),
                onClick = onOpen,
                onLongClick = if (row.required) null else ({ menu = true }),
                onLongClickLabel = if (row.required) null else stringResource(R.string.orbit_packages_remove),
            )
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Icon(orbitIcon("package"), contentDescription = null, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (row.required) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clearAndSetSemantics { }) {
                        Text(requiredText, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                if (row.copy) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface) {
                        Text(copyText, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
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
        Switch(
            checked = on || row.required,
            onCheckedChange = if (row.required) null else onToggle,
            enabled = !row.required,
            // Read out with the package's name, so the switch is not just "on" on its own.
            modifier = Modifier.semantics { contentDescription = row.title },
        )
    }
    if (!row.required) OrbitDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.orbit_packages_remove)) },
            leadingIcon = { Icon(orbitIcon("trash-2"), contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = {
                menu = false
                onRemove()
            },
        )
    }
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
private fun FolderHeader(folder: PackageFolder, onCount: Int, countText: Int, open: Boolean, enabled: Boolean, onClick: () -> Unit) {
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
            Text(stringResource(countText, onCount, folder.rows.size),
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

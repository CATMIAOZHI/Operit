package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    /** The ToolPkg container that is off in the main app, when this package needs it. */
    val missingContainer: String?,
)

private class PackagesState(val rows: List<PackageRow>, val enabled: Set<String>, val mainCount: Int)

/**
 * Orbit's own package switches. They start as a copy of the main app's and change only here, so
 * switching a package off on one side leaves the other side alone.
 */
@Composable
internal fun OrbitPackagesPage(orbit: OrbitProfile, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
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
                PackagesState(
                    rows = candidates.map { (name, pkg) ->
                        PackageRow(
                            name = name,
                            title = pkg.displayName.resolve(context).ifBlank { name },
                            description = pkg.description.resolve(context),
                            missingContainer = OrbitPackages.missingContainer(packageManager, name),
                        )
                    }.sortedBy { it.title.lowercase() },
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

    fun toggle(name: String, on: Boolean, before: Boolean) {
        overrides = overrides + (name to on)
        scope.launch {
            try {
                OrbitPackages.setEnabled(context, orbit.id, packageManager, name, on)
                OrbitPackages.refreshPromptWhenIdle(context, orbit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Show the switch as it really is, and say so.
                overrides = overrides + (name to before)
                Toast.makeText(context, R.string.orbit_packages_save_failed, Toast.LENGTH_SHORT).show()
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
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
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
                stringResource(R.string.orbit_packages_count, state.rows.count { isOn(it.name) }, state.rows.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        val needle = query.trim().lowercase()
        val shown = if (needle.isEmpty()) state.rows else state.rows.filter {
            it.title.lowercase().contains(needle) || it.name.lowercase().contains(needle) ||
                it.description.lowercase().contains(needle)
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.orbit_packages_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
        itemsIndexed(shown, key = { _, row -> row.name }) { index, row ->
            val first = index == 0
            val last = index == shown.lastIndex
            // One gray card drawn in slices, so each package stays its own lazy item.
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
                    bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
                ),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    if (!first) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 66.dp, end = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    val on = isOn(row.name)
                    PackageItem(row, on) { toggle(row.name, it, on) }
                }
            }
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
private fun SearchPill(query: String, onChange: (String) -> Unit) {
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
                    Text(stringResource(R.string.orbit_packages_search), style = MaterialTheme.typography.bodyLarge,
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
            if (row.missingContainer != null) {
                Text(
                    stringResource(R.string.orbit_packages_needs_container, row.missingContainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (row.description.isNotBlank()) {
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

package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitMcp
import com.ai.assistance.operit.features.orbit.OrbitPackages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
private data class McpRow(val id: String, val title: String, val description: String, val on: Boolean, val status: OrbitMcp.Status)

/**
 * The app's MCP servers with this Orbit's own switch for each, and where each stands. Hidden when
 * the app has none set up.
 */
@Composable
internal fun OrbitMcpSection(orbit: OrbitProfile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var reload by remember { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(emptySet<String>()) }
    val rows by produceState<List<McpRow>?>(null, orbit.id, reload) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val on = OrbitMcp.enabled(context, orbit.id)
                OrbitMcp.configured(context).values.map {
                    McpRow(it.id, it.name, it.description, it.id in on, OrbitMcp.status(context, it.id, on))
                }
            }.getOrNull()
        } ?: value
    }
    val list = rows ?: return
    if (list.isEmpty()) return
    // A start or a failed connection from a chat or a background task shows up while the page is on screen.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(2000)
                reload++
            }
        }
    }
    // A switch flips at once and stays so until the saved list says the same.
    var overrides by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    LaunchedEffect(list) {
        overrides = overrides.filter { (id, on) -> list.firstOrNull { it.id == id }?.on != on }
    }

    fun toggle(id: String, on: Boolean) {
        overrides = overrides + (id to on)
        scope.launch {
            try {
                if (on) OrbitMcp.set(context, orbit.id, listOf(id), emptyList())
                else OrbitMcp.set(context, orbit.id, emptyList(), listOf(id))
                OrbitPackages.refreshPromptWhenIdle(context, orbit)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                overrides = overrides - id
                Toast.makeText(context, R.string.orbit_packages_save_failed, Toast.LENGTH_SHORT).show()
            }
            reload++
        }
    }

    fun start(id: String) {
        starting = starting + id
        scope.launch {
            try {
                val on = OrbitMcp.enabled(context, orbit.id)
                OrbitMcp.ensureStarted(context, id, on, manual = true)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
            } finally {
                starting = starting - id
                reload++
            }
        }
    }

    SectionTitle(stringResource(R.string.orbit_mcp_section))
    Text(
        stringResource(R.string.orbit_mcp_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            list.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val on = overrides[row.id] ?: row.on
                McpItem(row, on, busy = row.id in starting, onToggle = { toggle(row.id, it) }, onStart = { start(row.id) })
            }
        }
    }
}

@Composable
private fun McpItem(row: McpRow, on: Boolean, busy: Boolean, onToggle: (Boolean) -> Unit, onStart: () -> Unit) {
    val state = if (busy) OrbitMcp.State.STARTING else row.status.state
    val offInApp = state == OrbitMcp.State.OFF_IN_APP
    val label = when {
        offInApp -> R.string.orbit_mcp_state_off_app
        !on -> R.string.orbit_mcp_state_off
        state == OrbitMcp.State.READY -> R.string.orbit_mcp_state_running
        state == OrbitMcp.State.STARTING -> R.string.orbit_mcp_state_starting
        state == OrbitMcp.State.FAILED -> R.string.orbit_mcp_state_failed
        state == OrbitMcp.State.NOT_STARTED && row.status.reason != null -> R.string.orbit_mcp_state_terminal
        else -> R.string.orbit_mcp_state_not_started
    }
    val failed = on && state == OrbitMcp.State.FAILED
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) { Icon(orbitIcon("plug"), contentDescription = null, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed) row.status.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        val canStart = on && !busy && (state == OrbitMcp.State.FAILED ||
            (state == OrbitMcp.State.NOT_STARTED && row.status.reason == null))
        if (canStart) {
            TextButton(onClick = onStart) {
                Text(stringResource(if (failed) R.string.orbit_mcp_retry else R.string.orbit_mcp_start))
            }
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = on && !offInApp,
            onCheckedChange = if (offInApp) null else onToggle,
            enabled = !offInApp,
            modifier = Modifier.semantics { contentDescription = row.title },
        )
    }
}

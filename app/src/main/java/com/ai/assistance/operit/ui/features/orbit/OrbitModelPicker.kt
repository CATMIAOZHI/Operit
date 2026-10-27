package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions

/** What the sheet knows about the provider's model list. */
internal sealed interface ModelListState {
    data object Idle : ModelListState
    data object Loading : ModelListState
    data class Loaded(val models: List<String>) : ModelListState
    data object Failed : ModelListState
}

/** Rows shown at once; the search narrows a long list down. */
private const val SHOWN = 60

/**
 * Picking the models for a new model configuration: the provider's own list once it is fetched,
 * with the ones Orbit suggested ticked. Suggested names the provider does not list stay pickable,
 * marked, in case the list is incomplete.
 */
@Composable
internal fun OrbitModelPicker(
    suggested: List<String>,
    state: ModelListState,
    picked: Set<String>,
    canFetch: Boolean,
    enabled: Boolean,
    onFetch: () -> Unit,
    onToggle: (String) -> Unit,
    /** Shown instead of the usual "check the key and endpoint" when fetching failed. */
    failedText: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val listed = (state as? ModelListState.Loaded)?.models.orEmpty()
    // A new list (for example after another key) starts with an empty search.
    var query by remember(listed) { mutableStateOf("") }
    val unlisted = if (state is ModelListState.Loaded) suggested.filter { it !in listed } else suggested
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.orbit_model_pick_title),
                    style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (picked.isNotEmpty()) {
                    Text(
                        stringResource(R.string.orbit_model_pick_count, picked.size),
                        style = MaterialTheme.typography.labelMedium, color = colors.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            when (state) {
                ModelListState.Idle, ModelListState.Failed -> {
                    Text(
                        if (state == ModelListState.Failed && failedText != null) failedText
                        else stringResource(
                            when {
                                state == ModelListState.Failed -> R.string.orbit_model_pick_failed
                                canFetch -> R.string.orbit_model_pick_hint
                                else -> R.string.orbit_model_pick_need_key
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state == ModelListState.Failed) colors.error else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        onClick = onFetch, enabled = enabled && canFetch, shape = RoundedCornerShape(50),
                        color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(
                                    if (state == ModelListState.Failed) R.string.orbit_model_pick_retry
                                    else R.string.orbit_model_pick_fetch
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (enabled && canFetch) colors.onSurface else colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                ModelListState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.orbit_model_pick_loading), style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                }
                is ModelListState.Loaded -> {
                    if (listed.isEmpty()) {
                        Text(stringResource(R.string.orbit_model_pick_empty), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                    } else if (listed.size > 8) {
                        Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            TextField(
                                query, { query = it.take(80) }, singleLine = true, enabled = enabled,
                                placeholder = { Text(stringResource(R.string.orbit_model_pick_search)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            val q = query.trim().lowercase()
            fun matches(name: String) = q.isEmpty() || name.lowercase().contains(q)
            // Ticked ones first so they stay in view, then the rest of the provider's list.
            val rows = (picked.filter { it in listed || it in unlisted } + unlisted + listed).distinct().filter(::matches)
            val full = picked.size >= OrbitApprovalActions.MAX_PICKED_MODELS
            rows.take(SHOWN).forEach { name ->
                val ticked = name in picked
                val rowEnabled = enabled && (ticked || !full)
                Row(
                    // The whole row is one checkbox, so a screen reader reads the model name with it.
                    Modifier.fillMaxWidth()
                        .toggleable(value = ticked, enabled = rowEnabled, role = Role.Checkbox) { onToggle(name) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null, enabled = rowEnabled,
                        modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (rowEnabled) colors.onSurface else colors.onSurfaceVariant)
                        if (state is ModelListState.Loaded && name in unlisted) {
                            Text(stringResource(R.string.orbit_model_pick_unlisted), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if (full) {
                Text(
                    stringResource(R.string.orbit_model_pick_full, OrbitApprovalActions.MAX_PICKED_MODELS),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (rows.size > SHOWN) {
                Text(
                    stringResource(R.string.orbit_model_pick_more, rows.size - SHOWN),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

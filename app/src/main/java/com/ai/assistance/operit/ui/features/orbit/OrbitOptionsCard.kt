package com.ai.assistance.operit.ui.features.orbit

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitOptions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends a reply option as the user's next message in an Orbit chat: (chat id, text) -> sent. Null when the chat cannot take one. */
/** (chat id, text, still waiting, lost) -> started. lost is called when the message was dropped on the way. */
val LocalOrbitOptionSend = compositionLocalOf<((String, String, () -> Boolean, () -> Unit) -> Boolean)?> { null }

/**
 * Reply options inside one of Orbit's replies, like Muse's: each a dashed button with what the user
 * would say. A tap sends it as the user's message at once; the set then shows which one went and
 * takes no more taps.
 */
@Composable
internal fun OrbitOptionsCard(id: String) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { OrbitOptions.load(context) } }
    val sets by OrbitOptions.sets.collectAsState()
    val chatId = com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOwner.current?.chatId
    val set = sets[id]?.takeIf { chatId == null || it.chatId == chatId } ?: return
    val send = LocalOrbitOptionSend.current
    val colors = MaterialTheme.colorScheme
    val used = set.chosen >= 0
    val chosenLabel = androidx.compose.ui.res.stringResource(R.string.orbit_options_chosen)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        set.options.forEachIndexed { index, option ->
            val chosen = set.chosen == index
            val outline = if (chosen) colors.primary else colors.outline.copy(alpha = if (used) 0.35f else 0.7f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawRoundRect(
                            color = colors.surface.copy(alpha = if (used && !chosen) 0.4f else 0.9f),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                        )
                        drawRoundRect(
                            color = outline,
                            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                            size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                            cornerRadius = CornerRadius(12.dp.toPx()),
                            style = Stroke(
                                width = stroke,
                                pathEffect = if (chosen) null else PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                            ),
                        )
                    }
                    .clickable(enabled = !used && send != null, role = Role.Button) {
                        if (!OrbitOptions.choose(context, set.id, index)) return@clickable
                        val app = context.applicationContext
                        // Confirmed where the user message is saved; undone only if it was lost.
                        OrbitOptions.expect(app, set.chatId, set.id, index, option)
                        val failed = {
                            if (OrbitOptions.drop(set.id, index)) {
                                OrbitOptions.unchoose(app, set.id, index)
                                Toast.makeText(app, R.string.orbit_options_send_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                        val started = send?.invoke(set.chatId, option, { OrbitOptions.isWaiting(set.id, index) }, failed) == true
                        if (!started) failed()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .let { base ->
                        if (!chosen) base else base.semantics {
                            selected = true
                            stateDescription = chosenLabel
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (used && !chosen) colors.onSurfaceVariant.copy(alpha = 0.6f) else colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (chosen) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

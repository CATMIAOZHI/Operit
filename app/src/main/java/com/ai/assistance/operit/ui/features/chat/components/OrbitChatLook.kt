package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R

/**
 * True inside an Orbit chat. Orbit keeps its own clean look: messages show no avatars, names or
 * model/config line, whatever the user chose for regular chats.
 */
val LocalOrbitChatLook = compositionLocalOf { false }

/** Emoji reactions Orbit put on the user's messages in this chat, by message time. */
val LocalOrbitReactions = compositionLocalOf<Map<Long, String>> { emptyMap() }

/** A user's message with Orbit's reaction, if any, tucked under the bubble's corner like a chat app. */
@Composable
fun OrbitReactedUserMessage(timestamp: Long, content: @Composable () -> Unit) {
    val reaction = LocalOrbitReactions.current[timestamp]
    if (reaction == null) {
        content()
        return
    }
    Column(Modifier.fillMaxWidth()) {
        content()
        val label = stringResource(R.string.orbit_reaction_label, reaction)
        // Lifted 10dp to overlap the bubble's lower corner, without leaving that gap below.
        Box(
            Modifier.fillMaxWidth().padding(end = 8.dp).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val lift = 10.dp.roundToPx()
                layout(placeable.width, (placeable.height - lift).coerceAtLeast(0)) { placeable.place(0, -lift) }
            },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 1.dp,
                modifier = Modifier.clearAndSetSemantics { contentDescription = label },
            ) {
                Text(reaction, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
            }
        }
    }
}

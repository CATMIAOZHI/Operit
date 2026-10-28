package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.preferences.OrbitChatOrigin
import com.ai.assistance.operit.features.orbit.OrbitChatFork
import java.text.DateFormat
import java.util.Date

/** Supplied only by the main Orbit chat; developer and other chats cannot fork through this menu. */
val LocalOrbitForkMessage = compositionLocalOf<((ChatMessage) -> Unit)?> { null }

@Composable
internal fun OrbitForkReference(origin: OrbitChatOrigin, onOpenMain: () -> Unit) {
    val context = LocalContext.current
    val english = !androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language.startsWith("zh")
    key(origin, english) {
        val excerpt by produceState<String?>(null) {
            value = OrbitChatFork.source(context, origin, english)?.text
        }
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(origin.messageId))
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
            .clickable(onClick = onOpenMain).padding(horizontal = 24.dp, vertical = 6.dp)) {
            Text(stringResource(R.string.orbit_fork_origin, time),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(excerpt ?: stringResource(R.string.orbit_fork_open_main),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

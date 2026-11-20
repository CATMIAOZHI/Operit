package com.ai.assistance.operit.ui.permissions

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Where a review's details send the user to change a tool's permission: [open] goes to the page
 * that sets the rules of [orbit] chats (an Orbit's) or the others (Operit's); without it, [hint]
 * says where that page is, if anywhere.
 */
@Immutable
class ToolPermissionsEntry(val orbit: Boolean, val open: (() -> Unit)?, @param:StringRes val hint: Int?)

/** Each host sets its own: Operit's pages, or Orbit's (whose chats follow rules of their own). */
val LocalToolPermissionsEntry = compositionLocalOf {
    ToolPermissionsEntry(orbit = false, open = null, hint = R.string.permission_review_tool_permissions_hint)
}

/**
 * Points a review's details to the tool permission settings for the chat [chatId] the call came
 * from. A tool is never allowed from here: that is decided in those settings, where every exception
 * shows. [onLeave] closes the details.
 */
@Composable
fun ToolPermissionSettingsLink(chatId: String, onLeave: () -> Unit) {
    val context = LocalContext.current
    // An Orbit's chats follow Orbit's rules, the others Operit's, wherever the chat is shown. Worked
    // out off the main thread and only redrawn when the answer changes; nothing shows until known.
    val orbitChat by remember(context, chatId) {
        val preferences = OrbitPreferences.getInstance(context)
        preferences.settingsFlow
            .map { chatId.isNotBlank() && it.ownerForChat(chatId) != null }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }.collectAsState(initial = null)
    val known = orbitChat ?: return
    val provided = LocalToolPermissionsEntry.current
    val entry = when (known) {
        provided.orbit -> provided
        true -> ToolPermissionsEntry(orbit = true, open = null, hint = R.string.permission_review_orbit_tool_permissions_hint)
        false -> ToolPermissionsEntry(orbit = false, open = null, hint = R.string.permission_review_tool_permissions_hint)
    }
    val open = entry.open
    if (open != null) {
        TextButton(onClick = {
            onLeave()
            open()
        }) {
            Text(stringResource(R.string.permission_review_open_tool_permissions))
        }
    } else if (entry.hint != null) {
        Text(
            stringResource(entry.hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

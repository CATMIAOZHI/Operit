package com.ai.assistance.operit.features.orbit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitSettings
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Orbit chats live in Orbit's own page, so the normal chat lists leave them out. They stay normal,
 * visible rows in the database: memory learning and chat recall only read visible chats, and
 * Orbit's own memory depends on both.
 */
object OrbitChatVisibility {
    /** Chats owned by an Orbit that still exists. Chats of a deleted Orbit show up again. */
    fun orbitChatIds(settings: OrbitSettings): Set<String> {
        val existing = settings.orbits.mapTo(HashSet()) { it.id }
        return buildSet {
            settings.orbits.forEach { add(it.chatId); addAll(it.ownedChatIds) }
            settings.chatOwners.forEach { (chatId, orbitId) -> if (orbitId in existing) add(chatId) }
        }
    }
}

@Composable
fun rememberOrbitChatIds(): State<Set<String>> {
    val context = LocalContext.current
    val preferences = remember(context) { OrbitPreferences.getInstance(context.applicationContext) }
    val flow = remember(preferences) {
        preferences.settingsFlow.map(OrbitChatVisibility::orbitChatIds).distinctUntilChanged()
    }
    // The app reads the settings at start, so the cached snapshot is normally the real value and
    // Orbit chats do not flash into the list; it never blocks drawing on disk.
    val initial = remember(preferences) { OrbitChatVisibility.orbitChatIds(preferences.snapshot) }
    return flow.collectAsState(initial = initial)
}

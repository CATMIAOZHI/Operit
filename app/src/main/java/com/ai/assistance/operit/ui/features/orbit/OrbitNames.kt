package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The name the user knows this companion by, for words about the companion itself ("Mia is
 * typing"). Words about the feature (settings, tools, the Orbit tab) keep saying Orbit.
 */
@Composable
internal fun orbitName(orbit: OrbitProfile): String = orbit.name.ifBlank { stringResource(R.string.nav_orbit) }

/** [orbitName] for a page that only has the Orbit's id; follows a rename while the page is open. */
@Composable
internal fun rememberOrbitName(orbitId: String): String {
    val preferences = OrbitPreferences.getInstance(LocalContext.current.applicationContext)
    val names = remember(preferences, orbitId) {
        preferences.settingsFlow.map { settings -> settings.orbits.firstOrNull { it.id == orbitId }?.name.orEmpty() }
            .distinctUntilChanged()
    }
    // The settings already read show the name at once instead of "Orbit" for a frame.
    val initial = remember(preferences, orbitId) {
        preferences.snapshot.orbits.firstOrNull { it.id == orbitId }?.name.orEmpty()
    }
    val name by names.collectAsState(initial = initial)
    return name.ifBlank { stringResource(R.string.nav_orbit) }
}

/** The name for a memory space's pages: the Orbit it belongs to, or null for the app's own memory. */
@Composable
internal fun rememberOrbitNameForSpace(spaceId: String?): String? {
    val preferences = OrbitPreferences.getInstance(LocalContext.current.applicationContext)
    val names = remember(preferences, spaceId) {
        preferences.settingsFlow
            .map { settings -> spaceId?.let { id -> settings.orbits.firstOrNull { it.memorySpaceId == id } }?.name }
            .distinctUntilChanged()
    }
    val initial = remember(preferences, spaceId) {
        spaceId?.let { id -> preferences.snapshot.orbits.firstOrNull { it.memorySpaceId == id } }?.name
    }
    val name by names.collectAsState(initial = initial)
    val fallback = stringResource(R.string.nav_orbit)
    return name?.ifBlank { fallback }
}

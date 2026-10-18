package com.ai.assistance.operit.ui.features.memory.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.preferencesManager

/**
 * Regular in-app destination, without a dialog window or floating surface. An Orbit's pages use
 * Orbit's own frame (round back button and title) so they match the profile they open from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryLibraryPage(
    title: String,
    profileId: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    if (com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences.isOrbitSpace(profileId)) {
        BackHandler(onBack = onBack)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                com.ai.assistance.operit.ui.features.orbit.OrbitPageHeader(
                    title, onBack, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
                Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding().imePadding()) { content() }
            }
        }
        return
    }
    val profile by remember(profileId) { preferencesManager.getMemorySpaceFlow(profileId) }
        .collectAsState(initial = null)
    val profileName = profile?.name.orEmpty()
    BackHandler(onBack = onBack)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleLarge)
                        Text(profileName, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.memory_a11y_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) { content() }
    }
}

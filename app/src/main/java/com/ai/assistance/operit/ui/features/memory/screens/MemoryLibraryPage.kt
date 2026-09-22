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

/** Regular in-app destination, without a dialog window or floating surface. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryLibraryPage(
    title: String,
    profileId: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val profileNames by preferencesManager.memorySpaceNamesFlow.collectAsState(initial = emptyMap())
    val profileName = profileNames[profileId] ?: profileId
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.chat_recall_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) { content() }
    }
}

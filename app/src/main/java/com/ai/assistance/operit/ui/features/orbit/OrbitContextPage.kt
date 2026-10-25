package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitContextSettings
import com.ai.assistance.operit.ui.features.chat.components.ChatContextPolicyEditor
import com.ai.assistance.operit.ui.features.chat.util.formatContextK
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One compaction setting for the main chat and its side chats, and a button to compact now. */
@Composable
internal fun OrbitContextPage(orbit: OrbitProfile, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val name = orbitName(orbit)
    var reload by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val state by produceState<OrbitContextSettings.State?>(null, orbit.id, orbit.chatId, reload) {
        try {
            value = OrbitContextSettings.state(context, orbit)
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
    }
    val core = remember(context) { ChatRuntimeHolder.getInstance(context).getCore(ChatRuntimeSlot.MAIN) }
    val streaming by core.activeStreamingChatIds.collectAsState()
    val summarizing by core.isSummarizing.collectAsState()
    val sendSummarizing by core.isSendTriggeredSummarizing.collectAsState()
    // While compacting the button waits; afterwards a short line says how it went.
    var compactRunning by remember { mutableStateOf(false) }
    var compactResult by remember { mutableStateOf<Int?>(null) }
    val chatBusy = orbit.chatId in streaming || summarizing || sendSummarizing
    // The saved note goes away after a moment, so it never sits next to edits that are not saved.
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(2_500); saved = false } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OrbitPageHeader(stringResource(R.string.orbit_context_title), onBack)
        Text(stringResource(R.string.orbit_context_note, name),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val current = state
        if (current == null) {
            if (error == null) CircularProgressIndicator()
            return@Column
        }
        Text(
            if (current.policy.autoCompact) stringResource(R.string.orbit_context_now,
                formatContextK(current.capacityK), formatContextK(current.compactAtK.roundOne()))
            else stringResource(R.string.orbit_context_now_off, formatContextK(current.capacityK)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsCard(backgroundColor = settingsCardColor(false), contentPadding = PaddingValues(16.dp)) {
            ChatContextPolicyEditor(current.policy, saving = saving, onSave = { policy ->
                saving = true
                saved = false
                scope.launch {
                    try {
                        OrbitContextSettings.update(context, orbit.id) { policy }
                        error = null
                        saved = true
                        reload++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { saving = false }
                }
            })
            if (saved) {
                Text(stringResource(R.string.orbit_context_saved), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        SettingsCard(backgroundColor = settingsCardColor(false), contentPadding = PaddingValues(16.dp)) {
            Text(stringResource(R.string.orbit_context_compact_now_note, name),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                enabled = !compactRunning && !chatBusy,
                onClick = {
                    compactRunning = true
                    compactResult = null
                    val succeeded = kotlinx.coroutines.CompletableDeferred<Unit>()
                    core.getMessageCoordinationDelegate().manuallySummarizeConversation(orbit.chatId) {
                        succeeded.complete(Unit)
                    }
                    scope.launch {
                        try {
                            // Wait for the compaction to start, then for it to end. Success is
                            // reported just after the busy flag drops, so give it a moment.
                            withTimeoutOrNull(5_000) { core.isSummarizing.first { it } }
                            core.isSummarizing.first { !it }
                            val done = withTimeoutOrNull(2_000) { succeeded.await() } != null
                            compactResult = if (done) R.string.orbit_context_compact_done
                                else R.string.orbit_context_compact_failed
                        } finally { compactRunning = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (compactRunning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.orbit_context_compacting))
                } else Text(stringResource(R.string.orbit_context_compact_now))
            }
            when {
                compactRunning -> Unit
                chatBusy -> Text(stringResource(R.string.orbit_context_busy, name),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                compactResult != null -> Text(stringResource(checkNotNull(compactResult)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

private fun Float.roundOne(): Float = kotlin.math.round(this * 10f) / 10f

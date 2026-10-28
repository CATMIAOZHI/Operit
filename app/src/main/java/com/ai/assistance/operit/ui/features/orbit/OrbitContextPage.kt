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
import com.ai.assistance.operit.ui.features.chat.util.formatContextK
import com.ai.assistance.operit.ui.features.chat.util.parseContextK
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.settingsCardColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Budgets offered as one-tap choices; any other value can still be typed. */
private val BUDGET_PRESETS = listOf(200f, 300f, 400f, 500f)

/**
 * The context budget for the main chat and its side chats, and a button to compact now. Orbit
 * always compacts on its own and the app picks where, so the budget is the only choice.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            stringResource(R.string.orbit_context_now,
                formatContextK(current.capacityK), formatContextK(current.compactAtK.roundOne())),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsCard(backgroundColor = settingsCardColor(false), contentPadding = PaddingValues(16.dp)) {
            var budget by remember(current.policy.windowK) { mutableStateOf("${formatContextK(current.policy.windowK)}K") }
            val budgetK = parseContextK(budget)?.takeIf { it > 0f && it <= 1_000_000f }
            OutlinedTextField(
                value = budget, onValueChange = { budget = it }, singleLine = true, enabled = !saving,
                label = { Text(stringResource(R.string.orbit_context_budget)) },
                supportingText = { Text(stringResource(R.string.chat_context_budget_hint)) },
                isError = budgetK == null,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BUDGET_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = budgetK == preset,
                        onClick = { budget = "${formatContextK(preset)}K" },
                        label = { Text("${formatContextK(preset)}K") },
                        enabled = !saving,
                    )
                }
            }
            if (OrbitContextSettings.isDeepSeekFlash(current.modelName)) {
                Text(stringResource(R.string.orbit_context_recommend_flash, OrbitContextSettings.DEEPSEEK_FLASH_BUDGET_K),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Text(stringResource(R.string.orbit_context_auto_fixed),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = !saving && budgetK != null && budgetK != current.policy.windowK,
                onClick = {
                    val value = budgetK ?: return@Button
                    saving = true
                    saved = false
                    scope.launch {
                        try {
                            OrbitContextSettings.setBudget(context, orbit.id, value)
                            error = null
                            saved = true
                            reload++
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message }
                        finally { saving = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.save))
            }
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

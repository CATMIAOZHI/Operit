package com.ai.assistance.operit.ui.features.orbit

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.LearningPromptSnapshotRepository
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.UserProfileDocumentRepository
import com.ai.assistance.operit.features.orbit.OrbitPackages
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitPromptFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class LayerInfo(
    val promptBytes: Long,
    val soulBytes: Long,
    val identityBytes: Long,
    /** The synthesis as it goes into the prompt; 0 while it is still an empty template. */
    val alignmentBytes: Long,
    val memoryBytes: Long?,
    val userBytes: Long?,
    /** What the main chat last sent, or null before its first reply or after a refresh. */
    val saved: LearningPromptSnapshotRepository.SavedSystem?,
)

/** Which injected text the viewer shows. */
private enum class PromptView { FULL, RUNTIME, TOOLS }

/**
 * The last sizes read, for one Orbit only. Coming back from a file shows them at once while they
 * are read again, so the list never blinks into a spinner.
 */
@Volatile private var layerCache: Pair<String, LayerInfo>? = null

/**
 * What goes into Orbit's system prompt, in the order it is sent. Every layer is always included.
 * Files open their editor; the parts the app writes open a read-only view of the text it injected.
 */
@Composable
internal fun OrbitPromptLayersPage(
    orbit: OrbitProfile,
    onBack: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenSoul: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenAlignment: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenUser: () -> Unit,
) {
    var view by rememberSaveable { mutableStateOf<PromptView?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    // null while loading; a failure shows a retry instead of spinning forever.
    val info by produceState(
        layerCache?.takeIf { it.first == orbit.id }?.let { Result.success(it.second) }, orbit.id, reload,
    ) {
        if (value?.isSuccess != true) value = null
        value = withContext(Dispatchers.IO) { runCatching {
            fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size.toLong()
            LayerInfo(
                promptBytes = bytes(OrbitPromptFiles.prompt(context, orbit.id)),
                soulBytes = bytes(OrbitPreferences.getInstance(context).readSoul(orbit.id)),
                identityBytes = bytes(OrbitPromptFiles.identity(context, orbit.id)),
                alignmentBytes = OrbitFiles.alignmentForPrompt(OrbitFiles.of(context, orbit.id).read(OrbitFiles.ALIGNMENT))
                    ?.let { bytes(it) } ?: 0L,
                memoryBytes = orbit.memorySpaceId?.let { bytes(MemoryNotesRepository(context, it).load().markdown) },
                userBytes = orbit.memorySpaceId?.let {
                    bytes(UserProfileDocumentRepository.forMemorySpace(context, it).load())
                },
                // Orbit chats send under the default card.
                saved = LearningPromptSnapshotRepository(context, orbit.chatId)
                    .latestSystem(CharacterCardManager.DEFAULT_CHARACTER_CARD_ID),
            )
        } }.onFailure { if (it is CancellationException) throw it }
            .onSuccess { layerCache = orbit.id to it }
    }
    // Back from a file opened from this list: read the sizes again, the file may have changed.
    OrbitOnPageReturn { reload++ }
    // The same iOS push as Orbit's other pages, with the swipe back following the finger.
    OrbitPushHost(
        entries = listOfNotNull(view),
        onPop = { view = null },
        modifier = Modifier.background(MaterialTheme.colorScheme.background),
        base = {
            LayersList(orbit, info, onBack, onOpenPrompt, onOpenSoul, onOpenIdentity, onOpenAlignment, onOpenMemory, onOpenUser,
                onView = { view = it }, onReload = { reload++ })
        },
    ) { shown ->
        InjectedTextPage(orbitName(orbit), shown, info?.getOrNull()?.saved) { view = null }
    }
}

@Composable
private fun LayersList(
    orbit: OrbitProfile,
    loaded: Result<LayerInfo>?,
    onBack: () -> Unit,
    onOpenPrompt: () -> Unit,
    onOpenSoul: () -> Unit,
    onOpenIdentity: () -> Unit,
    onOpenAlignment: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenUser: () -> Unit,
    onView: (PromptView) -> Unit,
    onReload: () -> Unit,
) {
    OrbitPageBack(onBack = onBack)
    val who = orbitName(orbit)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Refreshing while Orbit is replying (here or in the floating window) would swap the prompt under that reply.
    val replying = remember(orbit) { OrbitPackages.replying(context, orbit) }
    val busy by replying.collectAsState(initial = false)
    var refreshing by remember { mutableStateOf(false) }
    var confirmRefresh by remember { mutableStateOf(false) }
    fun size(bytes: Long?) = bytes?.let { Formatter.formatShortFileSize(context, it) }
    fun chars(text: String?) = text?.let { Formatter.formatShortFileSize(context, it.toByteArray(Charsets.UTF_8).size.toLong()) }
    val listState = rememberLazyListState()

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding(),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            OrbitPageHeader(stringResource(R.string.orbit_prompt_layers), onBack)
            Text(
                stringResource(R.string.orbit_prompt_layers_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp),
            )
        }
        if (loaded == null) {
            item(key = "loading") {
                OrbitDelayedSpinner()
            }
            return@LazyColumn
        }
        val info = loaded.getOrNull()
        if (info == null) {
            item(key = "failed") { OrbitLoadFailed(onReload) }
            return@LazyColumn
        }
        item(key = "layers") {
            val sections = info.saved?.sections.orEmpty()
            // The order they are sent in: what rarely changes first, what changes often last.
            LayerCard {
                LayerRow(1, stringResource(R.string.orbit_layer_base), OrbitPromptFiles.PROMPT_FILE,
                    stringResource(R.string.orbit_layer_base_desc),
                    size(info.promptBytes), onOpenPrompt)
                LayerDivider()
                LayerRow(2, stringResource(R.string.orbit_layer_tools), null,
                    stringResource(R.string.orbit_layer_tools_desc), chars(sections["tools"])) { onView(PromptView.TOOLS) }
                LayerDivider()
                LayerRow(3, stringResource(R.string.orbit_layer_runtime), null,
                    stringResource(R.string.orbit_layer_runtime_desc), chars(sections["runtime"])) { onView(PromptView.RUNTIME) }
                LayerDivider()
                LayerRow(4, stringResource(R.string.orbit_layer_identity), OrbitPromptFiles.IDENTITY_FILE,
                    stringResource(R.string.orbit_layer_identity_desc), size(info.identityBytes), onOpenIdentity)
                LayerDivider()
                LayerRow(5, stringResource(R.string.orbit_layer_soul), "SOUL.md",
                    stringResource(R.string.orbit_layer_soul_desc), size(info.soulBytes), onOpenSoul)
                LayerDivider()
                LayerRow(6, stringResource(R.string.orbit_layer_alignment), OrbitFiles.ALIGNMENT,
                    stringResource(R.string.orbit_layer_alignment_desc),
                    if (info.alignmentBytes > 0) size(info.alignmentBytes) else stringResource(R.string.orbit_layer_alignment_empty),
                    onOpenAlignment)
                LayerDivider()
                LayerRow(7, stringResource(R.string.orbit_layer_memory), "memory.md",
                    stringResource(R.string.orbit_layer_memory_desc, who), size(info.memoryBytes), onOpenMemory)
                LayerDivider()
                LayerRow(8, stringResource(R.string.orbit_layer_user), "user.md",
                    stringResource(R.string.orbit_layer_user_desc), size(info.userBytes), onOpenUser)
            }
        }
        item(key = "whole") {
            Spacer(Modifier.size(22.dp))
            LayerCard {
                ActionRow(
                    icon = "scroll-text",
                    title = stringResource(R.string.orbit_prompt_view_full),
                    detail = info.saved?.let { stringResource(R.string.orbit_prompt_view_full_desc, chars(it.system).orEmpty()) }
                        ?: stringResource(R.string.orbit_prompt_not_built, who),
                    enabled = true,
                ) { onView(PromptView.FULL) }
                LayerDivider()
                ActionRow(
                    icon = "refresh-cw",
                    title = stringResource(R.string.orbit_prompt_refresh),
                    detail = stringResource(if (busy) R.string.orbit_prompt_refresh_busy else R.string.orbit_prompt_refresh_desc, who),
                    enabled = !busy && !refreshing,
                ) {
                    if (busy || refreshing) return@ActionRow
                    confirmRefresh = true
                }
            }
        }
    }

    if (confirmRefresh) {
        TaskConfirmSheet(
            icon = "refresh-cw",
            title = stringResource(R.string.orbit_prompt_refresh_confirm_title),
            message = stringResource(R.string.orbit_prompt_refresh_confirm_message, who),
            confirmText = stringResource(R.string.orbit_prompt_refresh),
            dismissText = stringResource(R.string.cancel),
            destructive = false,
            onDismiss = { confirmRefresh = false },
            onConfirm = {
                confirmRefresh = false
                if (busy || refreshing) return@TaskConfirmSheet
                refreshing = true
                scope.launch {
                    try {
                        // Every chat of this Orbit reads its files and tools again on its next reply.
                        orbit.talkChatIds.forEach { LearningPromptSnapshotRepository(context, it).delete() }
                        Toast.makeText(context, R.string.orbit_prompt_refreshed, Toast.LENGTH_SHORT).show()
                        onReload()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.orbit_prompt_refresh_failed, Toast.LENGTH_SHORT).show()
                    } finally {
                        refreshing = false
                    }
                }
            },
        )
    }
}

/** A spinner that shows only when loading takes a while, so a quick load never flashes one. */
@Composable
internal fun OrbitDelayedSpinner() {
    var slow by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); slow = true }
    Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) {
        if (slow) CircularProgressIndicator()
    }
}

@Composable
private fun LayerCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) { Column { content() } }
}

@Composable
private fun LayerDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun LayerRow(index: Int, title: String, file: String?, description: String, size: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            index.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (file != null) {
                    Text(
                        file,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (size != null) {
            Text(
                size,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Icon(
            orbitIcon("chevron-right"),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 6.dp).size(20.dp),
        )
    }
}

@Composable
private fun ActionRow(icon: String, title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(orbitIcon(icon), contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Read-only view of injected prompt text, split into paragraphs so a long prompt scrolls smoothly. */
@Composable
private fun InjectedTextPage(who: String, view: PromptView, saved: LearningPromptSnapshotRepository.SavedSystem?, onBack: () -> Unit) {
    OrbitPageBack(onBack = onBack)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val title = stringResource(
        when (view) {
            PromptView.FULL -> R.string.orbit_prompt_view_full
            PromptView.RUNTIME -> R.string.orbit_layer_runtime
            PromptView.TOOLS -> R.string.orbit_layer_tools
        }
    )
    val text = when (view) {
        PromptView.FULL -> saved?.system
        PromptView.RUNTIME -> saved?.sections?.get("runtime")
        PromptView.TOOLS -> saved?.sections?.get("tools")
    }
    val paragraphs = remember(text) { text?.split(Regex("\n{2,}"))?.filter { it.isNotBlank() }.orEmpty() }
    val note = when {
        saved == null -> stringResource(R.string.orbit_prompt_not_built, who)
        text == null -> stringResource(R.string.orbit_prompt_section_missing, who)
        view == PromptView.TOOLS && saved.protocol.startsWith("true:") -> stringResource(R.string.orbit_prompt_tools_note)
        else -> stringResource(R.string.orbit_prompt_view_note)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) { OrbitPageHeader(title, onBack) }
            if (text != null) {
                OrbitCircleButton(orbitIcon("copy"), stringResource(R.string.copy), {
                    clipboard.setText(AnnotatedString(text))
                    Toast.makeText(context, R.string.orbit_prompt_copied, Toast.LENGTH_SHORT).show()
                }, Modifier.padding(bottom = 8.dp))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
        ) {
            item(key = "note") {
                Text(
                    note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
                )
            }
            itemsIndexed(paragraphs, key = { index, _ -> index }) { index, paragraph ->
                val first = index == 0
                val last = index == paragraphs.lastIndex
                // One gray card drawn in slices, so each paragraph is its own lazy item.
                Surface(
                    shape = RoundedCornerShape(
                        topStart = if (first) 20.dp else 0.dp, topEnd = if (first) 20.dp else 0.dp,
                        bottomStart = if (last) 20.dp else 0.dp, bottomEnd = if (last) 20.dp else 0.dp,
                    ),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    SelectionContainer {
                        Text(
                            paragraph,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp,
                                top = if (first) 16.dp else 6.dp, bottom = if (last) 16.dp else 6.dp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

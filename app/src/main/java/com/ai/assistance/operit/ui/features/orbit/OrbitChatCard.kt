package com.ai.assistance.operit.ui.features.orbit

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ai.assistance.operit.R
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * A card one of Orbit's tools put in the chat ([OrbitChatCards]): a secure entry card that opens
 * the entry sheet, a picture that opens full screen on a tap (with a save button there), a text file shown
 * right here, or any other file, which opens in another app.
 */
@Composable
fun OrbitChatCard(source: String) {
    val card = remember(source) { runCatching { JSONObject(source.trim()) }.getOrNull() } ?: return
    when (card.optString("kind")) {
        OrbitChatCards.VAULT_LOGIN, OrbitChatCards.VAULT_KEY, OrbitChatCards.VAULT_OAUTH ->
            // Keyed by the card, so a reused slot never keeps showing an earlier card.
            androidx.compose.runtime.key(source) { VerifiedVaultCard(source, card) }
        OrbitChatCards.IMAGE, OrbitChatCards.FILE -> AttachmentCard(card)
        OrbitChatCards.APPROVAL -> OrbitApprovalCard(card)
        OrbitChatCards.PERMISSION -> OrbitPermissionCard(card)
        OrbitChatCards.CHATGPT_LOGIN -> ChatGptLoginCard(card)
        OrbitChatCards.ACCOUNT_LOGIN -> OrbitAccountLoginCard(card)
        OrbitChatCards.SPEECH_SETTINGS -> OrbitSpeechSettingsCard()
        OrbitChatCards.WEIXIN_LOGIN -> OrbitWeixinLoginCard(card)
        OrbitChatCards.DATA_EXPORT -> OrbitDataExportCard(card)
        OrbitChatCards.OPEN_PAGE -> OrbitOpenPageCard(card)
    }
}

/** Cards already checked, so scrolling back to one shows it at once. */
private val verifiedVaultCards = android.util.LruCache<String, Result<OrbitVault.Request?>>(64)

/**
 * The signature check reads the device key, so it runs off the main thread, once per card. Until it
 * is done a blank card of the same size holds the place, so nothing below it moves.
 */
@Composable
private fun VerifiedVaultCard(source: String, card: JSONObject) {
    val checked by produceState(verifiedVaultCards.get(source), source) {
        if (value == null) {
            value = Result.success(
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { OrbitChatCards.vaultRequest(card) }
            ).also { verifiedVaultCards.put(source, it) }
        }
    }
    val result = checked
    when {
        result == null -> VaultCardPlaceholder()
        else -> result.getOrNull()?.let { VaultCard(it) }
    }
}

/** The vault card's own layout with nothing to tap, so it takes the same room at any font size. */
@Composable
private fun VaultCardPlaceholder() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.widthIn(max = 300.dp).fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(" ", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(" ", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(colors.surface))
    }
}

/**
 * The message's double-tap (like) and long-press (menu) on a card that also takes a tap of its
 * own: a tap on the card never reaches the message around it otherwise.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.orbitCardGestures(onClick: (() -> Unit)? = null, enabled: Boolean = true): Modifier {
    val gestures = com.ai.assistance.operit.ui.features.chat.components.LocalOrbitMessageGestures.current
    if (gestures == null && onClick == null) return this
    return this.combinedClickable(
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = if (onClick != null) androidx.compose.material3.ripple() else null,
        enabled = enabled || gestures != null,
        onClick = { if (enabled) onClick?.invoke() },
        onDoubleClick = gestures?.onDoubleTap,
        onLongClick = gestures?.onLongPress,
    )
}

@Composable
private fun VaultCard(request: OrbitVault.Request) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val login = request.kind == OrbitVault.LOGIN
    val oauth = request.kind == OrbitVault.OAUTH
    val fresh = login && request.newPassword
    val updating = request.updateId.isNotEmpty()
    val version by OrbitVault.version.collectAsState()
    // An update card: whether its entry still exists (null while reading).
    var gone by remember(request) { mutableStateOf(false) }
    val saved by produceState(false, request, version) {
        // A new-password card is done once a password is saved after it; an older login does not count.
        value = runCatching {
            val entries = OrbitVault.entries(context, request.orbitId)
            when {
                updating -> entries.firstOrNull { it.id == request.updateId }
                    .also { gone = it == null }?.let { it.savedAt >= request.requestedAt } ?: false
                fresh -> OrbitVault.newPasswordSaved(entries, request)
                oauth -> entries.any { it.kind == OrbitVault.OAUTH && it.card == request.requestedAt &&
                    it.site == request.site && it.oauth?.clientId == request.clientId }
                else -> OrbitVault.has(entries, request.kind, request.site, request.envName, request.packageName)
            }
        }.getOrDefault(false)
    }
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).orbitCardGestures().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTile(if (login) "lock-keyhole" else "layout-grid")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (login) stringResource(R.string.orbit_vault_card_login_title) else request.site,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        updating && gone -> stringResource(R.string.orbit_vault_card_update_gone)
                        updating && saved -> stringResource(R.string.orbit_vault_card_updated)
                        updating && login -> stringResource(R.string.orbit_vault_card_update_login_sub, request.site)
                        updating -> stringResource(R.string.orbit_vault_card_update_key_sub)
                        fresh && saved -> stringResource(R.string.orbit_vault_card_new_password_saved)
                        fresh -> stringResource(R.string.orbit_vault_card_new_password_sub, request.site)
                        saved -> stringResource(R.string.orbit_vault_card_saved)
                        login -> stringResource(R.string.orbit_vault_card_login_sub, request.site)
                        oauth -> stringResource(R.string.orbit_oauth_card_sub)
                        else -> stringResource(R.string.orbit_vault_card_key_sub)
                    },
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                .orbitCardGestures(onClick = { OrbitVault.open(request) }, enabled = !(updating && gone))
                // Long press still reaches the message; a screen reader hears the button is off.
                .semantics { if (updating && gone) disabled() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        when {
                            updating -> R.string.orbit_vault_card_update
                            oauth && saved -> R.string.orbit_oauth_reconnect
                            oauth -> R.string.orbit_oauth_connect
                            fresh && saved -> R.string.orbit_vault_reset
                            fresh -> R.string.orbit_vault_set
                            saved -> R.string.orbit_vault_card_update
                            login -> R.string.orbit_vault_add
                            else -> R.string.orbit_vault_card_link
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (updating && gone) colors.onSurface.copy(alpha = 0.38f)
                        else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
        }
    }
}

@Composable
internal fun CardTile(icon: String, label: String? = null) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(52.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(orbitIcon(icon), null, Modifier.size(if (label == null) 26.dp else 22.dp), tint = MaterialTheme.colorScheme.onSurface)
            if (label != null) {
                Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * Signs in to ChatGPT, the login Codex models and the Codex Draw package use. It is the same login
 * as in the model settings; once done Orbit hears it in a hidden note.
 */
@Composable
private fun ChatGptLoginCard(card: JSONObject) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val orbitId = card.optString("orbit_id")
    val chatId = card.optString("chat_id")
    if (orbitId.isBlank()) return
    // Opening the login store decrypts it, so that is done off the main thread.
    val auth by produceState<com.ai.assistance.operit.data.preferences.CodexAuthPreferences?>(null) {
        value = withContext(Dispatchers.IO) {
            com.ai.assistance.operit.data.preferences.CodexAuthPreferences.getInstance(context.applicationContext)
        }
    }
    val signedIn = auth?.authState?.collectAsState()?.value != null
    // Asked to sign in again: an old sign-in is saved but no longer works. Done once a new one is made here.
    var relogged by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val relogin = card.optBoolean("relogin") && !relogged
    val canLogin = !signedIn || relogin
    val orbitFlow = remember(orbitId) {
        com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).settingsFlow
    }
    val orbit = orbitFlow.collectAsState(initial = null).value?.orbits?.firstOrNull { it.id == orbitId }
    // 1: browser login, 2: device code login.
    var login by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val onSuccess: (com.ai.assistance.operit.data.preferences.CodexAuthState) -> Unit = {
        login = 0
        relogged = true
        orbit?.let { o -> com.ai.assistance.operit.features.orbit.OrbitVaultNotice.sendChatGptLogin(context, o, chatId) }
        scope.launch { runCatching { com.ai.assistance.operit.api.chat.EnhancedAIService.refreshAllServices(context.applicationContext) } }
    }
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).orbitCardGestures().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTile("log-in")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.orbit_chatgpt_card_title), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(when {
                        relogin -> R.string.orbit_login_card_relogin_sub
                        signedIn -> R.string.orbit_chatgpt_card_done
                        else -> R.string.orbit_chatgpt_card_sub
                    }),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50))
                .orbitCardGestures(onClick = { login = 1 }, enabled = canLogin && orbit != null && auth != null)
                .semantics { if (!canLogin) disabled() },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(stringResource(when {
                    relogin -> R.string.orbit_login_card_relogin
                    signedIn -> R.string.orbit_chatgpt_card_signed_in
                    else -> R.string.orbit_chatgpt_card_login
                }),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (!canLogin) colors.onSurface.copy(alpha = 0.38f) else androidx.compose.ui.graphics.Color.Unspecified)
            }
        }
        if (canLogin) {
            androidx.compose.material3.TextButton(
                onClick = { login = 2 }, enabled = orbit != null && auth != null,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            ) {
                Text(stringResource(R.string.orbit_chatgpt_card_device), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
        }
    }
    when (login) {
        1 -> com.ai.assistance.operit.ui.features.codex.CodexLoginDialog(onDismissRequest = { login = 0 },
            onLoginSuccess = onSuccess, presenter = { OrbitChatGptLoginSheet(it) })
        2 -> com.ai.assistance.operit.ui.features.codex.CodexDeviceLoginDialog(onDismissRequest = { login = 0 },
            onLoginSuccess = onSuccess, presenter = { OrbitChatGptLoginSheet(it) })
    }
}

/** The ChatGPT login while it runs, as an Orbit sheet. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OrbitChatGptLoginSheet(view: com.ai.assistance.operit.ui.features.codex.CodexLoginView) {
    val colors = MaterialTheme.colorScheme
    OrbitModalBottomSheet(
        onDismissRequest = view.onCancel,
        dismissEnabled = view.canCancel,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(18.dp), color = colors.surface, shadowElevation = 1.dp, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(orbitIcon("log-in"), null, Modifier.size(30.dp), tint = colors.onSurface)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(view.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            val code = view.code
            if (code != null) {
                Surface(shape = RoundedCornerShape(22.dp), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                        view.instructions?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(code.verificationUrl, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(code.userCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    view.onCopy?.let { copy ->
                        Surface(onClick = copy, shape = RoundedCornerShape(50), color = colors.surface,
                            modifier = Modifier.weight(1f).heightIn(min = 46.dp)) {
                            Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.codex_device_login_copy), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    view.onOpen?.let { open ->
                        Surface(onClick = open, shape = RoundedCornerShape(50), color = colors.primary,
                            contentColor = colors.onPrimary, modifier = Modifier.weight(1f).heightIn(min = 46.dp)) {
                            Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.codex_device_login_open), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (view.working) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(view.status, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            view.safety?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Surface(onClick = view.onCancel, enabled = view.canCancel, shape = RoundedCornerShape(50), color = colors.surface,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
                Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.cancel_action), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun AttachmentCard(card: JSONObject) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name = card.optString("name").ifBlank { card.optString("path").substringAfterLast('/') }
    val path = card.optString("path")
    // Only Orbit's own files/ folder; a card naming anything else draws as missing.
    val isImage = card.optString("kind") == OrbitChatCards.IMAGE
    // The picture's shape comes with the card, so its box is the same before and after it loads.
    val ratio = card.optInt("width").takeIf { it > 0 }?.let { w ->
        card.optInt("height").takeIf { it > 0 }?.let { h -> w.toFloat() / h }
    }
    val lookup by produceState(Found(false, null), card.optString("orbit_id"), path) {
        value = withContext(Dispatchers.IO) {
            val found = runCatching {
                OrbitFiles.of(context, card.optString("orbit_id")).chatFile(path)
            }.getOrNull()
            Found(true, found)
        }
    }
    val current = lookup.file
    if (isImage && !lookup.done) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(vertical = 4.dp).then(imageSize(ratio))) {}
        return
    }
    if (isImage && current != null) {
        var viewing by remember { mutableStateOf(false) }
        val label = stringResource(R.string.orbit_chat_image_desc, name)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp))
                .orbitCardGestures(onClick = { viewing = true })
                .semantics(mergeDescendants = true) { contentDescription = label },
        ) {
            AsyncImage(
                model = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageSize(ratio).clip(RoundedCornerShape(18.dp)),
            )
        }
        if (viewing) OrbitImageViewer(current, label) { viewing = false }
        return
    }
    var previewing by remember { mutableStateOf(false) }
    val previewable = current != null && OrbitMediaPreview.canPreview(name)
    val ext = name.substringAfterLast('.', "").uppercase().take(5)
    val size = card.optLong("size", -1L)
    val label = stringResource(if (previewable) R.string.orbit_chat_text_desc else R.string.orbit_chat_file_desc, name)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.widthIn(max = 300.dp).padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
            .orbitCardGestures(onClick = {
                if (previewable) previewing = true else current?.let { f -> openOrbitFile(context) { f } }
            }, enabled = current != null)
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardTile(if (card.optString("kind") == OrbitChatCards.IMAGE) "image" else "file-text", ext.ifEmpty { null })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        lookup.done && current == null -> stringResource(R.string.orbit_chat_file_missing)
                        size >= 0 -> android.text.format.Formatter.formatShortFileSize(context, size) + " · " +
                            stringResource(R.string.orbit_chat_file_open)
                        else -> stringResource(R.string.orbit_chat_file_open)
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
    }
    if (previewing && current != null) OrbitMediaPreview.Screen(current) { previewing = false }
}

private class Found(val done: Boolean, val file: File?)

/**
 * The picture full screen, like a chat app's photo viewer: pinch to zoom, drag when zoomed, tap or
 * back to close, and a save button at the bottom.
 */
@Composable
internal fun OrbitImageViewer(file: File, label: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    OrbitModalWindow(
        onDismissRequest = onDismiss,
        dismissEnabled = !saving,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { if (!saving) onDismiss() }) },
        ) {
            AsyncImage(
                model = file,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            val maxX = size.width * (scale - 1) / 2
                            val maxY = size.height * (scale - 1) / 2
                            offset = androidx.compose.ui.geometry.Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                    },
            )
            Surface(
                onClick = onDismiss,
                enabled = !saving,
                shape = androidx.compose.foundation.shape.CircleShape,
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, stringResource(R.string.common_close),
                        Modifier.size(22.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                OrbitViewerButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) { shareOrbitFile(context) { file } }
                Spacer(Modifier.width(10.dp))
                OrbitViewerButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                    openOrbitFile(context) { file }
                }
            }
            Surface(
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = saveToGallery(context, file)
                        saving = false
                        Toast.makeText(context, if (ok) R.string.orbit_chat_image_saved else R.string.orbit_chat_image_save_failed,
                            Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !saving,
                shape = RoundedCornerShape(50),
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).heightIn(min = 44.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (saving) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = androidx.compose.ui.graphics.Color.White)
                    } else {
                        Icon(orbitIcon("download"), null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (saving) R.string.orbit_chat_image_saving else R.string.orbit_chat_image_save_short),
                        style = MaterialTheme.typography.labelLarge, color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/** At most 260 x 340dp, in the picture's own shape; a very tall or wide one is cropped a little. */
private fun imageSize(ratio: Float?): Modifier {
    val r = (ratio ?: 1f).coerceIn(0.6f, 2.2f)
    val width = if (260f / r > 340f) 340f * r else 260f
    return Modifier.size(width.dp, (width / r).dp)
}

/** Copies one of Orbit's pictures into the gallery (Pictures/Operit). */
internal suspend fun saveToGallery(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Operit")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("No output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Operit").apply { mkdirs() }
            var target = File(folder, file.name)
            var n = 2
            while (target.exists()) target = File(folder, "${file.nameWithoutExtension}-${n++}.${file.extension}")
            file.copyTo(target)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            true
        }
    }.getOrDefault(false)
}

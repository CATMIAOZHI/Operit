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
            OrbitChatCards.vaultRequest(card)?.let { VaultCard(it) }
        OrbitChatCards.IMAGE, OrbitChatCards.FILE -> AttachmentCard(card)
        OrbitChatCards.APPROVAL -> OrbitApprovalCard(card)
        OrbitChatCards.PERMISSION -> OrbitPermissionCard(card)
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
    val version by OrbitVault.version.collectAsState()
    val saved by produceState(false, request, version) {
        // A new-password card is done once a password is saved after it; an older login does not count.
        value = runCatching {
            val entries = OrbitVault.entries(context, request.orbitId)
            when {
                fresh -> OrbitVault.newPasswordSaved(entries, request)
                oauth -> entries.any { it.kind == OrbitVault.OAUTH && it.card == request.requestedAt &&
                    it.site == request.site && it.oauth?.clientId == request.clientId }
                else -> OrbitVault.has(entries, request.kind, request.site, request.envName)
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
                .orbitCardGestures(onClick = { OrbitVault.open(request) }),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        when {
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
                )
            }
        }
    }
}

@Composable
private fun CardTile(icon: String, label: String? = null) {
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
                require(path.startsWith("files/"))
                OrbitFiles.of(context, card.optString("orbit_id")).fileFor(path).takeIf { it.isFile }
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
    val previewable = current != null && OrbitTextPreview.canShow(name)
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
    if (previewing && current != null) OrbitTextPreview.Screen(current) { previewing = false }
}

private class Found(val done: Boolean, val file: File?)

/**
 * The picture full screen, like a chat app's photo viewer: pinch to zoom, drag when zoomed, tap or
 * back to close, and a save button at the bottom.
 */
@Composable
private fun OrbitImageViewer(file: File, label: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
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
                shape = androidx.compose.foundation.shape.CircleShape,
                color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.material.icons.Icons.Default.Close, stringResource(R.string.common_close),
                        Modifier.size(22.dp), tint = androidx.compose.ui.graphics.Color.White)
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
private suspend fun saveToGallery(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
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

package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PastedAttachments"

/** What the keyboard may insert: pictures, GIFs, videos and other files, but not plain text. */
private val INSERTABLE_MIME_TYPES = arrayOf("image/*", "video/*", "audio/*", "application/*")

/**
 * Clip types that are only text. Everything else on the clipboard is a file to attach, including a
 * list of links to files (text/uri-list). A file typed as plain text pastes its text through the
 * system's paste instead: telling it from copied words would mean reading the clip on every
 * long press, which shows the "pasted from clipboard" notice.
 */
private val TEXT_ONLY_MIME_TYPES = setOf("text/plain", "text/html", "text/vnd.android.intent")

/**
 * A picture the keyboard inserted waits here only until the chat has copied it as an attachment,
 * which takes seconds. A copy whose chat closed before taking it is gone within the hour.
 */
private const val STAGED_DIR = "pasted_attachments"
private const val STAGED_KEEP_MS = 60L * 60 * 1000
private val stagedSweptThisRun = java.util.concurrent.atomic.AtomicBoolean(false)

/** Copies outlive the input field: leaving the chat mid-copy still ends in an attachment or a cleanup. */
private val pasteScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private object PasteAsAttachmentKey

/**
 * Lets the chat input take pictures and files, not only text: a picture the keyboard inserts (its
 * clipboard or image suggestions), "Paste as attachment" in the text menu while the clipboard holds
 * a picture or file, and Ctrl+V on a hardware keyboard. They become attachments through [onAttach],
 * like the ones picked with the + button. The chat's text fields are the value-based kind, which
 * take no such content by themselves; [content] gets the modifier for its text field.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AcceptPastedAttachments(
    enabled: Boolean,
    /**
     * The chat the field writes to, read live: a copy finishing after the user moved on is dropped,
     * not added to the chat open by then.
     */
    chatId: () -> String?,
    onAttach: (String) -> Unit,
    content: @Composable (fieldModifier: Modifier) -> Unit,
) {
    val context = LocalContext.current
    val attach by rememberUpdatedState(onAttach)
    val active by rememberUpdatedState(enabled)
    val liveChat by rememberUpdatedState(chatId)
    // False once the field is gone; a copy finishing later has no field to go to.
    val shown = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        shown.set(true)
        onDispose { shown.set(false) }
    }
    val label = stringResource(R.string.paste_as_attachment)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (stagedSweptThisRun.compareAndSet(false, true)) withContext(Dispatchers.IO) { sweepStaged(context) }
    }

    val interceptor = remember(context) {
        PlatformTextInputInterceptor { request, nextHandler ->
            nextHandler.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val connection = request.createInputConnection(outAttributes)
                        if (!active) return connection
                        EditorInfoCompat.setContentMimeTypes(outAttributes, INSERTABLE_MIME_TYPES)
                        return InputConnectionCompat.createWrapper(connection, outAttributes) { info, flags, _ ->
                            if (!active) return@createWrapper false
                            if (!readable(context, info.contentUri)) {
                                failed(context, info.contentUri)
                                return@createWrapper true
                            }
                            val granted =
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 &&
                                    flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                            if (granted) {
                                try {
                                    info.requestPermission()
                                } catch (e: Exception) {
                                    failed(context, info.contentUri)
                                    return@createWrapper true
                                }
                            }
                            // The keyboard's permission ends with this input, so the picture is copied first.
                            val startChat = liveChat()
                            pasteScope.launch {
                                try {
                                    val mimeType = info.description.takeIf { it.mimeTypeCount > 0 }?.getMimeType(0)
                                    val staged = stage(context, info.contentUri, mimeType)
                                    when {
                                        // The user left: no attachment and no message on another screen.
                                        !shown.get() || liveChat() != startChat ->
                                            staged?.let { withContext(Dispatchers.IO) { releaseStagedPaste(context, it) } }
                                        staged == null -> failed(context, info.contentUri)
                                        else -> attach(staged.absolutePath)
                                    }
                                } finally {
                                    if (granted) runCatching { info.releasePermission() }
                                }
                            }
                            true
                        }
                    }
                }
            )
        }
    }

    val fieldModifier =
        if (!enabled) {
            Modifier
        } else {
            Modifier
                .appendTextContextMenuComponents {
                    // Read when the menu opens; only the clip's description, which needs no paste notice.
                    if (clipboardHoldsFiles(context)) {
                        item(key = PasteAsAttachmentKey, label = label) {
                            pasteClipboardFiles(context, attach)
                            close()
                        }
                    }
                }
                .onPreviewKeyEvent { event ->
                    val paste = event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.V
                    if (paste && clipboardHoldsFiles(context)) {
                        pasteClipboardFiles(context, attach)
                        true
                    } else {
                        false
                    }
                }
        }

    InterceptPlatformTextInput(interceptor) { content(fieldModifier) }
}

/** True when the clipboard holds a picture or file rather than text. Reads only the clip's description. */
internal fun clipboardHoldsFiles(context: Context): Boolean {
    val description =
        runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription }.getOrNull()
            ?: return false
    return (0 until description.mimeTypeCount).any { description.getMimeType(it) !in TEXT_ONLY_MIME_TYPES }
}

/**
 * Attaches every picture or file on the clipboard. The clipboard keeps the right to read them, so
 * they go to the attachment as they are. Only content the app was given counts: a path, or a link
 * into the app's own storage, would have it read its own private files for whoever made the clip.
 */
internal fun pasteClipboardFiles(context: Context, attach: (String) -> Unit) {
    val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull() ?: return
    val all = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    val uris = all.filter { readable(context, it) }
    if (uris.size < all.size || uris.isEmpty()) {
        Toast.makeText(context, R.string.paste_attachment_failed, Toast.LENGTH_SHORT).show()
        if (uris.size < all.size) AppLogger.w(TAG, "Left out ${all.size - uris.size} pasted item(s) it may not read")
    }
    uris.forEach { attach(it.toString()) }
}

/** Content another app shared; never a file path or the app's own provider. */
private fun readable(context: Context, uri: Uri): Boolean {
    if (uri.scheme != "content") return false
    val authority = uri.authority ?: return false
    val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName }.getOrNull()
    return owner != null && owner != context.packageName
}

private fun failed(context: Context, uri: Uri) {
    AppLogger.w(TAG, "Could not read the pasted content $uri")
    Toast.makeText(context, R.string.paste_attachment_failed, Toast.LENGTH_SHORT).show()
}

/**
 * Copies the content into the app's cache under its own name, with the extension its type calls
 * for so the chat still knows a picture for one; null when it cannot be read. The chat takes the
 * copy over and deletes it (see [releaseStagedPaste]).
 */
private suspend fun stage(context: Context, uri: Uri, mimeType: String?): File? =
    withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, STAGED_DIR)
        // Each paste in its own folder keeps the original name without clashing.
        val folder = File(root, System.nanoTime().toString())
        try {
            sweepStaged(context)
            folder.mkdirs()
            val type = mimeType ?: context.contentResolver.getType(uri)
            val extension = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            var name = (displayName(context, uri) ?: defaultName()).replace('/', '_').replace('\\', '_')
            if (extension != null && !name.substringAfterLast('.', "").equals(extension, ignoreCase = true) &&
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) != type
            ) {
                name = "$name.$extension"
            }
            val target = File(folder, fitName(name))
            val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream")
            input.use { source -> target.outputStream().use { source.copyTo(it) } }
            if (target.length() == 0L) throw java.io.IOException("empty")
            target
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not copy the pasted content: ${e.message}")
            folder.deleteRecursively()
            null
        }
    }

/**
 * Shortens a name to what a file system takes (255 bytes, less a margin), keeping its extension.
 * The limit counts bytes: a long Chinese name is three times longer than it looks.
 */
private fun fitName(name: String, maxBytes: Int = 200): String {
    fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
    if (bytes(name) <= maxBytes) return name
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
    var base = name.substring(0, name.length - extension.length)
    while (base.isNotEmpty() && bytes(base) + bytes(extension) > maxBytes) {
        base = base.dropLast(if (base.length >= 2 && base[base.length - 1].isLowSurrogate()) 2 else 1)
    }
    return base.ifEmpty { defaultName() } + extension
}

/**
 * Deletes a copy [stage] made once the chat has its own; called with any attachment's source path,
 * it leaves everything else alone.
 */
internal fun releaseStagedPaste(context: Context, file: File) {
    val root = File(context.cacheDir, STAGED_DIR).canonicalFile
    val folder = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return
    if (folder.parentFile == root) folder.deleteRecursively()
}

/**
 * The same once the chat is done with an attachment, however it ended: also when it never ran,
 * its screen closed meanwhile. Only a path in the paste folder is touched.
 */
internal fun releaseStagedPasteLater(context: Context, path: String) {
    if (path.startsWith("content://") || !path.contains(STAGED_DIR)) return
    pasteScope.launch(Dispatchers.IO) { releaseStagedPaste(context, File(path)) }
}

/** Deletes copies older than [STAGED_KEEP_MS]: their chat never took them. */
private fun sweepStaged(context: Context) {
    val old = System.currentTimeMillis() - STAGED_KEEP_MS
    File(context.cacheDir, STAGED_DIR).listFiles()?.filter { it.lastModified() < old }?.forEach { it.deleteRecursively() }
}

private fun displayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." }

private fun defaultName(): String = "pasted_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

package com.ai.assistance.operit.ui.common.markdown

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.assistance.operit.R
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.audio.AudioAttributes
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val MARKDOWN_AUDIO_EXTENSIONS =
    setOf("mp3", "wav", "ogg", "oga", "m4a", "aac", "flac", "opus", "weba")

internal fun normalizeMarkdownMediaUrl(url: String): String {
    return url
        .substringBefore('#')
        .substringBefore('?')
        .trim()
        .lowercase()
}

internal fun isLikelyAudioUrl(url: String): Boolean {
    val extension = normalizeMarkdownMediaUrl(url).substringAfterLast('.', "")
    return extension in MARKDOWN_AUDIO_EXTENSIONS
}

// A code fence line: its marker, and what follows it (a closing fence has nothing after).
private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
// An inline code span: backticks, then anything up to as many backticks again.
private val inlineCode = Regex("(`+)(?!`)[\\s\\S]*?(?<!`)\\1(?!`)")

/**
 * The ranges of [text]'s fenced code blocks, each to its closing fence or the end. As in
 * Markdown, a block closes on a fence of the same mark at least as long as its opening.
 */
internal fun markdownFenceRanges(text: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var open: String? = null
    var start = 0
    var at = 0
    while (at <= text.length) {
        val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val m = fenceLine.find(text.substring(at, end).trimEnd('\r'))
        if (m != null) {
            val mark = m.groupValues[1]
            val opening = open
            if (opening == null) {
                open = mark
                start = at
            } else if (mark[0] == opening[0] && mark.length >= opening.length && m.groupValues[2].isBlank()) {
                out += start..end
                open = null
            }
        }
        at = end + 1
    }
    if (open != null) out += start..text.length
    return out
}

/** The ranges of [text] that are code (fenced blocks and inline spans): a media link in them is shown as text. */
internal fun markdownCodeRanges(text: String): List<IntRange> {
    if (!text.contains('`') && !text.contains("~~~")) return emptyList()
    val fences = markdownFenceRanges(text)
    val out = fences.toMutableList()
    // Inline code only between the blocks: a backtick inside one never pairs with one outside.
    var from = 0
    for (block in fences.sortedBy { it.first } + listOf(text.length..text.length)) {
        if (block.first > from) {
            inlineCode.findAll(text.substring(from, block.first)).forEach { m ->
                out += (m.range.first + from)..(m.range.last + from)
            }
        }
        from = maxOf(from, block.last + 1)
    }
    return out
}

/** One chat's place for voice: when it leaves (another chat, another page, the app lock), its voice stops. */
class VoiceScope

/** Given by the chat list; audio shown elsewhere stops when its own bar leaves. */
val LocalVoiceScope = staticCompositionLocalOf<VoiceScope?> { null }

/**
 * Given by a chat message for the voice bars in it. [open], a long press on a bar, opens the
 * message's own menu for that recording (one menu, as in a messenger; "to text" is one of its
 * items), and is null while the message takes no menu (picking messages). [words] are the words
 * this message shows under a bar, and [pending] whether they are being recognized. Elsewhere (a
 * preview, a shared picture) there is none.
 */
class VoiceMenuHost(
    val open: ((String) -> Unit)?,
    val words: (String) -> VoiceTranscripts.Found?,
    val pending: (String) -> Boolean,
)

val LocalVoiceMenu = androidx.compose.runtime.compositionLocalOf<VoiceMenuHost?> { null }

/**
 * The words of recordings, for "to text": first the words Orbit kept when it made the voice (see
 * [com.ai.assistance.operit.features.orbit.OrbitVoiceText]), then words recognized before, and,
 * only when the user asks ([recognize]), their speech recognition. Words recognized are kept in the
 * app's cache by the recording's content, with a small index by its path, size and time, so they
 * come back without reading the recording and the same recording is never sent twice. Words found
 * once are also kept here by url with a cheap mark of their recording. What a bubble shows is its
 * message's own state, never read from here. A file that cannot be read counts as no words.
 */
object VoiceTranscripts {
    /** Words checked against their recording, with the [mark] it had then; [recognized] when they were heard, not kept. */
    class Found(val text: String, val mark: String, val recognized: Boolean = false)

    sealed interface Recognized {
        class Words(val found: Found) : Recognized
        class Problem(val message: String) : Recognized
    }

    private class Snapshot(val file: java.io.File, val hash: String, val mark: String)

    private const val MAX = 64
    private const val MAX_KEPT_RECOGNIZED = 200
    private const val MAX_TEXT_BYTES = 256 * 1024L
    /** Far more than 20 minutes of any audio worth recognizing; a bigger file is not copied. */
    private const val MAX_SNAPSHOT_BYTES = 256L * 1024 * 1024
    // Least recently used first; the oldest goes when full.
    private val found = object : LinkedHashMap<String, Found>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Found>?) = size > MAX
    }
    // One look at a recording at a time, wherever it is shown, so the next one finds it checked.
    // A fixed few locks by url: two recordings sharing one only wait for each other.
    private val reading = Array(16) { kotlinx.coroutines.sync.Mutex() }
    // One recognition of a recording at a time: a second bubble asking waits and finds its words.
    // Kept only while someone holds or waits for it.
    private class Gate { val mutex = kotlinx.coroutines.sync.Mutex(); var users = 0 }
    private val gates = HashMap<String, Gate>()
    private val voiceText = com.ai.assistance.operit.features.orbit.OrbitVoiceText
    // Words just recognized, by content hash: a second request for the same content takes them.
    private val heard = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 16
    }

    private fun lockOf(url: String) = reading[(url.hashCode() and Int.MAX_VALUE) % reading.size]
    private fun fileMark(file: java.io.File): String? = if (file.isFile) "${file.length()}:${file.lastModified()}" else null
    private fun keptFolder(context: Context) = java.io.File(context.cacheDir, "voice-text")
    private fun indexFolder(context: Context) = java.io.File(keptFolder(context), "by-file")

    private suspend fun <T> oneAtATime(url: String, block: suspend () -> T): T {
        val gate = synchronized(gates) { gates.getOrPut(url) { Gate() }.also { it.users++ } }
        try {
            return gate.mutex.withLock { block() }
        } finally {
            synchronized(gates) { if (--gate.users == 0) gates.remove(url) }
        }
    }

    private val audioLink = Regex("!\\[[^\\]\\n]*\\]\\(([^)\\s]+)\\)")

    /**
     * The one recording [markdown] shows, or null when it shows none or several: a long press
     * anywhere on a bubble with one voice message is for that voice (several need their own bar).
     */
    fun onlyVoiceIn(markdown: String): String? {
        if (!markdown.contains("](")) return null
        val code = markdownCodeRanges(markdown)
        var one: String? = null
        for (m in audioLink.findAll(markdown)) {
            val url = m.groupValues[1]
            // An example in code shows no voice bar.
            if (!isLikelyAudioUrl(url) || url == one || code.any { m.range.first in it }) continue
            if (one != null) return null
            one = url
        }
        return one
    }

    /** Whether the recording at [url] is on the phone, so its words can be recognized. No file is read. */
    fun canRecognize(url: String): Boolean = voiceText.fileOf(url) != null

    /** The mark [words] would have now, null when their recording (or kept words) is gone. Reads files. */
    fun markOf(context: Context, url: String, words: Found): String? = runCatching {
        if (words.recognized) voiceText.fileOf(url)?.let(::fileMark) else voiceText.stamp(url, context.filesDir)
    }.getOrNull()

    /**
     * The words of the recording at [url] now, checked against it, kept or recognized before;
     * null when there are none. Never sends anything; a cancelled scope stops the reading. Reads files.
     */
    suspend fun find(context: Context, url: String): Found? = lockOf(url).withLock { findNow(context, url) }

    private suspend fun findNow(context: Context, url: String): Found? {
        val before = synchronized(found) { found[url] }
        val now = withContext(Dispatchers.IO) {
            try {
                if (before?.recognized == true && voiceText.fileOf(url)?.let(::fileMark) == before.mark) return@withContext before
                val mark = voiceText.stamp(url, context.filesDir)
                when {
                    mark == null -> recognizedBefore(context, url)
                    before != null && !before.recognized && mark == before.mark -> before
                    else -> voiceText.read(url, context.filesDir) { isActive }?.let { Found(it, mark) }
                        ?: recognizedBefore(context, url)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        synchronized(found) { if (now == null) found.remove(url) else found[url] = now }
        return now
    }

    /** The index name for [file] as it is ([mark]): its path, size and time, hashed. */
    private fun indexName(file: java.io.File, mark: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("${file.absolutePath}\n$mark".toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Words recognized before for the recording at [url] as it is now, through the index: no recording is read. */
    private fun recognizedBefore(context: Context, url: String): Found? {
        val file = voiceText.fileOf(url)?.takeIf { it.isFile } ?: return null
        val mark = fileMark(file) ?: return null
        val entry = java.io.File(indexFolder(context), indexName(file, mark)).takeIf { it.isFile && it.length() <= 128 } ?: return null
        val text = readKept(context, entry.readText().trim()) ?: return null
        return Found(text, mark, recognized = true)
    }

    private fun readKept(context: Context, hash: String): String? {
        if (!hash.matches(Regex("[0-9a-f]{64}"))) return null
        val kept = java.io.File(keptFolder(context), "$hash.txt").takeIf { it.isFile && it.length() <= MAX_TEXT_BYTES } ?: return null
        return kept.readText().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The words of the recording at [url]: found ([find]), or else heard with the user's speech
     * recognition (which may cost money: only when the user asks) and kept for next time. What is
     * sent is a copy taken first, so the words kept always belong to the content they name.
     */
    suspend fun recognize(context: Context, url: String): Recognized {
        val app = context.applicationContext
        return oneAtATime(url) {
            try {
                recognizeNow(app, url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Recognized.Problem(app.getString(R.string.voice_stt_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun recognizeNow(app: Context, url: String): Recognized {
        find(app, url)?.let { return Recognized.Words(it) }
        val file = voiceText.fileOf(url)?.takeIf { it.isFile }
            ?: return Recognized.Problem(app.getString(R.string.voice_no_transcript))
        // The copy's place is taken first, so it goes however this ends (cancelled included).
        val copy = withContext(Dispatchers.IO) { snapshotPlace(app, file) }
        try {
            val snapshot = withContext(Dispatchers.IO) { snapshotOf(file, copy) }
                ?: return Recognized.Problem(app.getString(R.string.voice_no_transcript))
            // One recognition of a content at a time, whatever its name: a second waits and finds its words.
            return oneAtATime("sha256:${snapshot.hash}") {
                // The same content recognized before (under this or another name): just now, or kept.
                val kept = synchronized(heard) { heard[snapshot.hash] }
                    ?: withContext(Dispatchers.IO) { readKept(app, snapshot.hash) }
                if (kept != null) {
                    withContext(Dispatchers.IO) { runCatching { keepIndex(app, file, snapshot) } }
                    Recognized.Words(Found(kept, snapshot.mark, recognized = true).also { remember(url, it) })
                } else when (val heard = com.ai.assistance.operit.api.speech.AudioFileTranscriber.transcribe(app, snapshot.file)) {
                    is com.ai.assistance.operit.api.speech.AudioFileTranscriber.Outcome.Problem -> Recognized.Problem(heard.message)
                    is com.ai.assistance.operit.api.speech.AudioFileTranscriber.Outcome.Words -> {
                        // Shared with anyone waiting for the same content, even when it cannot be kept on disk.
                        synchronized(this@VoiceTranscripts.heard) { this@VoiceTranscripts.heard[snapshot.hash] = heard.text }
                        withContext(Dispatchers.IO) { keepRecognized(app, file, snapshot, heard.text) }
                        Recognized.Words(Found(heard.text, snapshot.mark, recognized = true).also { remember(url, it) })
                    }
                }
            }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { copy.delete() }
        }
    }

    private fun remember(url: String, words: Found) = synchronized(found) { found[url] = words }

    /** Where the copy of [file] goes; copies left by a run the system ended are cleared first. */
    private fun snapshotPlace(app: Context, file: java.io.File): java.io.File {
        val folder = java.io.File(app.cacheDir, "voice-snapshot").apply { mkdirs() }
        val hourAgo = System.currentTimeMillis() - 3_600_000L
        folder.listFiles()?.filter { it.lastModified() < hourAgo }?.forEach { it.delete() }
        return java.io.File(folder, "${java.util.UUID.randomUUID()}.${file.extension.take(8)}")
    }

    /**
     * Copies [file] to [copy] and hashes what was copied; null when the file changed while it was
     * copied, or is bigger than [MAX_SNAPSHOT_BYTES] (counted as it is read, so a growing file stops too).
     */
    private suspend fun snapshotOf(file: java.io.File, copy: java.io.File): Snapshot? {
        val mark = fileMark(file) ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().use { input ->
            copy.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_SNAPSHOT_BYTES) return null
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        if (fileMark(file) != mark) return null
        return Snapshot(copy, digest.digest().joinToString("") { "%02x".format(it) }, mark)
    }
    private fun keepIndex(app: Context, file: java.io.File, snapshot: Snapshot) {
        val index = indexFolder(app).apply { mkdirs() }
        java.io.File(index, indexName(file, snapshot.mark)).writeText(snapshot.hash)
        prune(index, MAX_KEPT_RECOGNIZED * 2)
    }

    /** Keeps [text] for the content of [snapshot], and its index for [file]; only the newest few hundred stay. A failure is ignored. */
    private fun keepRecognized(app: Context, file: java.io.File, snapshot: Snapshot, text: String) {
        runCatching {
            // Words too long to be read back are not kept (read and write share the limit).
            if (text.toByteArray().size > MAX_TEXT_BYTES) return
            val folder = keptFolder(app).apply { mkdirs() }
            java.io.File(folder, "${snapshot.hash}.txt").writeText(text)
            prune(folder, MAX_KEPT_RECOGNIZED)
            keepIndex(app, file, snapshot)
        }
    }

    private fun prune(folder: java.io.File, keep: Int) {
        val files = folder.listFiles { f -> f.isFile }.orEmpty()
        if (files.size > keep) files.sortedBy { it.lastModified() }.take(files.size - keep).forEach { it.delete() }
    }
}
/** Stops the voice playing for [scope], if any; voice another chat started keeps playing. */
fun stopVoiceIn(scope: VoiceScope) = VoicePlayback.leave(scope)

/** Stops whatever voice is playing, for a page that covers the chat it was started in. */
fun stopVoicePlayback() = VoicePlayback.stop()

/** What the one player is doing with [url]: [wants] to play (the icon), really [running] now, its length once known. */
internal data class VoiceNow(val url: String, val wants: Boolean, val running: Boolean, val duration: Long?)

/**
 * One player for every voice bar: starting one stops the one playing, like a messenger. It is made on
 * the first tap and let go when the audio ends, fails or its chat leaves, or the app goes to the
 * background, so a long chat full of voice keeps no players. Used on the main thread only.
 */
@SuppressLint("StaticFieldLeak") // Holds only the application context.
internal object VoicePlayback {
    private var player: ExoPlayer? = null
    private var owner: Any? = null
    private var watched: Lifecycle? = null
    private var pendingSeek: Float? = null
    private val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) stop()
    }
    private val state = MutableStateFlow<VoiceNow?>(null)

    /** Only this url's changes, so starting one bar does not redraw every other bar. */
    fun of(url: String) = state.map { now -> now?.takeIf { it.url == url } }.distinctUntilChanged()

    /** Plays [url] from the start, or pauses and goes on with it when it is the one in the player. */
    fun toggle(context: Context, url: String, scope: Any, lifecycle: Lifecycle) {
        val current = player
        if (current != null && state.value?.url == url) {
            current.playWhenReady = !current.playWhenReady
            return
        }
        start(context, url, scope, lifecycle)
    }

    /** Moves [url] to [fraction] of its length; only for the url in the player. */
    fun seek(url: String, fraction: Float) {
        val current = player ?: return
        if (state.value?.url != url) return
        val length = current.duration
        if (length == C.TIME_UNSET || length <= 0) {
            pendingSeek = fraction
        } else {
            current.seekTo((length * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    fun position(url: String): Long = player?.takeIf { state.value?.url == url }?.currentPosition ?: 0L

    fun leave(scope: Any) {
        if (owner === scope) stop()
    }

    private fun start(context: Context, url: String, scope: Any, lifecycle: Lifecycle) {
        stop()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val appContext = context.applicationContext
        val next = ExoPlayer.Builder(appContext).build()
        next.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        next.setHandleAudioBecomingNoisy(true)
        next.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (this@VoicePlayback.player !== next) return
                val length = next.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                if (length != null) {
                    pendingSeek?.let { pendingSeek = null; next.seekTo((length * it).toLong()) }
                    rememberDuration(url, length)
                }
                state.value = VoiceNow(url, next.playWhenReady, next.isPlaying, length)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && this@VoicePlayback.player === next) stop()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (this@VoicePlayback.player !== next) return
                stop()
                Toast.makeText(appContext, R.string.audio_playback_failed, Toast.LENGTH_SHORT).show()
            }
        })
        next.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        next.prepare()
        next.playWhenReady = true
        player = next
        owner = scope
        watched = lifecycle.also { it.addObserver(observer) }
        state.value = VoiceNow(url, wants = true, running = false, duration = null)
    }

    fun stop() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
        owner = null
        pendingSeek = null
        watched?.removeObserver(observer)
        watched = null
        state.value = null
    }
}

/** A length read once: [stamp] is the file's size and change time, so a remade file is read again. */
private class Measured(val stamp: String, val ms: Long?, val checkedAt: Long)

private val measured = LruCache<String, Measured>(256)
private val inflight = HashMap<String, CompletableDeferred<Long?>>()
/** At most two recordings are read at a time, however fast the chat is scrolled. */
private val reading = Semaphore(2)
private const val RECHECK_MS = 30_000L
private const val RETRY_FAILED_MS = 60_000L

/** Where a recording is, the same for `file://` and a plain path; null for audio on the web. */
private fun placeOf(url: String): String? {
    val uri = Uri.parse(url)
    return when (uri.scheme) {
        null -> url
        "file" -> uri.path
        "content" -> url
        else -> null
    }
}

private fun keyOf(url: String) = placeOf(url) ?: "web:$url"

/** Web audio has no file to read; its length is learned the first time it plays. */
private fun rememberDuration(url: String, ms: Long) {
    val key = keyOf(url)
    val old = measured.get(key)
    if (old?.ms != ms) measured.put(key, Measured(old?.stamp.orEmpty(), ms, old?.checkedAt ?: 0L))
}

private fun knownDuration(url: String): Long? = measured.get(keyOf(url))?.ms

/** How long a recording is; null when it cannot be read (or for web audio not played yet). */
private suspend fun durationOf(context: Context, url: String): Long? {
    val place = placeOf(url) ?: return knownDuration(url)
    // Read lately: nothing to queue for.
    measured.get(place)?.let { if (it.ms != null && SystemClock.uptimeMillis() - it.checkedAt < RECHECK_MS) return it.ms }
    while (true) {
        val (mine, job) = synchronized(inflight) {
            inflight[place]?.let { false to it } ?: (true to CompletableDeferred<Long?>().also { inflight[place] = it })
        }
        if (!mine) {
            // Another bar is reading the same file; its answer is ours. If it gave up, read it here.
            job.join()
            currentCoroutineContext().ensureActive()
            if (job.isCancelled) continue
            return job.getCompleted()
        }
        try {
            val ms = reading.withPermit { withContext(Dispatchers.IO) { measure(context, place) } }
            job.complete(ms)
            return ms
        } catch (e: Throwable) {
            job.cancel()
            throw e
        } finally {
            synchronized(inflight) { if (inflight[place] === job) inflight.remove(place) }
        }
    }
}

private fun measure(context: Context, place: String): Long? {
    val now = SystemClock.uptimeMillis()
    val content = place.startsWith("content:")
    val old = measured.get(place)
    if (old != null && old.ms != null && now - old.checkedAt < RECHECK_MS) return old.ms
    val stamp = if (content) "" else File(place).let { "${it.length()}:${it.lastModified()}" }
    // A content uri has no size or time to compare, so it is read again each time it is due.
    if (old != null && !content && old.stamp == stamp && (old.ms != null || now - old.checkedAt < RETRY_FAILED_MS)) {
        measured.put(place, Measured(stamp, old.ms, if (old.ms != null) now else old.checkedAt))
        return old.ms
    }
    if (old != null && content && old.ms == null && now - old.checkedAt < RETRY_FAILED_MS) return null
    val ms = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            if (content) retriever.setDataSource(context, Uri.parse(place)) else retriever.setDataSource(place)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()
    measured.put(place, Measured(stamp, ms, now))
    return ms
}

private fun clock(ms: Long): String {
    val seconds = ((ms + 500) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Audio in a message, shown as a voice bar: tap to play, tap again to pause and again to go on;
 * while it is in the player, tapping or dragging the wave moves to that point. Longer audio makes a
 * longer bar. [contentColor] is the message's text color, so the bar reads on any bubble color.
 */
@Composable
fun MarkdownAudioRenderer(
    audioMarkdown: String,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    /** Set when the voice is a message of its own: the bar is then the bubble, in this color. */
    bubbleColor: Color = Color.Unspecified,
) {
    if (!isCompleteImageMarkdown(audioMarkdown)) {
        return
    }

    val audioAlt = extractMarkdownImageAlt(audioMarkdown)
    val audioUrl = extractMarkdownImageUrl(audioMarkdown)
    if (audioUrl.isBlank() || !isLikelyAudioUrl(audioUrl)) {
        return
    }
    key(audioUrl) {
        VoiceBar(
            audioUrl, audioAlt, contentColor.takeOrElse { LocalContentColor.current },
            bubbleColor.takeIf { it != Color.Unspecified }, modifier,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun VoiceBar(audioUrl: String, audioAlt: String, ink: Color, bubble: Color?, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Inside a chat, voice keeps playing while its bar is scrolled away and stops when the chat goes.
    // Anywhere else it stops when its bar goes.
    val chatScope = LocalVoiceScope.current
    val ownScope = remember { Any() }
    val scope: Any = chatScope ?: ownScope
    if (chatScope == null) {
        DisposableEffect(ownScope) { onDispose { VoicePlayback.leave(ownScope) } }
    }
    // Long press opens the message's own menu, which has "to text" (see [VoiceMenuHost]).
    val host = LocalVoiceMenu.current
    // Only this bar's own words: another bar showing or hiding its words does not redraw it.
    val words by remember(host, audioUrl) { androidx.compose.runtime.derivedStateOf { host?.words(audioUrl) } }
    val hearing by remember(host, audioUrl) { androidx.compose.runtime.derivedStateOf { host?.pending(audioUrl) == true } }
    val longPress = host?.open
    val haptics = LocalHapticFeedback.current
    val openMenu: (() -> Unit)? = longPress?.let { open ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            open(audioUrl)
        }
    }
    val latestOpenMenu = androidx.compose.runtime.rememberUpdatedState(openMenu)
    val measuredMs by produceState(knownDuration(audioUrl)) { value = durationOf(context, audioUrl) }
    val now by remember { VoicePlayback.of(audioUrl) }.collectAsState(null)
    // The length the player found (web audio has no other), kept after it stops.
    var heard by remember { mutableLongStateOf(0L) }
    LaunchedEffect(now?.duration) { now?.duration?.let { heard = it } }
    val active = now != null
    val running = now?.running == true
    var position by remember { mutableLongStateOf(0L) }
    var dragged by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(active, running) {
        if (!active) {
            position = 0L
            return@LaunchedEffect
        }
        position = VoicePlayback.position(audioUrl)
        while (running) {
            delay(200)
            position = VoicePlayback.position(audioUrl)
        }
    }

    val total = now?.duration ?: measuredMs ?: heard
    val barWidth = (136 + (total / 1000).toInt() * 3).coerceAtMost(260).dp
    val shownDrag = dragged
    val progress = shownDrag ?: if (active && total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f
    val playing = now?.wants == true
    val label = stringResource(R.string.audio_block)
    val actionLabel = stringResource(if (playing) R.string.pause else R.string.tts_play)
    val optionsLabel = stringResource(R.string.voice_options)
    val lengthText = if (total > 0) clock(total) else null
    val description = listOfNotNull(label, audioAlt.takeIf { it.isNotBlank() }, lengthText).joinToString(", ")
    val seekable = active && total > 0
    // Tapping or dragging the wave, or a screen reader setting the progress, all move here.
    val seekTo: (Float) -> Unit = { f ->
        val at = f.coerceIn(0f, 1f)
        VoicePlayback.seek(audioUrl, at)
        position = (total * at).toLong()
    }

    // As a message of its own the bar is the bubble (Orbit's bubble shape); inside words it has no
    // frame of its own, only the play button, the wave and the time.
    val barShape = if (bubble != null) RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp) else RoundedCornerShape(24.dp)
    Column(modifier = modifier.padding(vertical = if (bubble != null) 0.dp else 4.dp)) {
      Box {
        Row(
            modifier = Modifier
                .width(barWidth)
                .heightIn(min = 48.dp)
                .clip(barShape)
                .then(if (bubble != null) Modifier.background(bubble) else Modifier)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = actionLabel,
                    onLongClickLabel = optionsLabel.takeIf { openMenu != null },
                    onLongClick = openMenu,
                ) {
                    VoicePlayback.toggle(context, audioUrl, scope, lifecycle)
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (seekable) {
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        setProgress { f -> seekTo(f); true }
                    }
                }
                .padding(horizontal = if (bubble != null) 12.dp else 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(8.dp))
            Waveform(
                seed = audioUrl.hashCode(),
                progress = progress,
                ink = ink,
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .then(
                        if (!seekable) Modifier else Modifier
                            .pointerInput(audioUrl, total) {
                                // A long press on the wave opens the same menu as the rest of the bar.
                                detectTapGestures(onLongPress = { latestOpenMenu.value?.invoke() }) { at -> seekTo(at.x / size.width) }
                            }
                            .pointerInput(audioUrl, total) {
                                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = at(it.x) },
                                    onDragEnd = {
                                        dragged?.let(seekTo)
                                        dragged = null
                                    },
                                    onDragCancel = { dragged = null },
                                ) { change, _ ->
                                    change.consume()
                                    dragged = at(change.position.x)
                                }
                            }
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    shownDrag != null -> clock((total * shownDrag).toLong())
                    active -> clock(position)
                    else -> lengthText.orEmpty()
                },
                style = MaterialTheme.typography.labelMedium,
                color = ink.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
      }
        val shownWords = words
        if (shownWords != null || hearing) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(if (bubble != null) 16.dp else 12.dp))
                    .background(bubble ?: ink.copy(alpha = 0.06f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (shownWords == null) {
                    Text(stringResource(R.string.voice_recognizing), style = MaterialTheme.typography.bodyMedium,
                        color = ink.copy(alpha = 0.6f))
                } else {
                    SelectionContainer {
                        Text(text = shownWords.text, style = MaterialTheme.typography.bodyMedium, color = ink)
                    }
                    // Heard by speech recognition, not the words Orbit said: there may be mistakes.
                    if (shownWords.recognized) {
                        Text(stringResource(R.string.voice_recognized_note), style = MaterialTheme.typography.labelSmall,
                            color = ink.copy(alpha = 0.55f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
        // A voice message of its own shows no title under it, as in a messenger.
        if (audioAlt.isNotBlank() && bubble == null) {
            Text(
                text = audioAlt,
                style = MaterialTheme.typography.bodySmall,
                color = ink.copy(alpha = 0.6f),
                modifier = Modifier.width(barWidth).padding(start = 8.dp, top = 2.dp).clearAndSetSemantics { },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Bars of fixed made-up heights (the same for one recording every time); the played part is darker. */
@Composable
private fun Waveform(seed: Int, progress: Float, ink: Color, modifier: Modifier) {
    val heights = remember(seed) {
        val random = java.util.Random(seed.toLong())
        FloatArray(48) { 0.25f + random.nextFloat() * 0.75f }
    }
    Canvas(modifier) {
        val barWidth = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val count = ((size.width + gap) / (barWidth + gap)).toInt().coerceIn(1, heights.size)
        val played = (count * progress).toInt()
        for (i in 0 until count) {
            val height = size.height * heights[i]
            drawRoundRect(
                color = if (i < played) ink else ink.copy(alpha = 0.3f),
                topLeft = Offset(i * (barWidth + gap), (size.height - height) / 2),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

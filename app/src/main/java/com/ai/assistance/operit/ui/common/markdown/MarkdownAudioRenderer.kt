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

/** One chat's place for voice: when it leaves (another chat, another page, the app lock), its voice stops. */
class VoiceScope

/** Given by the chat list; audio shown elsewhere stops when its own bar leaves. */
val LocalVoiceScope = staticCompositionLocalOf<VoiceScope?> { null }

/**
 * Given by a chat message for the voice bars in it. [open], a long press on a bar, opens the
 * message's own menu for that recording (one menu, as in a messenger; "to text" is one of its
 * items), and is null while the message takes no menu (picking messages). [words] are the words
 * this message shows under a bar. Elsewhere (a preview, a shared picture) there is neither.
 */
class VoiceMenuHost(val open: ((String) -> Unit)?, val words: (String) -> String?)

val LocalVoiceMenu = androidx.compose.runtime.compositionLocalOf<VoiceMenuHost?> { null }

/**
 * A speed-up for finding the words of a recording again: the words of recordings already checked,
 * by url, each with a cheap mark of its recording, so finding them again only looks at the files
 * (see [com.ai.assistance.operit.features.orbit.OrbitVoiceText.stamp]) and checks them in full only
 * when the recording changed. What a bubble shows is its message's own state, never read from here.
 */
object VoiceTranscripts {
    /** Words checked against their recording, with the [mark] it had then. */
    class Found(val text: String, val mark: String)
    private const val MAX = 64
    // Least recently used first; the oldest goes when full.
    private val found = object : LinkedHashMap<String, Found>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Found>?) = size > MAX
    }
    // One look at a recording at a time, wherever it is shown, so the next one finds it checked.
    // A fixed few locks by url: two recordings sharing one only wait for each other.
    private val reading = Array(16) { kotlinx.coroutines.sync.Mutex() }

    /** The mark of the recording at [url] and its words now, null when either is gone. Reads files. */
    fun markOf(context: Context, url: String): String? =
        com.ai.assistance.operit.features.orbit.OrbitVoiceText.stamp(url, context.filesDir)

    /** Whether the recording at [url] has words kept for it (a quick look at the files). Reads files. */
    fun available(context: Context, url: String): Boolean = markOf(context, url) != null

    /**
     * The words of the recording at [url] now, checked against it; null when there are none (a
     * cancelled [kotlinx.coroutines.CoroutineScope] stops the reading). Reads files.
     */
    suspend fun find(context: Context, url: String): Found? =
        reading[(url.hashCode() and Int.MAX_VALUE) % reading.size].withLock { findNow(context, url) }

    private suspend fun findNow(context: Context, url: String): Found? {
        val voiceText = com.ai.assistance.operit.features.orbit.OrbitVoiceText
        val before = synchronized(found) { found[url] }
        val now = withContext(Dispatchers.IO) {
            val mark = voiceText.stamp(url, context.filesDir) ?: return@withContext null
            if (before != null && mark == before.mark) before
            else voiceText.read(url, context.filesDir) { isActive }?.let { Found(it, mark) }
        }
        synchronized(found) { if (now == null) found.remove(url) else found[url] = now }
        return now
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
        words?.let { text ->
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .widthIn(max = 300.dp)
                        .clip(RoundedCornerShape(if (bubble != null) 16.dp else 12.dp))
                        .background(bubble ?: ink.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
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

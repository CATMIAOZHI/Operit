package com.ai.assistance.operit.ui.features.orbit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Picture
import android.util.LruCache
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.EventListener
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.ui.common.markdown.LocalMarkdownSnapshot
import com.ai.assistance.operit.ui.common.markdown.MarkdownSnapshot
import com.ai.assistance.operit.ui.features.chat.components.ORBIT_CHAT_TIME_GAP_MS
import com.ai.assistance.operit.ui.features.chat.components.OrbitChatTimeLine
import com.ai.assistance.operit.util.AppLogger
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picked messages of an Orbit chat as one picture, drawn with the chat's own bubbles: Orbit's
 * picture and name on top, the messages with their time lines, and a small mark at the bottom.
 *
 * Every picked message is in it, however many. The messages are drawn a few at a time and each
 * part goes straight into the PNG file, so a long chat never needs one huge picture in memory.
 */
internal object OrbitShareImage {
    private const val TAG = "OrbitShareImage"

    /** Wide enough to stay sharp, never wider than the phone, so bubbles keep their shape. */
    private const val MAX_WIDTH = 1440

    /** Rows drawn and saved at a time; about 12 MB of pixels. */
    private const val STRIP = 2048

    /** Messages laid out at a time. */
    private const val GROUP_WORDS = 4_000
    private const val GROUP_MESSAGES = 15

    private const val FOLDER = "shared_images"
    private const val PREFIX = "orbit_chat_"

    /** One loader for all shares; a software canvas cannot draw hardware bitmaps. */
    @Volatile private var softwareLoader: ImageLoader? = null

    private fun loader(context: Context): ImageLoader =
        softwareLoader ?: synchronized(this) {
            softwareLoader ?: ImageLoader.Builder(context.applicationContext).allowHardware(false).build()
                .also { softwareLoader = it }
        }

    /** A very long message is drawn in parts of about this many characters. */
    private const val PIECE = 6_000
    private const val PIECE_LINES = 200

    /** Longest wait for one group's text and pictures; past it the picture fails instead of missing parts. */
    private const val GROUP_WAIT_MS = 90_000L

    /**
     * Messages drawn together. [wholes] holds each one's full reply: a card in a part of a long
     * reply still finds what was filled in it, which is kept by its place in the whole reply;
     * [offsets] says how many cards of that reply come before the part (null for a whole reply).
     */
    class Group(
        val messages: List<ChatMessage>,
        val wholes: List<String>,
        val offsets: List<com.ai.assistance.operit.ui.common.dynamicui.DynamicUiPartOffset?>,
    )

    /** The finished picture, and the same rows as small pictures the preview shows one by one. */
    class Result(val file: File, val tiles: List<Tile>)
    class Tile(val file: File, val height: Int)

    suspend fun groups(context: Context, messages: List<ChatMessage>): List<Group> =
        withContext(Dispatchers.Default) {
            val groups = ArrayList<Group>()
            var current = ArrayList<ChatMessage>()
            var wholes = ArrayList<String>()
            var offsets = ArrayList<com.ai.assistance.operit.ui.common.dynamicui.DynamicUiPartOffset?>()
            var words = 0
            for (original in messages) {
                ensureActive()
                val parts = pieces(original)
                // Cards in the parts before this one, counted as the whole reply counts them.
                var cards = 0
                val same = HashMap<String, Int>()
                for (message in parts) {
                    if (current.isNotEmpty() && (current.size >= GROUP_MESSAGES || words >= GROUP_WORDS)) {
                        groups += Group(current, wholes, offsets)
                        current = ArrayList()
                        wholes = ArrayList()
                        offsets = ArrayList()
                        words = 0
                    }
                    current += message
                    wholes += original.content
                    val sources = if (parts.size > 1 && original.sender == "ai")
                        com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech.uiSources(message.content)
                    else emptyList()
                    // Only the counts of the cards this part holds, so each part keeps a small table.
                    offsets += if (cards == 0) null else com.ai.assistance.operit.ui.common.dynamicui.DynamicUiPartOffset(
                        cards, sources.distinct().mapNotNull { s -> same[s]?.let { s to it } }.toMap(),
                    )
                    sources.forEach {
                        cards += 1
                        same[it] = (same[it] ?: 0) + 1
                    }
                    words += OrbitMessageActions.shownText(context, message).length
                }
            }
            if (current.isNotEmpty()) groups += Group(current, wholes, offsets)
            groups
        }

    private val openTag = Regex("""^<([A-Za-z_][\w:.-]*)(\s[^>]*)?>""")
    private val closeTag = Regex("""^</([A-Za-z_][\w:.-]*)\s*>""")

    /** Blocks of Orbit's replies that only draw whole: tool calls, results, thinking, status. */
    private val wholeTag = Regex("""^(tool|tool_result|think|thinking|status|search|plan|meta)(_\w+)?$""")

    /** What the user's message carries besides its words (files, a reply, memory); drawn only whole. */
    private val userWholeTag = Regex("""^(attachment|workspace_attachment|reply_to|memory)$""", RegexOption.IGNORE_CASE)

    /**
     * Where the user's blocks open and close, anywhere in a line: files are joined to the words, and
     * to each other, with only a space, so one can end and the next begin on the same line.
     */
    // A tag never holds another '<', so a broken one stops at the next and long lines stay quick.
    private val userBlockTag = Regex("""<(/?)(attachment|workspace_attachment|reply_to|memory)\b[^<>]*?(/?)>""", RegexOption.IGNORE_CASE)

    /** Follows the user's blocks through [line] in order, leaving [open] as the line leaves them. */
    private fun followUserBlocks(line: String, open: MutableList<String>) {
        for (m in userBlockTag.findAll(line)) {
            val name = m.groupValues[2].lowercase()
            when {
                m.groupValues[1] == "/" ->
                    open.lastIndexOf(name).takeIf { it >= 0 }?.let { open.subList(it, open.size).clear() }
                m.groupValues[3] != "/" -> open += name
            }
        }
    }

    /** Code blocks that are cards; cut in two they would not draw. */
    private fun wholeFence(language: String): Boolean {
        val name = language.trim().substringBefore(' ').lowercase()
        return name.startsWith("a2ui") || name.startsWith("orbit")
    }

    /**
     * A very long message as several of at most about [PIECE] characters or [PIECE_LINES] lines,
     * so no message, code block or copy block has to be laid out whole. It prefers empty lines;
     * a long code block is cut between lines and each part closed and reopened, so every part is
     * a whole code block. Cards and Orbit's tool blocks stay whole. Together the parts hold every
     * word.
     */
    private fun pieces(message: ChatMessage): List<ChatMessage> {
        val text = message.content
        if (text.length <= PIECE && text.count { it == '\n' } < PIECE_LINES) return listOf(message)
        val ai = message.sender == "ai"
        val kept = if (ai) wholeTag else userWholeTag
        val out = ArrayList<ChatMessage>()
        val current = StringBuilder()
        var count = 0
        var fence: String? = null
        var fenceTicks = 0
        var fenceBody = 0
        var fenceWhole = false
        val open = ArrayList<String>()
        fun flush() {
            if (current.isNotBlank()) out += message.copy(content = current.toString().trimEnd('\n'), contentStream = null)
            current.setLength(0)
            count = 0
        }
        fun take(from: Int, until: Int) {
            val t = text.substring(from, until).trim()
            // The user's files are read before their words are drawn, so code blocks follow only the
            // words: those before a file on the line, none inside one (a file may follow a closing ```).
            val words: String? = when {
                ai -> t
                open.isNotEmpty() -> null
                else -> userBlockTag.find(t)?.let { t.substring(0, it.range.first).trim() } ?: t
            }
            when {
                words == null -> Unit
                fence != null ->
                    if (words.length >= fenceTicks && words.all { it == '`' }) fence = null else fenceBody += 1
                open.isEmpty() && words.takeWhile { it == '`' }.length >= 3 -> {
                    fence = words
                    fenceTicks = words.takeWhile { it == '`' }.length
                    fenceBody = 0
                    fenceWhole = wholeFence(words.substring(fenceTicks))
                }
                !ai -> Unit
                // A block may also end at the end of a line of its content.
                open.isNotEmpty() && t.contains("</${open.last()}>") -> open.removeAt(open.size - 1)
                else -> closeTag.find(t)?.let { m ->
                    open.lastIndexOf(m.groupValues[1]).takeIf { it >= 0 }?.let { open.subList(it, open.size).clear() }
                } ?: openTag.find(t)?.let { m ->
                    val name = m.groupValues[1]
                    if (kept.matches(name) && !m.value.endsWith("/>") && !t.contains("</$name>")) open += name
                }
            }
            if (!ai) followUserBlocks(t, open)
            current.append(text, from, until).append('\n')
            count += 1
            val big = current.length >= PIECE || count >= PIECE_LINES
            val opener = fence
            when {
                open.isNotEmpty() -> Unit
                opener != null -> if (!fenceWhole && big && fenceBody > 0) {
                    current.append("`".repeat(fenceTicks))
                    flush()
                    current.append(opener).append('\n')
                    count = 1
                    fenceBody = 0
                }
                (big && t.isEmpty()) || current.length >= PIECE * 3 / 2 || count >= PIECE_LINES * 3 / 2 -> flush()
            }
        }
        var at = 0
        while (at <= text.length) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            // A single line longer than a part is taken a part at a time, except inside a card or a
            // tool block, where a line break would change what it says.
            var from = at
            // A line that opens a tool block counts too, even when the whole block is this one line.
            val opensTool = end - at > PIECE && (
                if (!ai) userBlockTag.containsMatchIn(text.substring(at, end))
                else fence == null && openTag.find(text.substring(at, minOf(end, at + 4096)).trimStart())
                    ?.let { kept.matches(it.groupValues[1]) } == true)
            val keepLine = (fence != null && fenceWhole) || open.isNotEmpty() || opensTool
            while (!keepLine && end - from > PIECE) {
                var cut = from + PIECE
                if (Character.isHighSurrogate(text[cut - 1])) cut -= 1
                take(from, cut)
                from = cut
            }
            take(from, end)
            at = end + 1
        }
        flush()
        return out.ifEmpty { listOf(message) }
    }

    /**
     * Draws every group with [page] off screen and saves them, one under another, as one PNG.
     * [parent] carries the chat's theme and Orbit's settings into the picture, so it looks like
     * the chat on screen. [onProgress] gets 0..1 on the main thread.
     *
     * Each group waits until its text is laid out and its pictures have loaded, is recorded once
     * on the main thread, and is then cut into rows and saved away from it.
     */
    suspend fun render(
        context: Context,
        parent: CompositionContext,
        groups: List<Group>,
        onProgress: (Float) -> Unit,
        page: @Composable (Int) -> Unit,
    ): Result = withContext(Dispatchers.Main) {
        require(groups.isNotEmpty()) { "Nothing to draw" }
        val activity = context.findActivity() ?: error("No activity")
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val width = screenWidth.coerceIn(1, MAX_WIDTH)
        val snapshot = MarkdownSnapshot()
        // Pictures in the messages load through this loader, so the wait knows when they are in.
        val loader = loader(activity).newBuilder().eventListener(object : EventListener {
            override fun onStart(request: ImageRequest) = snapshot.imageStarted(request)
            override fun onSuccess(request: ImageRequest, result: SuccessResult) = snapshot.imageEnded(request)
            override fun onError(request: ImageRequest, result: ErrorResult) = snapshot.imageEnded(request)
            override fun onCancel(request: ImageRequest) = snapshot.imageEnded(request)
        }).build()
        val stamp = System.currentTimeMillis()
        val folder = withContext(Dispatchers.IO) {
            File(context.cacheDir, FOLDER).apply {
                mkdirs()
                // Pictures from earlier shares are cleared once other apps have had time to read them.
                val old = System.currentTimeMillis() - 60 * 60 * 1000L
                listFiles { f -> f.name.startsWith(PREFIX) && f.lastModified() < old }?.forEach { it.deleteRecursively() }
            }
        }
        val part = File(folder, "$PREFIX$stamp.part")
        val target = File(folder, "$PREFIX$stamp.png")
        val tileFolder = File(folder, "${PREFIX}${stamp}_tiles")
        val tiles = ArrayList<Tile>()
        var strip: Bitmap? = null
        var done = false
        try {
            withContext(Dispatchers.IO) { tileFolder.mkdirs() }
            // Opened here and closed below with nothing in between that could be cancelled.
            val rows = PngRows(part, width)
            try {
                val picture = Bitmap.createBitmap(width, STRIP, Bitmap.Config.ARGB_8888).also { strip = it }
                val canvas = Canvas(picture)
                for ((index, _) in groups.withIndex()) {
                    val compose = ComposeView(activity).apply {
                        setParentCompositionContext(parent)
                        setContent {
                            CompositionLocalProvider(
                                LocalImageLoader provides loader,
                                LocalMarkdownSnapshot provides snapshot,
                            ) { page(index) }
                        }
                    }
                    val holder = ScrollView(activity).apply {
                        isVerticalScrollBarEnabled = false
                        addView(compose, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                        // Off screen, but still attached and drawn, so pictures loading late redraw.
                        translationX = (screenWidth + width).toFloat()
                    }
                    root.addView(holder, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val recorded = try {
                        // Until the text is laid out, the pictures are in, and the height has held
                        // still for a moment (fades and late layout settle in that time).
                        val until = System.currentTimeMillis() + GROUP_WAIT_MS
                        var last = -1
                        var still = 0
                        while (still < 2) {
                            delay(50)
                            val height = compose.height
                            still = if (!snapshot.busy && snapshot.quietFor >= 150 && height > 0 && height == last) still + 1 else 0
                            last = height
                            check(System.currentTimeMillis() < until) { "The messages took too long to load" }
                        }
                        val height = compose.height
                        check(height > 0) { "Nothing was drawn" }
                        // One draw on the main thread; cutting it into rows happens elsewhere.
                        Picture().apply {
                            compose.draw(beginRecording(width, height))
                            endRecording()
                        }
                    } finally {
                        root.removeView(holder)
                    }
                    withContext(Dispatchers.Default) {
                        val height = recorded.height
                        var top = 0
                        while (top < height) {
                            ensureActive()
                            val count = minOf(STRIP, height - top)
                            picture.eraseColor(0)
                            val saved = canvas.save()
                            canvas.clipRect(0, 0, width, count)
                            canvas.translate(0f, -top.toFloat())
                            canvas.drawPicture(recorded)
                            canvas.restoreToCount(saved)
                            rows.add(picture, count)
                            val tile = File(tileFolder, "${tiles.size}.jpg")
                            tile.outputStream().use {
                                if (count == STRIP) picture.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                else {
                                    val cut = Bitmap.createBitmap(picture, 0, 0, width, count)
                                    try {
                                        cut.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                    } finally {
                                        if (cut !== picture) cut.recycle()
                                    }
                                }
                            }
                            tiles += Tile(tile, count)
                            top += count
                            val shown = ((index + top.toFloat() / height) / groups.size).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(shown) }
                        }
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { rows.close() }
            }
            withContext(Dispatchers.IO) { PngRows.assemble(part, target, width, rows.height) }
            done = true
            Result(target, tiles)
        } finally {
            strip?.recycle()
            withContext(NonCancellable + Dispatchers.IO) {
                part.delete()
                if (!done) {
                    target.delete()
                    tileFolder.deleteRecursively()
                }
            }
        }
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    fun failed(context: Context, e: Throwable) {
        AppLogger.e(TAG, "Could not make the chat picture", e)
        Toast.makeText(context, R.string.orbit_share_image_failed, Toast.LENGTH_SHORT).show()
    }
}

/**
 * A PNG written row by row. The rows go, compressed, into a side file first because the height is
 * only known at the end; [assemble] then puts the header in front of them.
 */
private class PngRows(file: File, private val width: Int) : Closeable {
    private val deflater = Deflater(6)
    private val out = DeflaterOutputStream(IdatChunks(BufferedOutputStream(FileOutputStream(file), 1 shl 16)), deflater, 1 shl 16)
    private val pixels = IntArray(width)
    private val line = ByteArray(1 + width * 4)

    @Volatile var height = 0
        private set

    /** The first [count] rows of [bitmap]: RGBA, each byte minus the one a pixel before (filter "Sub"). */
    fun add(bitmap: Bitmap, count: Int) {
        line[0] = 1
        for (y in 0 until count) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var r0 = 0
            var g0 = 0
            var b0 = 0
            var a0 = 0
            var at = 1
            for (c in pixels) {
                val a = c ushr 24
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                line[at] = (r - r0).toByte()
                line[at + 1] = (g - g0).toByte()
                line[at + 2] = (b - b0).toByte()
                line[at + 3] = (a - a0).toByte()
                r0 = r; g0 = g; b0 = b; a0 = a
                at += 4
            }
            out.write(line)
        }
        height += count
    }

    override fun close() {
        try {
            out.close()
        } finally {
            deflater.end()
        }
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)

        fun assemble(part: File, target: File, width: Int, height: Int) {
            check(height > 0) { "Nothing was drawn" }
            BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
                out.write(SIGNATURE)
                val header = ByteArray(13)
                putInt(header, 0, width)
                putInt(header, 4, height)
                header[8] = 8 // bits per channel
                header[9] = 6 // RGBA
                chunk(out, "IHDR", header, header.size)
                part.inputStream().use { it.copyTo(out, 1 shl 16) }
                chunk(out, "IEND", ByteArray(0), 0)
            }
        }

        fun chunk(out: OutputStream, type: String, data: ByteArray, size: Int) {
            val name = type.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply {
                update(name)
                update(data, 0, size)
            }
            val number = ByteArray(4)
            putInt(number, 0, size)
            out.write(number)
            out.write(name)
            out.write(data, 0, size)
            putInt(number, 0, crc.value.toInt())
            out.write(number)
        }

        private fun putInt(to: ByteArray, at: Int, value: Int) {
            to[at] = (value ushr 24).toByte()
            to[at + 1] = (value ushr 16).toByte()
            to[at + 2] = (value ushr 8).toByte()
            to[at + 3] = value.toByte()
        }
    }
}

/** Compressed rows cut into PNG data chunks of 128 KB. */
private class IdatChunks(private val out: OutputStream) : OutputStream() {
    private val buffer = ByteArray(1 shl 17)
    private var size = 0

    override fun write(b: Int) {
        if (size == buffer.size) flushChunk()
        buffer[size++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var from = off
        var left = len
        while (left > 0) {
            if (size == buffer.size) flushChunk()
            val n = minOf(left, buffer.size - size)
            System.arraycopy(b, from, buffer, size, n)
            size += n
            from += n
            left -= n
        }
    }

    private fun flushChunk() {
        if (size > 0) {
            PngRows.chunk(out, "IDAT", buffer, size)
            size = 0
        }
    }

    override fun flush() = out.flush()

    override fun close() {
        try {
            flushChunk()
        } finally {
            out.close()
        }
    }
}

/** One part of the picture: Orbit on top of the first, the mark under the last. */
@Composable
private fun OrbitSharePage(
    host: OrbitMessageHost,
    group: OrbitShareImage.Group,
    before: Long?,
    first: Boolean,
    last: Boolean,
    horizontalPadding: Dp,
    row: @Composable (ChatMessage) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(colors.background)
            .padding(top = if (first) 24.dp else 0.dp, bottom = if (last) 16.dp else 0.dp),
    ) {
        if (first) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                OrbitAvatar(host.orbit(), 64, petVisible = false)
                Spacer(Modifier.height(8.dp))
                Text(host.orbitName, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = horizontalPadding)) {
            var previous: Long? = before
            for ((at, message) in group.messages.withIndex()) {
                val gap = previous?.let { message.timestamp - it >= ORBIT_CHAT_TIME_GAP_MS } != false
                if (gap) OrbitChatTimeLine(message.timestamp)
                previous = message.timestamp
                CompositionLocalProvider(
                    com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiOwner provides
                        message.takeIf { it.sender == "ai" }?.let {
                            com.ai.assistance.operit.ui.common.dynamicui.DynamicUiOwner(
                                host.chatId, it.timestamp, it.selectedVariantIndex, group.wholes[at],
                            )
                        },
                    com.ai.assistance.operit.ui.common.dynamicui.LocalDynamicUiPartOffset provides group.offsets[at],
                ) { row(message.copy(contentStream = null)) }
            }
        }
        if (last) {
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(painterResource(R.drawable.ic_launcher_simple_foreground), null, Modifier.size(24.dp))
                Text("Operit Ry", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * Sharing picked messages as a picture. Returns what starts it; while it runs a progress bar
 * shows, then a full-screen preview with share, open in another app and save.
 */
@Composable
internal fun rememberOrbitPictureShare(
    host: OrbitMessageHost,
    horizontalPadding: Dp,
    row: @Composable (ChatMessage) -> Unit,
): (List<ChatMessage>) -> Unit {
    val context = LocalContext.current
    val parent = rememberCompositionContext()
    val scope = rememberCoroutineScope()
    val currentRow by rememberUpdatedState(row)
    val currentHost by rememberUpdatedState(host)
    val currentPadding by rememberUpdatedState(horizontalPadding)
    var open by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<OrbitShareImage.Result?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val job = remember { arrayOfNulls<Job>(1) }

    if (open) {
        OrbitSharePreview(file, { progress }) {
            job[0]?.cancel()
            open = false
            file = null
        }
    }
    return remember {
        { messages ->
            job[0]?.cancel()
            file = null
            progress = 0f
            open = true
            val shown = currentHost
            val padding = currentPadding
            job[0] = scope.launch {
                try {
                    val groups = OrbitShareImage.groups(context, messages)
                    file = OrbitShareImage.render(context, parent, groups, onProgress = { progress = it }) { index ->
                        OrbitSharePage(
                            host = shown,
                            group = groups[index],
                            before = groups.getOrNull(index - 1)?.messages?.lastOrNull()?.timestamp,
                            first = index == 0,
                            last = index == groups.lastIndex,
                            horizontalPadding = padding,
                        ) { currentRow(it) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    open = false
                    OrbitShareImage.failed(context, e)
                }
            }
        }
    }
}

/** Preview tiles kept decoded while the preview is open; about 25 MB at most. */
private class TileCache : LruCache<File, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: File, value: ImageBitmap) = value.width * value.height * 2

    fun load(file: File, sample: Int): ImageBitmap? =
        get(file) ?: runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })?.asImageBitmap()
        }.getOrNull()?.also { put(file, it) }
}

/**
 * A picture of any height, shown as the small pictures saved while making it, each decoded as
 * it scrolls into view (and the next one ahead of it), so a very long chat never fills memory.
 */
@Composable
private fun OrbitTallPicture(result: OrbitShareImage.Result, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val tiles = result.tiles
    // null while reading; 0 when the picture cannot be read.
    val read by produceState<Int?>(null, result) {
        value = withContext(Dispatchers.IO) {
            tiles.firstOrNull()?.let {
                BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { o -> BitmapFactory.decodeFile(it.file.path, o) }.outWidth
            }?.coerceAtLeast(0) ?: 0
        }
    }
    val width = read
    if (width == null || width <= 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (width == null) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.7f))
            else Text(stringResource(R.string.image_load_failed), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // Fewer pixels when the picture is much wider than the screen.
    val sample = remember(width) {
        val screen = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        var s = 1
        while (width / (s * 2) >= screen) s *= 2
        s
    }
    val cache = remember(result) { TileCache() }
    DisposableEffect(cache) { onDispose { cache.evictAll() } }
    val description = stringResource(R.string.orbit_share_image_title)
    LazyColumn(
        Modifier.fillMaxSize().semantics { contentDescription = description },
        contentPadding = contentPadding,
    ) {
        itemsIndexed(tiles, key = { _, tile -> tile.file.path }) { index, tile ->
            var failed by remember(tile.file) { mutableStateOf(false) }
            val band by produceState(cache.get(tile.file), tile.file) {
                if (value == null) value = withContext(Dispatchers.IO) { cache.load(tile.file, sample) }
                tiles.getOrNull(index + 1)?.let { next -> withContext(Dispatchers.IO) { cache.load(next.file, sample) } }
                if (value == null) failed = true
            }
            val shape = Modifier.fillMaxWidth().aspectRatio(width / tile.height.toFloat())
            when {
                band != null -> Image(band!!, null, shape, contentScale = ContentScale.FillWidth)
                failed -> Box(shape.background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.image_load_failed), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                else -> Box(shape.background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** The picture full screen; a tall one scrolls. Share and open at the top, save at the bottom. */
@Composable
private fun OrbitSharePreview(file: OrbitShareImage.Result?, progress: () -> Float, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    OrbitModalWindow(onDismissRequest = onDismiss, dismissEnabled = !saving) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (file == null) {
                Column(Modifier.align(Alignment.Center).width(220.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val shown = progress()
                    Text(
                        stringResource(R.string.orbit_share_image_making, (shown * 100).roundToInt()),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { shown },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.2f),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    OrbitTallPicture(file, PaddingValues(top = 76.dp, bottom = 84.dp))
                }
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) {
                    OrbitViewerButton(orbitIcon("share-2"), stringResource(R.string.orbit_file_share)) { shareOrbitFile(context) { file.file } }
                    Spacer(Modifier.width(10.dp))
                    OrbitViewerButton(orbitIcon("external-link"), stringResource(R.string.orbit_chat_text_open_other)) {
                        openOrbitFile(context) { file.file }
                    }
                }
                Surface(
                    onClick = {
                        saving = true
                        scope.launch {
                            val ok = saveToGallery(context, file.file)
                            saving = false
                            Toast.makeText(context, if (ok) R.string.orbit_chat_image_saved else R.string.orbit_chat_image_save_failed,
                                Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !saving,
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).heightIn(min = 44.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(orbitIcon("download"), null, Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (saving) R.string.orbit_chat_image_saving else R.string.orbit_chat_image_save_short),
                            style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
            OrbitViewerButton(
                Icons.Default.Close, stringResource(R.string.common_close),
                Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
                enabled = !saving,
                onClick = onDismiss,
            )
        }
    }
}

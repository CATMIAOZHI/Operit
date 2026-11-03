package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the developer view shows beyond the stored chat, kept only while Orbit's developer mode is
 * on: the request bodies exactly as they went to the model (after cards, tool roles, metadata and
 * plugins changed them), and the events a chat never shows: retries, local tool-call repairs and
 * compaction inside a turn. Kept in the cache folder, a few per chat, with a size cap.
 */
object OrbitDevRecorder {
    private const val TAG = "OrbitDevRecorder"
    private const val DIR = "orbit_dev"
    private const val EVENTS = "events.jsonl"
    /** One line per request sent, kept apart from the bodies so a turn's count stays whole. */
    private const val SENT = "sent.log"
    private const val KEEP_PER_CHAT = 6
    private const val MAX_TOTAL_BYTES = 120L * 1024 * 1024
    private const val MAX_CHAT_DIRS = 80
    private const val MAX_EVENTS = 200
    private const val MAX_EVENTS_BYTES = 256L * 1024
    private const val MAX_EVENT_TEXT = 4000
    private const val MAX_SENT_LINES = 400
    private const val MAX_SENT_BYTES = 16L * 1024
    /** Bodies waiting to be read and kept; beyond this a burst (many subagents) skips the body. */
    private const val MAX_PENDING = 4
    /** Strings this long that are only base64 are images or files: the viewer shows their size. */
    private const val DATA_MIN_LENGTH = 4096

    const val KIND_SEND = "send"
    const val KIND_TOOL = "tool"
    const val EVENT_RETRY = "retry"
    const val EVENT_REPAIR = "repair"
    const val EVENT_COMPACT = "compact"

    /** One request kept for a chat; [file] holds its body. */
    data class Request(val file: File, val at: Long, val kind: String, val bytes: Long)

    /** One event in a chat that its messages do not show. */
    data class Event(val at: Long, val kind: String, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val seq = AtomicLong()
    private val pending = AtomicInteger()
    private val revision = MutableStateFlow(0L)

    /** Bumped after each write, so an open viewer reads again. */
    val changes: StateFlow<Long> = revision.asStateFlow()

    private fun app(): Context? = try {
        OperitApplication.instance
    } catch (e: Exception) {
        null
    }

    private fun enabled(context: Context): Boolean =
        OrbitPreferences.getInstance(context).snapshot.developerMode

    private val unsafe = Regex("[^A-Za-z0-9_-]")

    private fun chatDir(context: Context, chatId: String): File =
        File(File(context.cacheDir, DIR), chatId.replace(unsafe, "_").take(64) + "_" +
            Integer.toHexString(chatId.hashCode()))

    /**
     * Keeps [body], the request just built for [chatId]. Read and written in the background; the
     * request is not held up. [label] names the provider and model it went to. Bodies are read one
     * at a time; when many arrive at once the extra bodies are skipped, their count still noted.
     * [keepBody] false only counts the send: a retry that sends the same body again.
     */
    fun request(chatId: String?, kind: String, label: String, body: okhttp3.RequestBody, keepBody: Boolean = true) {
        val id = chatId?.takeIf { it.isNotBlank() } ?: return
        val context = app() ?: return
        if (!enabled(context)) return
        val at = System.currentTimeMillis()
        val queued = keepBody && pending.incrementAndGet() <= MAX_PENDING
        if (keepBody && !queued) pending.decrementAndGet()
        // Only a queued body is held by the work below; a skipped one is let go right away.
        val bodyToKeep = body.takeIf { queued }
        scope.launch {
            try {
                mutex.withLock {
                    val root = File(context.cacheDir, DIR)
                    val dir = chatDir(context, id).apply { mkdirs() }
                    noteSent(File(dir, SENT), at, kind)
                    if (bodyToKeep != null) {
                        // Read and parsed inside the lock: only one large body is held at a time.
                        val raw = okio.Buffer().also { bodyToKeep.writeTo(it) }.readUtf8()
                        val parsed: Any = runCatching { omitData(JSONObject(raw)) }.getOrNull() ?: raw
                        val record = JSONObject()
                            .put("at", at).put("kind", kind).put("label", label).put("chatId", id)
                            .put("bytes", raw.length).put("body", parsed)
                        File(dir, "req_${at}_${seq.incrementAndGet()}_$kind.json").writeText(record.toString(), Charsets.UTF_8)
                        trimChat(dir)
                    }
                    trimAll(root)
                }
                revision.value += 1
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not keep the request for the developer view", e)
            } finally {
                if (queued) pending.decrementAndGet()
            }
        }
    }

    private fun noteSent(file: File, at: Long, kind: String) {
        file.appendText("$at $kind\n", Charsets.UTF_8)
        if (file.length() > MAX_SENT_BYTES) {
            val kept = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(MAX_SENT_LINES)
            file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    /** Notes an event in [chatId] that its messages do not show. */
    fun event(chatId: String?, kind: String, text: String) {
        val id = chatId?.takeIf { it.isNotBlank() } ?: return
        val context = app() ?: return
        if (!enabled(context)) return
        val line = JSONObject().put("at", System.currentTimeMillis()).put("kind", kind)
            .put("text", text.take(MAX_EVENT_TEXT)).toString()
        scope.launch {
            try {
                mutex.withLock {
                    val root = File(context.cacheDir, DIR)
                    val file = File(chatDir(context, id).apply { mkdirs() }, EVENTS)
                    file.appendText(line + "\n", Charsets.UTF_8)
                    if (file.length() > MAX_EVENTS_BYTES) {
                        // The newest lines that fit in three quarters of the budget, at most MAX_EVENTS.
                        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(MAX_EVENTS)
                        var size = 0L
                        val kept = lines.asReversed().takeWhile {
                            size += it.toByteArray(Charsets.UTF_8).size + 1
                            size <= MAX_EVENTS_BYTES * 3 / 4
                        }.asReversed()
                        file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
                    }
                    trimAll(root)
                }
                revision.value += 1
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not note the event for the developer view", e)
            }
        }
    }

    /** The requests kept for [chatId], newest first. */
    suspend fun requests(context: Context, chatId: String): List<Request> = withContext(Dispatchers.IO) {
        val files = chatDir(context, chatId).listFiles { f -> f.name.startsWith("req_") } ?: return@withContext emptyList()
        files.mapNotNull { f ->
            val parts = f.name.removeSuffix(".json").split('_')
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            Request(f, at, parts.getOrNull(3) ?: KIND_SEND, f.length())
        }.sortedWith(compareByDescending<Request> { it.at }.thenByDescending { it.file.name })
    }

    /** The record of [request]: its time, kind, label and the body as sent (data cut short). */
    suspend fun read(request: Request): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(request.file.readText(Charsets.UTF_8))
    }

    /** When each request of [chatId] was sent (the latest few hundred), bodies kept or not. */
    suspend fun sentTimes(context: Context, chatId: String): List<Long> = withContext(Dispatchers.IO) {
        val file = File(chatDir(context, chatId), SENT)
        if (!file.exists()) return@withContext emptyList()
        mutex.withLock { file.readLines(Charsets.UTF_8) }.mapNotNull { it.substringBefore(' ').toLongOrNull() }
    }

    /** The events kept for [chatId], newest first. */
    suspend fun events(context: Context, chatId: String): List<Event> = withContext(Dispatchers.IO) {
        val file = File(chatDir(context, chatId), EVENTS)
        if (!file.exists()) return@withContext emptyList()
        mutex.withLock { file.readLines(Charsets.UTF_8) }.asReversed().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                Event(json.getLong("at"), json.getString("kind"), json.optString("text"))
            }.getOrNull()
        }
    }

    private fun trimChat(dir: File) {
        val requests = dir.listFiles { f -> f.name.startsWith("req_") }?.sortedByDescending { it.name } ?: return
        requests.drop(KEEP_PER_CHAT).forEach { it.delete() }
    }

    /**
     * Under the size cap and the chat count, the oldest going first. Event and count logs count
     * toward the cap; they are bounded per chat, so dropping old bodies brings the total down.
     */
    private fun trimAll(root: File) {
        val dirs = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
        dirs.drop(MAX_CHAT_DIRS).forEach { it.deleteRecursively() }
        val files = dirs.take(MAX_CHAT_DIRS).flatMap { it.listFiles()?.toList().orEmpty() }
        val requests = files.filter { it.name.startsWith("req_") }
            .sortedBy { it.name.split('_').getOrNull(1)?.toLongOrNull() ?: 0L }
        var total = files.sumOf { it.length() }
        for (file in requests) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= file.length()
            file.delete()
        }
    }

    private val base64 = Regex("^[A-Za-z0-9+/=_-]+$")

    /** Replaces images and files sent as base64 with their size, in place. */
    private fun omitData(value: Any?): Any? = when (value) {
        is JSONObject -> value.apply { keys().asSequence().toList().forEach { put(it, omitData(get(it))) } }
        is JSONArray -> value.apply { for (i in 0 until length()) put(i, omitData(get(i))) }
        is String -> dataLabel(value) ?: value
        else -> value
    }

    private fun dataLabel(text: String): String? {
        if (text.length < DATA_MIN_LENGTH) return null
        val comma = text.indexOf(";base64,")
        val type = if (text.startsWith("data:") && comma in 6..200) text.substring(5, comma) else null
        val payload = if (type != null) text.substring(comma + 8) else text
        if (!base64.matches(payload.take(1024))) return null
        val kb = payload.length.toLong() * 3 / 4 / 1024
        return "[base64 omitted: ${type ?: "data"}, ~$kb KB]"
    }
}

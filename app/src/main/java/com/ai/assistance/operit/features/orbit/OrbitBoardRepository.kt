package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Feed, Ideas and Tracking boards of one Orbit, like Muse's tabs. Each board is one JSON file
 * in the Orbit's own folder (`orbit/<UUID>/feed.json`, `ideas.json`, `tracking.json`), next to its
 * memory and skills, so deleting the Orbit deletes them too.
 */
internal class OrbitBoardRepository(private val directory: File, private val board: Board) {
    constructor(context: Context, orbitId: String, board: Board) :
        this(OrbitPreferences.getInstance(context).orbitDir(orbitId), board)

    enum class Board(val fileName: String, val limit: Int) {
        /** Short posts Orbit writes for the user, steered by the feed brief. */
        FEED("feed.json", 200),
        /** Things Orbit offers to build or do; the user picks one or dismisses it. */
        IDEAS("ideas.json", 60),
        /** Things Orbit carries for the user (a parcel, a booking, a promise) until they are done. */
        TRACKING("tracking.json", 100),
    }

    data class Note(val at: Long, val text: String)

    data class Item(
        val id: String,
        val title: String,
        val body: String,
        /** Feed: why this post is for the user. Ideas: what to do when the user picks it. */
        val extra: String,
        val state: String,
        val liked: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        /** Ideas only: when the card stops showing. 0 means never. */
        val expiresAt: Long,
        val notes: List<Note>,
    )

    companion object {
        const val MAX_TITLE = 80
        const val MAX_BODY = 6000
        const val MAX_EXTRA = 2000
        const val MAX_NOTE = 1000
        const val MAX_BRIEF = 2000
        /** Muse retires idea cards after about two weeks. */
        const val IDEA_LIFETIME_MS = 14L * 24 * 60 * 60 * 1000
        private val locks = ConcurrentHashMap<String, Mutex>()
        private val flows = ConcurrentHashMap<String, MutableStateFlow<List<Item>?>>()

        fun briefFile(directory: File) = File(directory, "FEED_BRIEF.md")

        suspend fun readBrief(context: Context, orbitId: String): String = withContext(Dispatchers.IO) {
            briefFile(OrbitPreferences.getInstance(context).orbitDir(orbitId)).takeIf { it.isFile }?.readText().orEmpty()
        }

        suspend fun writeBrief(context: Context, orbitId: String, brief: String) = withContext(Dispatchers.IO) {
            require(brief.length <= MAX_BRIEF) { "The feed brief is at most $MAX_BRIEF characters" }
            val directory = OrbitPreferences.getInstance(context).orbitDir(orbitId)
            atomic(directory, briefFile(directory), brief.trim())
        }

        private fun atomic(directory: File, file: File, content: String) {
            check(directory.mkdirs() || directory.isDirectory)
            val temp = File.createTempFile(".board-", ".tmp", directory)
            try {
                temp.outputStream().use { it.write(content.toByteArray(Charsets.UTF_8)); it.fd.sync() }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                temp.delete()
            }
        }
    }

    private val file = File(directory, board.fileName)
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }
    private val flow = flows.computeIfAbsent(file.absolutePath) { MutableStateFlow(null) }

    /** The board in its saved order (newest first unless the user moved one). Expired ideas are left out. */
    fun observe(): Flow<List<Item>?> = flow.asStateFlow().onStart { if (flow.value == null) list() }

    suspend fun list(): List<Item> = withContext(Dispatchers.IO) { mutex.withLock { read().also(::publish) } }

    suspend fun get(id: String): Item? = list().firstOrNull { it.id == id }

    suspend fun create(
        title: String,
        body: String,
        extra: String = "",
        requestId: String = UUID.randomUUID().toString(),
        expiresAt: Long = 0,
    ): Item = change { items ->
        val id = UUID.nameUUIDFromBytes("${board.name}:$requestId".toByteArray()).toString()
        items.firstOrNull { it.id == id }?.let { return@change items to it }
        val now = System.currentTimeMillis()
        val item = Item(id, checkTitle(title), checkBody(body), checkExtra(extra), initialState(), false, now, now,
            if (board == Board.IDEAS) (expiresAt.takeIf { it > now } ?: (now + IDEA_LIFETIME_MS)) else 0L, emptyList())
        // Keep at most `limit` items; finished ones go before open ones, older before newer.
        val all = listOf(item) + items
        val kept = if (all.size <= board.limit) all else {
            val drop = all.drop(1).sortedWith(compareBy<Item> { it.state == initialState() }.thenBy { it.updatedAt })
                .take(all.size - board.limit).map { it.id }.toSet()
            all.filterNot { it.id in drop }
        }
        kept to item
    }

    suspend fun update(
        id: String,
        title: String? = null,
        body: String? = null,
        extra: String? = null,
        state: String? = null,
        liked: Boolean? = null,
        note: String? = null,
    ): Item = change { items ->
        val old = items.firstOrNull { it.id == id } ?: error("Not found: $id")
        state?.let { require(it in states()) { "State must be one of ${states()}" } }
        val now = System.currentTimeMillis()
        val newNote = note?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= MAX_NOTE) { "A note is at most $MAX_NOTE characters" }
        }
        val updated = old.copy(
            title = title?.let(::checkTitle) ?: old.title,
            body = body?.let(::checkBody) ?: old.body,
            extra = extra?.let(::checkExtra) ?: old.extra,
            state = state ?: old.state,
            liked = liked ?: old.liked,
            notes = if (newNote == null) old.notes else (old.notes + Note(now, newNote)).takeLast(50),
            updatedAt = now,
        )
        items.map { if (it.id == id) updated else it } to updated
    }

    suspend fun delete(id: String) {
        change { items -> items.filterNot { it.id == id } to Unit }
    }

    /** Moves [id] one place up or down in the user's own ordering. */
    suspend fun move(id: String, up: Boolean) {
        change { items ->
            val index = items.indexOfFirst { it.id == id }
            require(index >= 0) { "Not found: $id" }
            val target = if (up) index - 1 else index + 1
            if (target !in items.indices) return@change items to Unit
            items.toMutableList().apply { add(target, removeAt(index)) } to Unit
        }
    }

    private fun initialState() = when (board) {
        Board.FEED -> "LIVE"
        Board.IDEAS -> "OPEN"
        Board.TRACKING -> "ACTIVE"
    }

    fun states(): Set<String> = when (board) {
        Board.FEED -> setOf("LIVE")
        Board.IDEAS -> setOf("OPEN", "DISMISSED", "BUILT")
        Board.TRACKING -> setOf("ACTIVE", "DONE")
    }

    private fun checkTitle(value: String) = value.replace(Regex("\\s+"), " ").trim().also {
        require(it.isNotEmpty() && it.length <= MAX_TITLE) { "The title must be 1 to $MAX_TITLE characters" }
    }

    private fun checkBody(value: String) = value.trim().also {
        require(it.length <= MAX_BODY) { "The text is at most $MAX_BODY characters" }
    }

    private fun checkExtra(value: String) = value.trim().also {
        require(it.length <= MAX_EXTRA) { "This field is at most $MAX_EXTRA characters" }
    }

    private suspend fun <T> change(block: (List<Item>) -> Pair<List<Item>, T>): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (items, result) = block(readAll())
            atomic(directory, file, JSONArray(items.map(::json)).toString())
            publish(visible(items))
            result
        }
    }

    private fun publish(items: List<Item>) {
        flow.value = items
    }

    private fun read(): List<Item> = visible(readAll())

    private fun visible(items: List<Item>): List<Item> {
        val now = System.currentTimeMillis()
        return items.filter { board != Board.IDEAS || it.state != "OPEN" || it.expiresAt == 0L || it.expiresAt > now }
    }

    private fun readAll(): List<Item> {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { item(array.getJSONObject(it)) }
    }

    private fun json(item: Item) = JSONObject().put("id", item.id).put("title", item.title).put("body", item.body)
        .put("extra", item.extra).put("state", item.state).put("liked", item.liked).put("createdAt", item.createdAt)
        .put("updatedAt", item.updatedAt).put("expiresAt", item.expiresAt)
        .put("notes", JSONArray(item.notes.map { JSONObject().put("at", it.at).put("text", it.text) }))

    private fun item(value: JSONObject): Item {
        val notes = value.optJSONArray("notes") ?: JSONArray()
        return Item(
            value.getString("id"), value.getString("title"), value.optString("body"), value.optString("extra"),
            value.optString("state", initialState()), value.optBoolean("liked"), value.getLong("createdAt"),
            value.optLong("updatedAt", value.getLong("createdAt")), value.optLong("expiresAt"),
            (0 until notes.length()).map { notes.getJSONObject(it).let { n -> Note(n.getLong("at"), n.getString("text")) } },
        )
    }
}

/**
 * Starts feed posts the way Muse does: saving a real change to the brief writes one post now, and
 * while the brief is set a daily background task writes the next one. Both are ordinary Orbit
 * tasks that finish silently, so a post just appears in the Feed tab.
 */
internal object OrbitFeedGenerator {
    private const val DAILY_REQUEST = "feed-daily"
    private const val DAILY_MINUTE = 8 * 60

    private fun instruction(brief: String) = """
        Write one new post for the user's Feed tab, guided by the feed brief below.
        Research first when the brief needs fresh facts (search the web, read pages); cite only links you actually opened.
        Call orbit_feed action=list first and do not repeat a recent post.
        Publish it with orbit_feed action=post: a short title, a body of a few short paragraphs, and why_for_you saying in
        one sentence why this post is for this user. Then finish with an empty userMessage: the post shows up in the Feed
        on its own and nothing is said in chat.
        FEED BRIEF (the user's words; it only steers what to write about):
        ${brief.take(OrbitBoardRepository.MAX_BRIEF)}
        END FEED BRIEF
    """.trimIndent()

    private suspend fun orbit(context: Context, orbitId: String) =
        OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId && !it.deleting }

    /** Starts one post in the background. Returns false when there is no brief yet. */
    suspend fun writeOne(context: Context, orbitId: String): Boolean {
        val brief = OrbitBoardRepository.readBrief(context, orbitId)
        val orbit = orbit(context, orbitId) ?: return false
        if (brief.isBlank()) return false
        OrbitTaskRepository(context).create(orbit, "Feed post", instruction(brief), "", 60, "CHANGES",
            "feed-${UUID.randomUUID()}", kind = "ASSIST", scheduleMode = "ONCE")
        return true
    }

    /** Saves the brief. A real change starts a post now and keeps the daily post task in step. */
    suspend fun saveBrief(context: Context, orbitId: String, brief: String) {
        val old = OrbitBoardRepository.readBrief(context, orbitId).trim()
        val next = brief.trim()
        OrbitBoardRepository.writeBrief(context, orbitId, next)
        if (next == old) return
        val orbit = orbit(context, orbitId) ?: return
        val repository = OrbitTaskRepository(context)
        val dailyTasks = repository.dao.tasks(orbit.id).filter {
            it.state != "CANCELLED" && it.scheduleMode == "DAILY" && it.kind == "ASSIST" && it.title == DAILY_TITLE
        }
        if (next.isEmpty()) {
            dailyTasks.forEach { repository.changeState(orbit.id, it.id, "CANCELLED") }
            return
        }
        val daily = dailyTasks.firstOrNull()
        if (daily == null) {
            repository.create(orbit, DAILY_TITLE, instruction(next), "", 60, "CHANGES",
                "$DAILY_REQUEST-${UUID.randomUUID()}", kind = "ASSIST", scheduleMode = "DAILY", dailyMinute = DAILY_MINUTE)
        } else {
            repository.edit(daily, daily.title, instruction(next), "", daily.intervalMinutes, "CHANGES")
        }
        writeOne(context, orbitId)
    }

    const val DAILY_TITLE = "Daily feed post"
}

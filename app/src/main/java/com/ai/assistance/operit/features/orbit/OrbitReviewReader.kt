package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.dao.OrbitWindowMessage
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.ChatUtils
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The nightly alignment review reads its whole window itself, page by page, and writes down what
 * it learned as it goes (Muse's dream reads every turn). Nothing is fed into its prompt: the app
 * only keeps two positions for it, how far it has read and how far it has written down, so a run
 * that is compacted, stopped by its time limit or killed goes on from the last written page.
 * The window (its chats, start and end) is fixed when a review begins and stays fixed until the
 * whole of it has been read, so a review that takes several runs counts every turn exactly once.
 */
internal class OrbitReviewReader private constructor(private val context: Context, private val orbitId: String) {
    private val file = File(OrbitPreferences.getInstance(context).orbitDir(orbitId), "review_reading.json")
    private val dao get() = AppDatabase.getDatabase(context).orbitTaskDao()

    /** A reading position: everything up to message ([ts], [id]) is read, or that message up to char [off]. */
    private data class Pos(val ts: Long, val id: Long, val off: Int = 0) {
        fun json() = JSONObject().put("ts", ts).put("id", id).put("off", off)
        companion object {
            fun parse(o: JSONObject) = Pos(o.getLong("ts"), o.getLong("id"), o.optInt("off"))
        }
    }

    /** What the review shows on top of its prompt instead of the messages themselves. */
    data class Opened(val header: String)

    private suspend fun <T> locked(block: suspend () -> T): T = lock(orbitId).withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private fun state(): JSONObject = if (file.isFile) runCatching { JSONObject(file.readText()) }
        .getOrElse { JSONObject() } else JSONObject()

    private fun save(state: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(".reading-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { it.write(state.toString().toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
    }

    private fun chats(session: JSONObject): Map<String, String> {
        val o = session.getJSONObject("chats")
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /**
     * Begins a review, or picks up the one an earlier run left unfinished. Empty when the user said
     * nothing since the last review, so the run is skipped.
     */
    suspend fun open(now: Long, legacySince: Long?): Opened? = locked {
        val orbit = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId }
            ?: return@locked null
        val state = state()
        var session = state.optJSONObject("session")
        var resumed = session != null
        session?.let { flushNotes(state, it) }
        if (session != null && session.getInt("pending_pages") > 0) {
            // A new run has none of the last run's unwritten pages in its context: read them again.
            unread(session)
            save(state)
        }
        if (session == null) {
            val reviewed = state.optLong("reviewed_until", 0).takeIf { it in 1 until now }
            // The first review (or the first after the old excerpt review) looks back a few days.
            val since = reviewed?.coerceAtLeast(now - MAX_BACKLOG_MS)
                ?: (legacySince?.takeIf { it < now } ?: (now - FIRST_WINDOW_MS)).coerceAtLeast(now - MAX_WINDOW_MS)
            val labels = linkedMapOf(orbit.chatId to "main")
            orbit.sideChats.forEach { labels[it.chatId] = "side: ${it.title.take(40)}" }
            val ids = labels.keys.toList()
            val users = dao.reviewCount(ids, since, now, listOf("user"))
            if (users == 0) return@locked null
            session = JSONObject().put("since", since).put("until", now)
                .put("chats", JSONObject(labels as Map<*, *>))
                .put("total", dao.reviewCount(ids, since, now, listOf("user", "ai"))).put("user_total", users)
                .put("read", Pos(since, Long.MAX_VALUE).json()).put("committed", Pos(since, Long.MAX_VALUE).json())
                .put("read_n", 0).put("committed_n", 0)
                .put("pages", 0).put("pending_pages", 0).put("pending_users", JSONArray()).put("pending_ids", JSONArray())
                .put("user_read", 0).put("ruptures", JSONArray()).put("notes", 0)
            // The fresh notes file is started through the same saved-then-written step as a commit, so a
            // run stopped in between never takes the last window's notes for this one's.
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            session.put("writing", "# Review notes ${time.format(Date(since))} -> ${time.format(Date(now))}\n\n")
                .put("writing_new", true)
            state.put("session", session)
            save(state)
            resumed = false
            flushNotes(state, session)
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val latestDream = runCatching { OrbitFiles.of(context, orbitId).latestDream() }
            .onFailure { if (it is CancellationException) throw it }.getOrNull()
        val progress = progress(session)
        Opened(buildString {
            append("ALIGNMENT REVIEW WINDOW\n")
            append("today: ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))}\n")
            append("window: ${time.format(Date(session.getLong("since")))} -> ${time.format(Date(session.getLong("until")))} (local time)\n")
            append("messages: ${session.getInt("total")} (user turns: ${session.getInt("user_total")})\n")
            if (resumed) append("resumed: an earlier run already read and wrote down ${progress.getInt("committed")} of them; " +
                "your notes so far are in ${OrbitFiles.REVIEW_NOTES}\n")
            append("latest_dream: ${latestDream ?: "none"}\n")
            append("The messages are not shown here. Read them yourself with $TOOL, from where the review stands to the end.")
        })
    }

    private suspend fun progress(session: JSONObject): JSONObject {
        val ids = chats(session).keys.toList()
        val since = session.getLong("since")
        val until = session.getLong("until")
        suspend fun through(p: Pos) = if (p.id == Long.MAX_VALUE) 0 else
            dao.reviewCountThrough(ids, since, until, p.ts, p.id) - (if (p.off > 0) 1 else 0)
        val committed = Pos.parse(session.getJSONObject("committed"))
        // Counts are kept as pages are read; only a session from before they existed counts them here.
        val readCount = if (session.has("read_n")) session.getInt("read_n") else through(Pos.parse(session.getJSONObject("read")))
        val committedCount = if (session.has("committed_n")) session.getInt("committed_n") else through(committed)
        val more = committed.off > 0 || dao.reviewMessages(ids, since, until, committed.ts, committed.id, 1).isNotEmpty()
        return JSONObject().put("total", session.getInt("total")).put("read", readCount)
            .put("committed", committedCount).put("uncommitted_pages", session.getInt("pending_pages"))
            .put("user_turns", session.getInt("user_total")).put("user_turns_written_down", session.getInt("user_read"))
            .put("rupture_turns", session.getJSONArray("ruptures").length())
            .put("done", !more && session.getInt("pending_pages") == 0)
    }

    /** True when the open review has read and written down its whole window. */
    suspend fun done(): Boolean = locked {
        val state = state()
        val session = state.optJSONObject("session") ?: return@locked true
        flushNotes(state, session)
        progress(session).getBoolean("done")
    }

    /** True while a review has begun and is not finished. */
    suspend fun isOpen(): Boolean = locked { state().optJSONObject("session") != null }

    /** "read 40 of 120" for the run's checkpoint. */
    suspend fun summary(): String = locked {
        val session = state().optJSONObject("session") ?: return@locked ""
        val p = progress(session)
        "${p.getInt("committed")}/${p.getInt("total")}"
    }

    /** The whole window is read: the next review starts where this one ended. */
    suspend fun finish() = locked {
        val state = state()
        val session = state.optJSONObject("session") ?: return@locked
        // Notes not yet in the file go there first; a review is never closed with them still pending.
        flushNotes(state, session)
        check(progress(session).getBoolean("done")) { "The review window is not read and written down yet" }
        state.put("reviewed_until", session.getLong("until")).remove("session")
        save(state)
        lastLong = null
    }

    /** Drops the long-message copy once a run of this Orbit ends. */
    fun release() {
        if (lastLong?.first == orbitId) lastLong = null
    }

    /**
     * After the run's context is compacted, the pages it read but did not write down are gone from
     * its memory, so they are given to it again.
     */
    fun rewind() {
        kotlinx.coroutines.runBlocking {
            locked {
                val state = state()
                val session = state.optJSONObject("session") ?: return@locked
                if (session.getInt("pending_pages") == 0) return@locked
                unread(session)
                save(state)
            }
        }
    }

    /** Moves the reading position back to the last written-down page. */
    private fun unread(session: JSONObject) {
        session.put("read", session.getJSONObject("committed")).put("pending_pages", 0)
            .put("pending_users", JSONArray()).put("pending_ids", JSONArray()).remove("pending_from")
        if (session.has("committed_n")) session.put("read_n", session.getInt("committed_n"))
    }

    /**
     * A commit saves its notes in the state together with the new position, then copies them into
     * the notes file. A run stopped in between copies them here, once: notes already at the end of
     * the file are not written again.
     */
    private suspend fun flushNotes(state: JSONObject, session: JSONObject) {
        val block = session.optString("writing").takeIf { it.isNotBlank() } ?: return
        val files = OrbitFiles.of(context, orbitId)
        if (session.optBoolean("writing_new")) files.write(OrbitFiles.REVIEW_NOTES, block)
        else {
            val current = runCatching { files.read(OrbitFiles.REVIEW_NOTES) }
                .onFailure { if (it is CancellationException) throw it }.getOrDefault("")
            if (!current.trimEnd().endsWith(block.trim())) files.append(OrbitFiles.REVIEW_NOTES, block)
        }
        session.remove("writing")
        session.remove("writing_new")
        save(state)
    }

    suspend fun execute(runId: String, args: Map<String, String>): JSONObject = locked {
        val state = state()
        val session = state.optJSONObject("session")
            ?: error("No alignment review is open; this tool only works inside the nightly alignment review")
        flushNotes(state, session)
        when (val action = args["action"]?.trim().orEmpty()) {
            "next" -> next(state, session)
            "commit" -> commit(state, session, runId, args["notes"].orEmpty(), args["ruptures"].orEmpty())
            "look" -> look(session, args)
            "status" -> progress(session).put("notes_file", OrbitFiles.REVIEW_NOTES)
            else -> error("Unknown action \"${action.take(20)}\". Use next, commit, look or status.")
        }
    }

    private suspend fun next(state: JSONObject, session: JSONObject): JSONObject {
        require(session.getInt("pending_pages") < MAX_UNCOMMITTED_PAGES) {
            "You have read $MAX_UNCOMMITTED_PAGES pages without writing them down. Call action=commit with notes on " +
                "them first (\"nothing new\" is fine), then read on."
        }
        val chats = chats(session)
        val ids = chats.keys.toList()
        val since = session.getLong("since")
        val until = session.getLong("until")
        var pos = Pos.parse(session.getJSONObject("read"))
        val messages = JSONArray()
        val users = session.getJSONArray("pending_users")
        val userIds = session.optJSONArray("pending_ids") ?: JSONArray()
        var finished = 0
        var budget = PAGE_CHARS
        val reactions = chats.keys.associateWith { reactionsOf(it) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val first = pos
        fun add(m: OrbitWindowMessage, from: Int): Int {
            val text = cached(m.messageId)?.takeIf { from > 0 } ?: clean(m)
            val start = from.coerceIn(0, text.length)
            val end = minOf(text.length, start + budget.coerceAtLeast(MIN_SLICE))
            messages.put(entry(m, chats, reactions, time).put("text", text.substring(start, end)).apply {
                if (start > 0 || end < text.length) put("part", "chars ${start + 1}-$end of ${text.length}")
            })
            if (m.sender == "user" && start == 0) users.put(m.timestamp)
            // Any slice of a user message may show the friction, so each one can be named in ruptures.
            if (m.sender == "user") userIds.put(m.timestamp)
            budget -= end - start
            if (end < text.length) {
                // The rest comes on the next page: keep the cleaned text rather than load it again.
                remember(m, text)
                return end
            }
            if (cached(m.messageId) != null) lastLong = null
            finished++
            return 0
        }
        if (pos.off > 0) {
            val m = lastLong?.takeIf { it.first == orbitId && it.second.messageId == pos.id }?.second
                ?: dao.reviewMessage(pos.id)?.let { full(it) }
            val length = m?.let { cached(it.messageId)?.length ?: clean(it).also { t -> remember(it, t) }.length } ?: 0
            pos = if (m == null || pos.off >= length) Pos(pos.ts, pos.id) else Pos(pos.ts, pos.id, add(m, pos.off))
        }
        loop@ while (pos.off == 0 && budget >= MIN_SLICE) {
            val rows = dao.reviewMessages(ids, since, until, pos.ts, pos.id, 20)
            if (rows.isEmpty()) break
            for (raw in rows) {
                // A huge message is read back only when the page gets to it.
                val m = full(raw)
                pos = Pos(m.timestamp, m.messageId, add(m, 0))
                if (pos.off > 0 || budget < MIN_SLICE) break@loop
            }
        }
        if (messages.length() == 0) {
            if (pos != first) {
                // The rest of a message that was cut was deleted or shortened since: step past it, or
                // the review would wait on it forever.
                session.put("read", pos.json())
                if (Pos.parse(session.getJSONObject("committed")) == first) session.put("committed", pos.json())
                save(state)
            }
            return progress(session).put("messages", JSONArray()).put("note",
                if (session.getInt("pending_pages") > 0) "Nothing more to read. Write down the pages you read with action=commit."
                else "The whole window is read and written down. Go on with the rest of the review.")
        }
        val page = session.getInt("pages") + 1
        if (session.getInt("pending_pages") == 0) session.put("pending_from", page)
        session.put("read", pos.json()).put("pages", page).put("pending_pages", session.getInt("pending_pages") + 1)
            .put("pending_users", users).put("pending_ids", userIds)
        if (session.has("read_n")) session.put("read_n", session.getInt("read_n") + finished)
        save(state)
        val more = pos.off > 0 || dao.reviewMessages(ids, since, until, pos.ts, pos.id, 1).isNotEmpty()
        return JSONObject().put("page", page).put("messages", messages).put("more", more)
            .put("position", progress(session).let { "read ${it.getInt("read")} of ${it.getInt("total")} messages" })
            .put("uncommitted_pages", session.getInt("pending_pages"))
            .put("next", if (session.getInt("pending_pages") >= MAX_UNCOMMITTED_PAGES || !more)
                "Write down what these pages show with action=commit before anything else."
            else "Write down what this page shows with action=commit (you may read up to " +
                "$MAX_UNCOMMITTED_PAGES pages before committing when a matter runs across pages).")
            .apply { if (first.off > 0) put("continues", "The first message goes on from where the last page stopped.") }
    }

    private suspend fun commit(state: JSONObject, session: JSONObject, runId: String, notes: String, ruptures: String): JSONObject {
        require(session.getInt("pending_pages") > 0) { "Nothing read since the last commit. Call action=next." }
        val text = notes.trim()
        require(text.isNotEmpty()) {
            "notes is empty. Write what these pages show about working with the user, or \"nothing new\"."
        }
        require(text.length <= MAX_NOTES) { "notes has ${text.length} characters; at most $MAX_NOTES" }
        val pendingUsers = session.getJSONArray("pending_users").let { a -> (0 until a.length()).map { a.getLong(it) } }
        val onPages = (session.optJSONArray("pending_ids") ?: session.getJSONArray("pending_users"))
            .let { a -> (0 until a.length()).map { a.getLong(it) }.distinct() }
        val ids = ruptures.split(Regex("[\\s,，#]+")).filter { it.isNotBlank() }.map {
            it.toLongOrNull() ?: error("ruptures must be message ids (numbers) separated by commas, got \"${it.take(30)}\"")
        }.distinct()
        val stray = ids.filter { it !in onPages }
        require(stray.isEmpty()) {
            "ruptures may only name the user's messages on the pages you are committing; not among them: " +
                stray.joinToString() + ". User message ids on these pages: " + onPages.joinToString()
        }
        val from = session.optInt("pending_from", session.getInt("pages"))
        val to = session.getInt("pages")
        val block = buildString {
            append(if (from == to) "## Page $from\n" else "## Pages $from-$to\n")
            append(text).append('\n')
            if (ids.isNotEmpty()) append("ruptures: ${ids.joinToString()}\n")
            append('\n')
        }
        val notesSize = runCatching { OrbitFiles.of(context, orbitId).read(OrbitFiles.REVIEW_NOTES).length }
            .onFailure { if (it is CancellationException) throw it }.getOrDefault(0)
        require(notesSize + block.length <= NOTES_FULL) {
            "${OrbitFiles.REVIEW_NOTES} is full ($notesSize characters). Rewrite it shorter with orbit_file write " +
                "(keep message ids and ruptures), then commit again."
        }
        val all = session.getJSONArray("ruptures")
        val known = (0 until all.length()).map { all.getLong(it) }.toSet()
        ids.filter { it !in known }.forEach { all.put(it) }
        session.put("committed", session.getJSONObject("read")).put("pending_pages", 0)
            .put("user_read", session.getInt("user_read") + pendingUsers.size).put("pending_users", JSONArray())
            .put("pending_ids", JSONArray())
            .put("notes", session.optInt("notes") + 1).put("last_run", runId).put("writing", block).remove("pending_from")
        if (session.has("read_n")) session.put("committed_n", session.getInt("read_n"))
        save(state)
        flushNotes(state, session)
        val p = progress(session)
        return p.put("saved", true).put("notes_file", OrbitFiles.REVIEW_NOTES)
            .put("written", "Pages ${if (from == to) "$from" else "$from-$to"}: ${text.length} characters of notes" +
                if (ids.isEmpty()) "" else ", ruptures ${ids.joinToString()}")
            .apply { if (notesSize + block.length > NOTES_LONG) put("notes_file_size", notesSize + block.length).put("tidy",
                "Your notes file is getting long. Before reading on, rewrite it shorter with orbit_file write, " +
                    "keeping message ids, ruptures and open matters.") }
            .put("next", if (p.getBoolean("done")) "The whole window is read and written down. Read your notes in " +
                "${OrbitFiles.REVIEW_NOTES} and finish the review with the counts above."
            else "Call action=next to read on.")
    }

    /** Earlier (or any) messages of this Orbit's chats, without moving the review's position. */
    private suspend fun look(session: JSONObject, args: Map<String, String>): JSONObject {
        val id = args["message_id"]?.trim()?.removePrefix("#")?.toLongOrNull()
            ?: error("look needs message_id (a message's id from a page)")
        val count = args["count"]?.trim()?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val chats = chats(session)
        val ids = chats.keys.toList()
        val rows = dao.reviewMessages(ids, 0, Long.MAX_VALUE, id - 1, Long.MAX_VALUE, count)
        require(rows.isNotEmpty()) { "No message $id in this Orbit's chats" }
        val start = args["start_char"]?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val reactions = chats.keys.associateWith { reactionsOf(it) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return JSONObject().put("messages", JSONArray(rows.mapIndexed { i, raw ->
            val m = full(raw)
            val text = clean(m)
            val from = if (i == 0) (start - 1).coerceIn(0, text.length) else 0
            val end = minOf(text.length, from + LOOK_CHARS)
            entry(m, chats, reactions, time).put("text", text.substring(from, end)).apply {
                if (from > 0 || end < text.length) put("part", "chars ${from + 1}-$end of ${text.length}")
                    .put("more_with", "look message_id=${m.timestamp} count=1 start_char=${end + 1}".takeIf { end < text.length })
            }
        })).put("note", "Looking back does not move your reading position.")
    }

    private fun entry(m: OrbitWindowMessage, chats: Map<String, String>, reactions: Map<String, Map<String, String>>,
        time: SimpleDateFormat): JSONObject {
        val byKey = reactions[m.chatId].orEmpty()
        val emoji = listOfNotNull(byKey[m.timestamp.toString()]?.let { "user reacted $it" }) +
            byKey.filterKeys { it.toLongOrNull() == null && OrbitReactions.timeOf(it) == m.timestamp }
                .values.map { "user reacted $it to a card Orbit sent" }
        return JSONObject().put("message_id", m.timestamp).put("chat", chats[m.chatId] ?: "main")
            .put("time", time.format(Date(m.timestamp))).put("from", if (m.sender == "user") "user" else "you")
            .apply { if (emoji.isNotEmpty()) put("reactions", emoji.joinToString("; ")) }
    }

    private suspend fun reactionsOf(chatId: String): Map<String, String> = try {
        OrbitReactions.ofUser(context, orbitId).observe(chatId).first()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyMap()
    }

    private fun clean(m: OrbitWindowMessage) =
        (if (m.sender == "ai") ChatUtils.removeThinkingContent(m.content) else m.content).trim()

    /** A message too large for one row is read back in 32 KB pieces. */
    private suspend fun full(m: OrbitWindowMessage): OrbitWindowMessage {
        if (!m.truncated) return m
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val part = dao.reviewMessageBytes(m.messageId, bytes.size().toLong())
            if (part == null || part.isEmpty()) break
            bytes.write(part)
        }
        return m.copy(content = bytes.toString(Charsets.UTF_8.name()), truncated = false)
    }

    private fun cached(messageId: Long): String? =
        lastLong?.takeIf { it.first == orbitId && it.second.messageId == messageId }?.third
    private fun remember(m: OrbitWindowMessage, text: String) {
        lastLong = Triple(orbitId, m, text)
    }

    companion object {
        const val TOOL = "orbit_review_read"
        /** About 4,000 tokens of chat a page: a few pages fit before the context is compacted. */
        private const val PAGE_CHARS = 12_000
        private const val MIN_SLICE = 400
        private const val LOOK_CHARS = 4_000
        private const val MAX_NOTES = 3_000
        /** Notes past this many characters are asked to be condensed; the file itself takes 200,000. */
        private const val NOTES_LONG = 60_000
        private const val NOTES_FULL = 190_000
        /** The one long message being read across pages, so its next slice does not clean it again. */
        @Volatile private var lastLong: Triple<String, OrbitWindowMessage, String>? = null
        /** A matter can run across pages, but reading on and on without writing down loses it. */
        const val MAX_UNCOMMITTED_PAGES = 3
        private const val FIRST_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        private const val MAX_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        /** After a long break (or a stuck review) the backlog read in one review is bounded. */
        private const val MAX_BACKLOG_MS = 30L * 24 * 60 * 60 * 1000
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun lock(orbitId: String) = locks.computeIfAbsent(orbitId) { Mutex() }

        fun of(context: Context, orbitId: String) = OrbitReviewReader(context.applicationContext, orbitId)

        val prompt = ToolPrompt(
            name = TOOL,
            description = "Read the alignment review's window yourself, page by page, and write down what you learn as you go. " +
                "The app keeps your position: next always continues where you are.",
            parametersStructured = listOf(
                ToolParameterSchema("action", "string", "next (the next page), commit (write down the pages read since the " +
                    "last commit), look (re-read earlier messages without moving your position) or status", true),
                ToolParameterSchema("notes", "string", "commit: what these pages show about working with the user: " +
                    "frictions and corrections with message ids, new rules, what went well, open matters. \"nothing new\" " +
                    "is fine. At most $MAX_NOTES characters", false),
                ToolParameterSchema("ruptures", "string", "commit: the ids of the user's messages on these pages that show " +
                    "friction or a correction, separated by commas; empty when none", false),
                ToolParameterSchema("message_id", "string", "look: the message to start from", false),
                ToolParameterSchema("count", "string", "look: how many messages, 1-20 (default 5)", false),
                ToolParameterSchema("start_char", "string", "look: for a long message, the character to start from", false),
            ),
        )
    }
}

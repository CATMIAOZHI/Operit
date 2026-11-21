package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.api.chat.protocol.ExecutableToolProtocolParser
import com.ai.assistance.operit.data.dao.OrbitWindowMessage
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.findMarkupTagEnd
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
 * it learned as it goes (every turn is read). Nothing is fed into its prompt: the app
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

    private suspend fun state(): JSONObject {
        val state = if (file.isFile) runCatching { JSONObject(file.readText()) }
            .getOrElse { JSONObject() } else JSONObject()
        val space = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbitId }?.memorySpaceId
        val cutoff = space?.let { OrbitLearningBoundary.guard(context, it).cutoffTimestamp } ?: 0L
        if (cutoff > state.optLong("forgotten_before")) {
            state.put("forgotten_before", cutoff).put("reviewed_until", maxOf(cutoff, state.optLong("reviewed_until")))
                .remove("session")
            save(state)
            lastLong = null
        }
        return state
    }

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
        session?.let { upgrade(state, it) }
        if (session == null) {
            val reviewed = state.optLong("reviewed_until", 0).takeIf { it in 1 until now }
            // The first review (or the first after the old excerpt review) looks back a few days.
            val since = (reviewed?.coerceAtLeast(now - MAX_BACKLOG_MS)
                ?: (legacySince?.takeIf { it < now } ?: (now - FIRST_WINDOW_MS)).coerceAtLeast(now - MAX_WINDOW_MS))
                .coerceAtLeast(state.optLong("forgotten_before"))
            val labels = linkedMapOf(orbit.chatId to "main")
            orbit.sideChats.forEach { labels[it.chatId] = "side: ${it.title.take(40)}" }
            val ids = labels.keys.toList()
            // A reply still being written keeps its timestamp but not its text, so a window must end
            // before any turn that is still running; that turn is read whole by the next review.
            val holder = com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
            val running = com.ai.assistance.operit.api.chat.ChatRuntimeSlot.values()
                .flatMap { holder.getCore(it).activeStreamingChatIds.value }.toSet()
            val until = ids.filter { it in running }
                .mapNotNull { dao.latestUserTimestamp(it)?.minus(1) }
                .fold(now) { a, b -> minOf(a, b) }
            if (until <= since) return@locked null
            val users = dao.reviewCount(ids, since, until, listOf("user"))
            if (users == 0) return@locked null
            session = JSONObject().put("since", since).put("until", until)
                .put("text_version", TEXT_VERSION)
                .put("forgotten_before", state.optLong("forgotten_before"))
                .put("chats", JSONObject(labels as Map<*, *>))
                .put("total", dao.reviewCount(ids, since, until, listOf("user", "ai"))).put("user_total", users)
                .put("read", Pos(since, Long.MAX_VALUE).json()).put("committed", Pos(since, Long.MAX_VALUE).json())
                .put("read_n", 0).put("committed_n", 0)
                .put("pages", 0).put("pending_pages", 0).put("pending_users", JSONArray()).put("pending_ids", JSONArray())
                .put("user_read", 0).put("ruptures", JSONArray()).put("notes", 0)
            // The fresh notes file is started through the same saved-then-written step as a commit, so a
            // run stopped in between never takes the last window's notes for this one's.
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            session.put("writing", "# Review notes ${time.format(Date(since))} -> ${time.format(Date(until))}\n\n")
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
            append("Your own tool calls and results show as one line each ([tool call: ...] / [tool result: ...]). " +
                "A result line only says the step came back; whether the command or request worked is in its output, " +
                "so before noting that something failed or worked, action=look message_id=... count=1 shows the message in full " +
                "(a long one in pieces: follow its more_with).\n")
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
        // The page comes again under its own number.
        session.optInt("pending_from", 0).takeIf { it > 0 }?.let { session.put("pages", it - 1) }
        session.put("read", session.getJSONObject("committed")).put("pending_pages", 0)
            .put("pending_users", JSONArray()).put("pending_ids", JSONArray()).remove("pending_from")
        session.remove("page_key")
        if (session.has("committed_n")) session.put("read_n", session.getInt("committed_n"))
    }

    /** A short key only the page's result shows, so a page is committed only once it was really seen. */
    private fun newPageKey() = java.util.UUID.randomUUID().toString().take(6)

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
        upgrade(state, session)
        when (val action = args["action"]?.trim().orEmpty()) {
            "next" -> next(state, session)
            "commit" -> commit(state, session, runId, args["notes"].orEmpty(), args["ruptures"].orEmpty(), args["page_key"].orEmpty())
            "look" -> look(session, args)
            "status" -> progress(session).put("notes_file", OrbitFiles.REVIEW_NOTES)
            else -> error("Unknown action \"${action.take(20)}\". Use next, commit, look or status.")
        }
    }

    private suspend fun next(state: JSONObject, session: JSONObject): JSONObject {
        // A page not written down yet comes again: its result may never have reached the model (tool
        // results given together share one limit), and reading on past it would lose it.
        val again = session.getInt("pending_pages") >= MAX_UNCOMMITTED_PAGES
        if (again) unread(session)
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
            // The page is counted as the tool sends it: each message's id, time and sender count too,
            // so a page of many short messages stays well under the tool result's limit.
            val overhead = entry(m, chats, reactions, time).toString().length + PART_CHARS
            val limit = budget.coerceAtLeast(overhead + MIN_SLICE)
            var end = minOf(text.length, start + (limit - overhead))
            fun build(end: Int) = entry(m, chats, reactions, time).put("text", text.substring(start, end)).apply {
                if (start > 0 || end < text.length) put("part", "chars ${start + 1}-$end of ${text.length}")
            }
            var item = build(end)
            // Escaped characters take more room once sent: cut the slice until it really fits.
            while (end - start > MIN_SLICE) {
                val sent = item.toString().length
                if (sent <= limit) break
                // Scale by how much each character grew when sent, so dense escapes are not cut far too short.
                val textSent = (sent - overhead + PART_CHARS).coerceAtLeast(1)
                val scaled = start + ((end - start).toLong() * (limit - overhead) / textSent).toInt()
                end = maxOf(start + MIN_SLICE, minOf(end - 1, scaled))
                item = build(end)
            }
            messages.put(item)
            if (m.sender == "user" && start == 0) users.put(m.timestamp)
            // Any slice of a user message may show the friction, so each one can be named in ruptures.
            if (m.sender == "user") userIds.put(m.timestamp)
            budget -= item.toString().length
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
            // A page given again whose messages were all deleted since: the step back is kept too.
            if (pos != first || again) {
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
            .put("pending_users", users).put("pending_ids", userIds).put("page_key", newPageKey())
        if (session.has("read_n")) session.put("read_n", session.getInt("read_n") + finished)
        save(state)
        val more = pos.off > 0 || dao.reviewMessages(ids, since, until, pos.ts, pos.id, 1).isNotEmpty()
        return JSONObject().put("page", page).put("page_key", session.getString("page_key")).put("messages", messages).put("more", more)
            .put("position", progress(session).let { "read ${it.getInt("read")} of ${it.getInt("total")} messages" })
            .put("uncommitted_pages", session.getInt("pending_pages"))
            .put("next", "Before reading on or finishing, write down what this page shows with action=commit; a " +
                "matter that goes on past it goes into the notes as open, with its message ids. To check a tool step " +
                "first, action=look message_id=... count=1 (a long message goes on with its more_with).")
            .apply { if (again) put("again", "This is the page you have not written down yet, given again. Commit it before reading on.") }
            .apply { if (first.off > 0) put("continues", "The first message goes on from where the last page stopped.") }
    }

    private suspend fun commit(state: JSONObject, session: JSONObject, runId: String, notes: String, ruptures: String,
        pageKey: String): JSONObject {
        require(session.getInt("pending_pages") > 0) { "Nothing read since the last commit. Call action=next." }
        // A page read by an older build has no key.
        session.optString("page_key").takeIf { it.isNotEmpty() }?.let { key ->
            require(pageKey.trim() == key) {
                "page_key does not match the page you read. Commit only after you have seen the page: pass the " +
                    "page_key that action=next gave with it (call next again to see the page if you did not get it)."
            }
        }
        val text = notes.trim()
        require(text.isNotEmpty()) {
            "notes is empty. Write what this page shows about working with the user, or \"nothing new\"."
        }
        require(text.length <= MAX_NOTES) { "notes has ${text.length} characters; at most $MAX_NOTES" }
        val pendingUsers = session.getJSONArray("pending_users").let { a -> (0 until a.length()).map { a.getLong(it) } }
        val onPages = (session.optJSONArray("pending_ids") ?: session.getJSONArray("pending_users"))
            .let { a -> (0 until a.length()).map { a.getLong(it) }.distinct() }
        // A matter can show its friction only on the next page: the page before's user messages may be named too.
        val before = session.optJSONArray("previous_ids")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty()
        val ids = ruptures.split(Regex("[\\s,，#]+")).filter { it.isNotBlank() }.map {
            it.toLongOrNull() ?: error("ruptures must be message ids (numbers) separated by commas, got \"${it.take(30)}\"")
        }.distinct()
        val stray = ids.filter { it !in onPages && it !in before }
        require(stray.isEmpty()) {
            "ruptures may only name the user's messages on this page or the page before; not among them: " +
                stray.joinToString() + ". User message ids on this page: " + onPages.joinToString() +
                (if (before.isEmpty()) "" else "; on the page before: " + before.joinToString())
        }
        val from = session.optInt("pending_from", session.getInt("pages"))
        val to = session.getInt("pages")
        val block = buildString {
            append(if (from == to) "## Page $from\n" else "## Pages $from-$to\n")
            append(text).append('\n')
            if (ids.isNotEmpty()) append("ruptures: ${ids.joinToString()}\n")
            append('\n')
        }
        val notesFiles = OrbitFiles.of(context, orbitId)
        val notesSize = runCatching { notesFiles.read(OrbitFiles.REVIEW_NOTES).length }
            .onFailure { if (it is CancellationException) throw it }.getOrDefault(0)
        val notesPath = runCatching { notesFiles.fileFor(OrbitFiles.REVIEW_NOTES).absolutePath }
            .getOrDefault(OrbitFiles.REVIEW_NOTES)
        require(notesSize + block.length <= NOTES_FULL) {
            "${OrbitFiles.REVIEW_NOTES} is full ($notesSize characters). Rewrite it shorter with write_file " +
                "(environment=android, path=$notesPath; keep message ids and ruptures), then commit again."
        }
        val all = session.getJSONArray("ruptures")
        val known = (0 until all.length()).map { all.getLong(it) }.toSet()
        ids.filter { it !in known }.forEach { all.put(it) }
        session.put("committed", session.getJSONObject("read")).put("pending_pages", 0)
            .put("user_read", session.getInt("user_read") + pendingUsers.size).put("pending_users", JSONArray())
            .put("pending_ids", JSONArray()).put("previous_ids", JSONArray(onPages)).apply { remove("page_key") }
            .put("notes", session.optInt("notes") + 1).put("last_run", runId).put("writing", block).remove("pending_from")
        if (session.has("read_n")) session.put("committed_n", session.getInt("read_n"))
        save(state)
        flushNotes(state, session)
        val p = progress(session)
        return p.put("saved", true).put("notes_file", OrbitFiles.REVIEW_NOTES)
            .put("written", "Pages ${if (from == to) "$from" else "$from-$to"}: ${text.length} characters of notes" +
                if (ids.isEmpty()) "" else ", ruptures ${ids.joinToString()}")
            .apply { if (notesSize + block.length > NOTES_LONG) put("notes_file_size", notesSize + block.length).put("tidy",
                "Your notes file is getting long. Before reading on, rewrite it shorter with write_file " +
                    "(environment=android, path=$notesPath), keeping message ids, ruptures and open matters.") }
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
        val rows = dao.reviewMessages(ids, session.optLong("forgotten_before"), Long.MAX_VALUE,
            id - 1, Long.MAX_VALUE, count)
        require(rows.isNotEmpty()) { "No message $id in this Orbit's chats" }
        val start = args["start_char"]?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val reactions = chats.keys.associateWith { reactionsOf(it) }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return JSONObject().put("messages", JSONArray(rows.mapIndexed { i, raw ->
            val m = full(raw)
            val text = whole(m)
            val from = if (i == 0) (start - 1).coerceIn(0, text.length) else 0
            val end = minOf(text.length, from + LOOK_CHARS)
            entry(m, chats, reactions, time).put("text", text.slice(from, end)).apply {
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

    /**
     * A review begun before pages shortened tool steps keeps character positions into the old,
     * longer text of a reply it stopped inside; that reply is read again from its start (it was
     * not counted yet, and the notes already written stay). A user's message reads as before.
     */
    private suspend fun upgrade(state: JSONObject, session: JSONObject) {
        if (session.optInt("text_version") >= TEXT_VERSION) return
        for (key in listOf("committed", "read")) {
            val p = Pos.parse(session.getJSONObject(key))
            // Just before that message: the window query takes the next id at the same time.
            if (p.off > 0 && dao.reviewMessage(p.id)?.sender != "user") session.put(key, Pos(p.ts, p.id - 1).json())
        }
        session.put("text_version", TEXT_VERSION)
        lastLong = null
        save(state)
    }

    /** A message as the pages show it: Orbit's replies with each tool step shortened to one line. */
    private fun clean(m: OrbitWindowMessage) = if (m.sender == "ai") condense(m.content) else m.content.trim()

    /**
     * A message as look shows it: in full, only the thinking left out. Tool blocks are kept as
     * they are, so an unclosed `<think>` in a tool's output (a log) cannot hide what follows.
     * Only the spans are kept: look returns a few thousand characters of it at a time.
     */
    private fun whole(m: OrbitWindowMessage): Visible {
        if (m.sender != "ai") return Visible(m.content, intArrayOf(0, m.content.length))
        val content = m.content
        val spans = ArrayList<Int>()
        fun add(start: Int, end: Int) {
            if (start >= end) return
            if (spans.isNotEmpty() && spans[spans.size - 1] == start) spans[spans.size - 1] = end
            else { spans += start; spans += end }
        }
        ChatUtils.visitOutsideThinking(content, object : ChatUtils.OutsideThinkingVisitor {
            override fun text(start: Int, end: Int) = add(start, end)
            override fun toolBlock(start: Int, end: Int) = add(start, end)
        })
        return Visible(content, spans.toIntArray())
    }

    /** The text made of [spans] (start, end pairs) of [content], trimmed, read a slice at a time. */
    private class Visible(private val content: String, spans: IntArray) {
        private val spans = spans.copyOf()
        val length: Int

        init {
            var first = 0
            while (first < this.spans.size) {
                while (this.spans[first] < this.spans[first + 1] && content[this.spans[first]].isWhitespace()) this.spans[first]++
                if (this.spans[first] < this.spans[first + 1]) break
                first += 2
            }
            var last = this.spans.size - 2
            while (last >= first) {
                while (this.spans[last + 1] > this.spans[last] && content[this.spans[last + 1] - 1].isWhitespace()) this.spans[last + 1]--
                if (this.spans[last + 1] > this.spans[last]) break
                last -= 2
            }
            // Spans left empty by the trim count as nothing.
            for (k in 0 until first step 2) this.spans[k + 1] = this.spans[k]
            for (k in last + 2 until this.spans.size step 2) this.spans[k + 1] = this.spans[k]
            var total = 0
            for (k in this.spans.indices step 2) total += this.spans[k + 1] - this.spans[k]
            length = total
        }

        /** Characters [from] until [to] of the visible text. */
        fun slice(from: Int, to: Int): String {
            val out = StringBuilder(maxOf(0, to - from))
            var at = 0
            for (k in spans.indices step 2) {
                val size = spans[k + 1] - spans[k]
                val a = maxOf(from, at)
                val b = minOf(to, at + size)
                if (a < b) out.append(content, spans[k] + a - at, spans[k] + b - at)
                at += size
                if (at >= to) break
            }
            return out.toString()
        }
    }

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
        /**
         * About 8,000 tokens of English chat a page, up to about 36,000 of Chinese; with
         * [MAX_UNCOMMITTED_PAGES] it stays well inside the models Orbit runs on before compaction.
         */
        private const val PAGE_CHARS = 24_000
        private const val MIN_SLICE = 400
        /** Room left for a message's "part" note when it is cut. */
        private const val PART_CHARS = 40
        private const val LOOK_CHARS = 4_000
        private const val MAX_NOTES = 4_000
        /** Notes past this many characters are asked to be condensed; the file itself takes 200,000. */
        private const val NOTES_LONG = 60_000
        private const val NOTES_FULL = 190_000
        /** The one long message being read across pages, so its next slice does not clean it again. */
        @Volatile private var lastLong: Triple<String, OrbitWindowMessage, String>? = null
        /** A matter can run across pages, but reading on and on without writing down loses it. */
        /**
         * Each page is written down before the next: a compaction drops what was not, and tool
         * results given together share one limit, so two pages at once could lose the second.
         */
        const val MAX_UNCOMMITTED_PAGES = 1
        private const val FIRST_WINDOW_MS = 3L * 24 * 60 * 60 * 1000
        private const val MAX_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        /** After a long break (or a stuck review) the backlog read in one review is bounded. */
        private const val MAX_BACKLOG_MS = 30L * 24 * 60 * 60 * 1000
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun lock(orbitId: String) = locks.computeIfAbsent(orbitId) { Mutex() }

        fun of(context: Context, orbitId: String) = OrbitReviewReader(context.applicationContext, orbitId)

        /** Pages before this version showed Orbit's replies with their tool steps in full. */
        private const val TEXT_VERSION = 2
        private const val ERROR_CHARS = 200

        /**
         * Orbit's reply without its thinking, each tool call or tool result in it one line: a
         * patrol's results can run to tens of thousands of characters while what Orbit said is a
         * few hundred, and the review is about what Orbit said and did. A failed step keeps the
         * start of its error. Runs of blank lines become one.
         */
        internal fun condense(content: String): String {
            val out = StringBuilder()
            ChatUtils.visitOutsideThinking(content, object : ChatUtils.OutsideThinkingVisitor {
                override fun text(start: Int, end: Int) {
                    out.append(content, start, end)
                }

                override fun toolBlock(start: Int, end: Int) {
                    out.append('\n').append(step(content, start, end)).append('\n')
                }
            })
            return tidy(out)
        }

        /** The line for the tool block [start] until [end] of [content]; nothing past it is read. */
        private fun step(content: String, start: Int, end: Int): String {
            val tagEnd = findMarkupTagEnd(content, start, end)
            if (tagEnd < 0) return "[tool step]"
            val opening = content.substring(start, tagEnd + 1)
            val name = ExecutableToolProtocolParser.extractAttributeValue(opening, "name")?.trim()?.ifEmpty { null } ?: "?"
            if (!ChatMarkupRegex.isToolResultTagName(ChatMarkupRegex.extractOpeningTagName(opening))) {
                return "[tool call: $name]"
            }
            val status = ExecutableToolProtocolParser.extractAttributeValue(opening, "status")?.trim()?.ifEmpty { null }
                ?: "success"
            // The output sits between the opening and the block's own closing tag, in <content> as the app writes it.
            var from = tagEnd + 1
            var to = content.lastIndexOf("</", end - 1).takeIf { it >= from } ?: end
            fun unwrap(open: String, close: String) {
                while (from < to && content[from].isWhitespace()) from++
                while (to > from && content[to - 1].isWhitespace()) to--
                if (to - from >= open.length + close.length && content.startsWith(open, from, ignoreCase = true) &&
                    content.regionMatches(to - close.length, close, 0, close.length, ignoreCase = true)) {
                    from += open.length
                    to -= close.length
                }
            }
            unwrap("<content>", "</content>")
            if (status.equals("success", ignoreCase = true)) {
                while (from < to && content[from].isWhitespace()) from++
                while (to > from && content[to - 1].isWhitespace()) to--
                return "[tool result: $name, ${to - from} chars of output folded]"
            }
            unwrap("<error>", "</error>")
            while (from < to && content[from].isWhitespace()) from++
            while (to > from && content[to - 1].isWhitespace()) to--
            val shown = StringBuilder()
            var i = from
            var cut = false
            while (i < to) {
                if (content[i].isWhitespace()) {
                    // A run of spaces is one, and only with room for the character after it (the end is trimmed).
                    while (content[i].isWhitespace()) i++
                    if (shown.length + 2 > ERROR_CHARS) { cut = true; break }
                    shown.append(' ')
                    continue
                }
                if (shown.length >= ERROR_CHARS) { cut = true; break }
                shown.append(content[i++])
            }
            if (cut) {
                // The mark counts in the 200, and a character in two halves is not cut in the middle.
                if (shown.length > ERROR_CHARS - 1) shown.setLength(ERROR_CHARS - 1)
                if (shown.isNotEmpty() && shown[shown.length - 1].isHighSurrogate()) shown.setLength(shown.length - 1)
                while (shown.isNotEmpty() && shown[shown.length - 1] == ' ') shown.setLength(shown.length - 1)
                shown.append('…')
            }
            return "[tool result: $name, $status: $shown]"
        }

        /** [text] with line ends as LF, runs of blank lines as one and no trailing spaces, trimmed. */
        private fun tidy(text: CharSequence): String {
            val out = StringBuilder(text.length)
            val pad = StringBuilder()
            var newlines = 0
            for (c in text) {
                when (c) {
                    '\r' -> Unit
                    '\n' -> { newlines++; pad.setLength(0) }
                    ' ', '\t' -> pad.append(c)
                    else -> {
                        if (out.isNotEmpty()) {
                            repeat(minOf(newlines, 2)) { out.append('\n') }
                            out.append(pad)
                        }
                        out.append(c)
                        pad.setLength(0)
                        newlines = 0
                    }
                }
            }
            return out.toString()
        }

        val prompt = ToolPrompt(
            name = TOOL,
            description = "Read the alignment review's window yourself, page by page, and write down what you learn as you go. " +
                "The app keeps your position: next always continues where you are. On pages your own tool calls and " +
                "results are one line each; look shows a message in full.",
            parametersStructured = listOf(
                ToolParameterSchema("action", "string", "next (the next page; call it on its own, not together with " +
                    "other tools; until the page is committed, next gives the same page again), commit (write down " +
                    "the page just read), look (re-read messages in full, without moving your position) or status", true),
                ToolParameterSchema("notes", "string", "commit: what this page shows about working with the user: " +
                    "frictions and corrections with message ids, new rules, what went well, open matters. \"nothing new\" " +
                    "is fine. At most $MAX_NOTES characters", false),
                ToolParameterSchema("ruptures", "string", "commit: the ids of the user's messages on this page (or the " +
                    "page before, when only this page shows it) that show friction or a correction, separated by " +
                    "commas; empty when none", false),
                ToolParameterSchema("page_key", "string", "commit: the page_key that next gave with the page", false),
                ToolParameterSchema("message_id", "string", "look: the message to start from", false),
                ToolParameterSchema("count", "string", "look: how many messages, 1-20 (default 5)", false),
                ToolParameterSchema("start_char", "string", "look: for a long message, the character to start from", false),
            ),
        )
    }
}

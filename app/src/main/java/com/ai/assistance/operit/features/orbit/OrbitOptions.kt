package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reply options. Orbit makes a set with a tool and gets a token; the token written in
 * its reply draws the options as buttons. A tap sends that option as the user's next message, and
 * the set can be used once. The newest [MAX] stay in the index; older sets are archived on disk.
 */
internal object OrbitOptions {
    const val MAX = 400
    const val MAX_OPTIONS = 4
    const val MAX_CHARS = 200
    /** Archived sets read back for old messages stay in memory up to this many, the oldest read dropped first. */
    private const val ARCHIVE_CACHE = 100
    private const val FILE = "orbit_options.json"

    /** The token as Orbit writes it in its reply. */
    val token = Regex("""\[\[orbit-options:([a-z0-9]{10})]]""")

    fun tokenOf(id: String) = "[[orbit-options:$id]]"

    /**
     * [chosen] is the option tapped; [sent] turns true once it really reached the chat. Only a sent
     * choice is saved, so a message lost on the way (app closed while queued) is tappable again.
     */
    data class OptionSet(
        val id: String, val chatId: String, val options: List<String>, val chosen: Int = -1, val at: Long,
        val sent: Boolean = false,
    )

    private val _sets = MutableStateFlow<Map<String, OptionSet>>(emptyMap())
    val sets: StateFlow<Map<String, OptionSet>> = _sets
    /** Ids of sets read back from the archive, oldest read first. They stay in their archive files. */
    private val fromArchive = LinkedHashSet<String>()
    private fun archived(id: String) = synchronized(fromArchive) { id in fromArchive }
    /** Archived sets whose sent choice is still being written: kept in memory until it is on disk. */
    private val pinned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var loaded = false
    private val lock = Any()
    /** Only the file write takes this, so a tap never waits for a save in progress. */
    private val saveLock = Any()
    /** Saves after a tap happen here, one at a time, off the main thread. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun saveSoon(context: Context) {
        val app = context.applicationContext
        io.launch { runCatching { save(app) } }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)
    private fun archive(context: Context, id: String) =
        File(context.applicationContext.filesDir, "orbit_options_archive/$id.json")

    private fun encode(set: OptionSet) = JSONObject().put("id", set.id).put("chat", set.chatId)
        .put("options", JSONArray(set.options)).put("chosen", if (set.sent) set.chosen else -1).put("at", set.at)

    /** Older messages load only their own option set; archives never have to be read as one big file. */
    fun find(context: Context, id: String): OptionSet? {
        load(context)
        _sets.value[id]?.let { return it }
        if (!id.matches(Regex("[a-z0-9]{10}"))) return null
        return synchronized(lock) {
            _sets.value[id] ?: runCatching {
                val saved = archive(context, id)
                if (!saved.isFile) return@runCatching null
                val o = JSONObject(android.util.AtomicFile(saved).readFully().toString(Charsets.UTF_8))
                val options = o.getJSONArray("options").let { a -> (0 until a.length()).map(a::getString) }
                val chosen = o.optInt("chosen", -1)
                OptionSet(id, o.getString("chat"), options, chosen, o.optLong("at"), sent = chosen >= 0)
                    .also { set ->
                        // Scrolling through old chats must not grow memory or the index without end:
                        // the oldest read archived set is let go again (not one tapped and on its way).
                        synchronized(fromArchive) {
                            fromArchive.add(id)
                            // Decided on the newest state, so a tap landing meanwhile is never dropped.
                            _sets.update { current ->
                                val over = (fromArchive.size - ARCHIVE_CACHE).coerceAtLeast(0)
                                val drop = fromArchive.filter {
                                    it != id && it !in pinned && current[it]?.let { s -> s.chosen < 0 || s.sent } != false
                                }.take(over).toSet()
                                current + (id to set) - drop
                            }
                            val now = _sets.value
                            fromArchive.removeAll { it != id && it !in now }
                        }
                    }
            }.onFailure { AppLogger.w("OrbitOptions", "Archived reply options could not be read") }.getOrNull()
        }
    }

    private fun saveArchive(context: Context, set: OptionSet) {
        val target = archive(context, set.id)
        target.parentFile?.mkdirs()
        val atomic = android.util.AtomicFile(target)
        val output = atomic.startWrite()
        try {
            output.write(encode(set).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    /** Reads the saved sets once; call off the main thread. */
    fun load(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val read = runCatching {
                val array = JSONArray(file(context).takeIf { it.isFile }?.readText() ?: "[]")
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    val options = o.getJSONArray("options").let { a -> (0 until a.length()).map(a::getString) }
                    val chosen = o.optInt("chosen", -1)
                    OptionSet(o.getString("id"), o.getString("chat"), options, chosen, o.optLong("at"), sent = chosen >= 0)
                }.associateBy { it.id }
            }.onFailure { AppLogger.w("OrbitOptions", "Reply options could not be read") }.getOrDefault(emptyMap())
            _sets.update { read + it }
            loaded = true
        }
    }

    private fun save(context: Context) = synchronized(saveLock) {
        val array = JSONArray()
        _sets.value.values.sortedBy { it.at }.forEach { set ->
            // A set read back from the archive keeps living in its own archive file.
            if (!archived(set.id)) array.put(encode(set))
        }
        val target = file(context)
        val temp = File(target.parentFile, "$FILE.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** A new set of [options] for the chat [chatId]; returns its id. */
    fun create(context: Context, chatId: String, options: List<String>): String {
        load(context)
        val clean = options.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.distinct()
        require(clean.size in 2..MAX_OPTIONS) { "Give 2 to $MAX_OPTIONS different options" }
        require(clean.all { it.length <= MAX_CHARS }) { "Each option is at most $MAX_CHARS characters" }
        val id = java.util.UUID.randomUUID().toString().replace("-", "").take(10)
        synchronized(lock) {
            synchronized(saveLock) {
                val all = _sets.value.filterKeys { !archived(it) } + (id to OptionSet(id, chatId, clean, at = System.currentTimeMillis()))
                val evicted = all.values.sortedBy { it.at }.dropLast(MAX).filter { it.chosen < 0 || it.sent }
                // Archive before removing anything from the index. A failed write never loses a set.
                evicted.forEach { saveArchive(context, it) }
                val ids = evicted.map { it.id }.toSet()
                _sets.update { current ->
                    // A tap racing the archive stays live until its newer state has been saved too.
                    (current + (id to all.getValue(id))) - ids.filter { current[it] == all[it] }.toSet()
                }
                save(context)
            }
        }
        return id
    }

    /** Marks option [index] of set [id] as tapped; false if the set was already used or is gone. */
    fun choose(context: Context, id: String, index: Int): Boolean {
        while (true) {
            val all = _sets.value
            val set = all[id] ?: return false
            if (set.chosen >= 0 || index !in set.options.indices) return false
            if (_sets.compareAndSet(all, all + (id to set.copy(chosen = index)))) break
        }
        return true
    }

    /** The tapped option reached the chat: keep the set used from now on, across restarts. */
    fun confirm(context: Context, id: String, index: Int) {
        var done: OptionSet? = null
        // An archived set is pinned before it turns sent (only then could it be let go), and stays
        // in memory until written, so reading it back can never bring the old state.
        val fromFile = archived(id)
        if (fromFile) pinned.add(id)
        while (true) {
            val all = _sets.value
            val set = all[id]
            if (set == null || set.chosen != index || set.sent) {
                if (fromFile) pinned.remove(id)
                return
            }
            done = set.copy(sent = true)
            if (_sets.compareAndSet(all, all + (id to done))) break
        }
        val saved = done ?: return
        if (fromFile) {
            // Read back from the archive: only its own file changes.
            val app = context.applicationContext
            io.launch {
                try { runCatching { synchronized(saveLock) { saveArchive(app, saved) } } } finally { pinned.remove(id) }
            }
        } else saveSoon(context)
    }

    /** Undoes [choose] when the message could not be sent after all. */
    fun unchoose(context: Context, id: String, index: Int) {
        while (true) {
            val all = _sets.value
            val set = all[id] ?: return
            if (set.chosen != index || set.sent) return
            if (_sets.compareAndSet(all, all + (id to set.copy(chosen = -1)))) return
        }
    }

    /** A tapped option on its way to the chat, waiting to be seen saved as a user message. */
    private class Waiting(val app: Context, val chatId: String, val id: String, val index: Int, val text: String, val since: Long) {
        /** The queued message carrying it, when it had to wait for a busy chat. */
        var queueId: Long? = null
        /** True while that queued message still sits in the queue, not yet on its way. */
        var stillQueued: (() -> Boolean)? = null
        /** That queued message was handed over to be sent (it may still come back to the queue). */
        var dispatched = false
    }

    private val waiting = mutableListOf<Waiting>()
    private val tags = Regex("""<([A-Za-z_][\w-]*)\b[^>]*>[\s\S]*?</\1>|<[A-Za-z_][\w-]*\b[^>]*/>""")

    /** Starts waiting for option [index] of set [id] to be saved in chat [chatId]. */
    fun expect(context: Context, chatId: String, id: String, index: Int, text: String) = synchronized(waiting) {
        waiting += Waiting(context.applicationContext, chatId, id, index, text.trim(), System.currentTimeMillis() - 1_000)
    }

    fun isWaiting(id: String, index: Int): Boolean = synchronized(waiting) { waiting.any { it.id == id && it.index == index } }

    /** Stops waiting; true when it was still waiting, so the caller may undo the choice. */
    fun drop(id: String, index: Int): Boolean = synchronized(waiting) { waiting.removeAll { it.id == id && it.index == index } }

    /** Ties the option just tapped with [text] in [chatId] to the queued message [queueId]. */
    fun queued(chatId: String, text: String, queueId: Long, stillQueued: () -> Boolean) = synchronized(waiting) {
        waiting.lastOrNull { it.chatId == chatId && it.text == text.trim() && it.queueId == null }?.let {
            it.queueId = queueId
            it.stillQueued = stillQueued
        }
    }

    /** The user deleted or took back the queued message [queueId]: its option can be tapped again. */
    fun queueCancelled(queueId: Long) {
        val gone = synchronized(waiting) {
            waiting.firstOrNull { it.queueId == queueId }?.also { waiting.remove(it) }
        } ?: return
        unchoose(gone.app, gone.id, gone.index)
    }

    /** The queued message [queueId] in [chatId] is being sent right now. */
    fun dispatching(chatId: String, queueId: Long) = synchronized(waiting) {
        waiting.forEach { if (it.chatId == chatId && it.queueId == queueId) it.dispatched = true }
    }

    /** The queued message [queueId] in [chatId] went back into the queue: it is not on its way any more. */
    fun backInQueue(chatId: String, queueId: Long) = synchronized(waiting) {
        waiting.forEach { if (it.chatId == chatId && it.queueId == queueId) it.dispatched = false }
    }

    /**
     * Called for every user message saved to any chat. A message whose text, without the tags the
     * app adds around it, is exactly a waiting option's text confirms the oldest such option. The
     * raw text is tried first, so an option that itself contains tags still matches. An option that
     * had to queue counts only once its queued message was handed over to be sent and has not come
     * back to the queue: before that, the saved message cannot be its own.
     */
    fun onUserMessageSaved(chatId: String, content: String, timestamp: Long) {
        val match = synchronized(waiting) {
            if (waiting.isEmpty()) return
            val raw = content.trim()
            val clean = raw.replace(tags, "").trim()
            val open = waiting.filter {
                it.chatId == chatId && timestamp >= it.since &&
                    (it.queueId == null || (it.dispatched && it.stillQueued?.invoke() != true))
            }
            (open.firstOrNull { it.text == raw }
                ?: open.firstOrNull { clean.isNotEmpty() && it.text.replace(tags, "").trim() == clean })
                ?.also { waiting.remove(it) }
        } ?: return
        confirm(match.app, match.id, match.index)
    }
}

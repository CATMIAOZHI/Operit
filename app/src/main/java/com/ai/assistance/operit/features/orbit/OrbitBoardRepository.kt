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
 * The Feed, Ideas and Tracking boards of one Orbit, the tabs on its page. Each board is one JSON file
 * in the Orbit's own folder (`orbit/<UUID>/feed.json`, `ideas.json`, `tracking.json`), next to its
 * memory and skills, so deleting the Orbit deletes them too.
 */
internal class OrbitBoardRepository(private val directory: File, private val board: Board) {
    constructor(context: Context, orbitId: String, board: Board) :
        this(OrbitPreferences.getInstance(context).orbitDir(orbitId), board)

    enum class Board(val fileName: String, val limit: Int) {
        /** Short posts Orbit writes for the user, steered by the feed brief. */
        FEED("feed.json", 200),
        /**
         * Things Orbit offers to build or do; the user picks one or dismisses it. Ideas keep their
         * own split limit (see [IDEA_PLAIN_LIMIT] and [IDEA_VALUED_LIMIT]); this is the total.
         */
        IDEAS("ideas.json", 180),
        /** Things Orbit carries for the user (a parcel, a booking, a promise) until they are done. */
        TRACKING("tracking.json", 100),
        /**
         * Notes on the user's goals: BRIEF is a briefing the background routine wrote, LOG a dated
         * progress entry. `extra` holds the goal id.
         */
        GOAL_NOTES("goal_notes.json", 300),
    }

    data class Note(val at: Long, val text: String)

    /** One thing an idea would deliver, listed under "What's included". */
    data class Part(val title: String, val detail: String)

    /**
     * Ideas only: how Orbit rated an idea before proposing it, each 1 to 5 (0 on ideas made before
     * ratings): can it really be done, how well it fits this user, how new it is.
     */
    data class Scores(val feasibility: Int, val fit: Int, val novelty: Int) {
        val rated get() = feasibility > 0 && fit > 0 && novelty > 0
    }

    data class Item(
        val id: String,
        val title: String,
        val body: String,
        /** Feed: why this post is for the user. Ideas: what to do when the user picks it. */
        val extra: String,
        val state: String,
        /** The user's answer: 1 liked, -1 not for them, 0 none yet. Orbit reads it to learn their taste. */
        val vote: Int,
        val createdAt: Long,
        val updatedAt: Long,
        /** Ideas only: when the card stops showing. 0 means never. */
        val expiresAt: Long,
        val notes: List<Note>,
        /** Feed and Ideas: the one emoji shown beside the title. Blank uses the board's own. */
        val emoji: String = "",
        /** Ideas only: what the idea would deliver. */
        val parts: List<Part> = emptyList(),
        /** Ideas only: one of [IDEA_CATEGORIES]; blank (ideas made before categories) counts as "other". */
        val category: String = "",
        /** Ideas only: when Orbit marked it built; a later vote or icon change leaves it alone. 0 if not built. */
        val doneAt: Long = 0,
        /** Feed only: a Dynamic UI card is saved beside the post (see [readCard]) and shown under its text. */
        val card: Boolean = false,
        /** Ideas only: the ratings Orbit gave it. */
        val scores: Scores = Scores(0, 0, 0),
        /** Ideas only: why this idea, for this user, now. */
        val rationale: String = "",
        /** Ideas only: where the reason comes from (a message id, a memory file, a goal, a link Orbit opened). */
        val sources: List<String> = emptyList(),
        /** Feed only: the post's pictures, file names in its own folder (see [imageFiles]), at most [MAX_IMAGES]. */
        val images: List<String> = emptyList(),
    ) {
        val liked get() = vote > 0
        val disliked get() = vote < 0
        /** Liked, disliked or built: the user's own answer, kept longer than the rest. */
        val valued get() = vote != 0 || state == "BUILT"
        /** When it was built; ideas built before this was saved fall back to their last change. */
        val builtAt get() = doneAt.takeIf { it > 0 } ?: updatedAt
    }

    companion object {
        const val MAX_TITLE = 80
        const val MAX_BODY = 6000
        const val MAX_EXTRA = 2000
        const val MAX_NOTE = 1000
        const val MAX_BRIEF = 2000
        /** A feed post's Dynamic UI card, kept in its own file so the feed list stays small. */
        const val MAX_CARD = 65_536
        /** A feed post shows at most nine pictures, like a moments post. */
        const val MAX_IMAGES = 9
        const val MAX_PARTS = 6
        const val MAX_PART_TITLE = 60
        const val MAX_PART_DETAIL = 200
        const val MAX_RATIONALE = 1000
        const val MAX_SOURCES = 6
        const val MAX_SOURCE = 300
        /** Idea cards retire after about two weeks. */
        const val IDEA_LIFETIME_MS = 14L * 24 * 60 * 60 * 1000
        /** Ideas the user has not answered: the newest are kept, older ones make room. */
        const val IDEA_PLAIN_LIMIT = 60
        /** Ideas the user liked, disliked or had built: worth more, so kept for much longer. */
        const val IDEA_VALUED_LIMIT = 120

        /** The card names a feed post body refers to with a2ui-ref fences, in order. */
        fun cardFences(body: String): List<String> = scanCardFences(body).first

        /**
         * A feed post body without any a2ui-ref fence. A post shows its card under the text, so a
         * fence in the text itself would only show up as raw characters.
         */
        fun withoutCardFences(body: String): String {
            if (!body.contains("a2ui-ref")) return body
            val (names, text) = scanCardFences(body)
            return if (names.isEmpty()) body else text
        }

        /**
         * Reads the body line by line: a card reference is exactly the three lines ```a2ui-ref,
         * its name, and ```. Anything inside an ordinary code block (including a quoted example of
         * a reference) is left alone. Returns the names and the text without those references.
         */
        private fun scanCardFences(body: String): Pair<List<String>, String> {
            val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            val names = mutableListOf<String>()
            val kept = ArrayList<String>(lines.size)
            var openFence: String? = null
            var index = 0
            while (index < lines.size) {
                val line = lines[index]
                val trimmed = line.trim()
                if (openFence != null) {
                    kept += line
                    if (trimmed.startsWith(openFence) && trimmed.trimStart(openFence[0]).isEmpty()) openFence = null
                    index++
                    continue
                }
                val mark = trimmed.firstOrNull()?.takeIf { it == '`' || it == '~' }
                val ticks = if (mark == null) "" else trimmed.takeWhile { it == mark }
                if (ticks.length >= 3) {
                    val info = trimmed.substring(ticks.length).trim()
                    val name = lines.getOrNull(index + 1)?.trim().orEmpty()
                    val close = lines.getOrNull(index + 2)?.trim()
                    if (ticks == "```" && info == "a2ui-ref" && close == "```" &&
                        name.isNotEmpty() && '`' !in name) {
                        names += name
                        index += 3
                        continue
                    }
                    openFence = ticks
                }
                kept += line
                index++
            }
            // Removing a reference leaves the blank lines around it; keep at most one blank line.
            val text = buildList<String> {
                kept.forEach { line ->
                    if (line.isBlank() && lastOrNull()?.isBlank() == true) return@forEach
                    add(line)
                }
            }.joinToString("\n").trim()
            return names to text
        }
        /** The Ideas tab's groups, in the order they are shown. */
        val IDEA_CATEGORIES = listOf("productivity", "money", "relationships", "health", "shopping", "other")

        /** The category an idea is filed under; an old idea without one goes to "other". */
        fun categoryOf(item: Item) = item.category.takeIf { it in IDEA_CATEGORIES } ?: "other"

        /** The first emoji in [value] (one grapheme, flags and joined emoji included), or "" if there is none. */
        fun emojiOf(value: String): String {
            val text = value.trim()
            if (text.isEmpty()) return ""
            val breaker = java.text.BreakIterator.getCharacterInstance()
            breaker.setText(text)
            val first = text.substring(0, breaker.next().coerceAtLeast(1))
            // Letters, digits and plain symbols are not an emoji.
            val cp = first.codePointAt(0)
            return if (first.length > 16 || cp < 0x2100 || Character.isLetterOrDigit(cp)) "" else first
        }

        private val locks = ConcurrentHashMap<String, Mutex>()
        private val flows = ConcurrentHashMap<String, MutableStateFlow<List<Item>?>>()

        fun briefFile(directory: File) = File(directory, "FEED_BRIEF.md")

        private const val CARD_FOLDER = "feed_cards"
        private fun cardFile(directory: File, id: String) = File(File(directory, CARD_FOLDER), "${File(id).name}.a2ui")
        private const val IMAGE_FOLDER = "feed_images"
        private val IMAGE_NAME = Regex("\\d{1,2}\\.(jpg|png)")
        private fun imageFolder(directory: File, id: String) = File(File(directory, IMAGE_FOLDER), File(id).name)

        /** A feed post's pictures in order; names that are not ours are left out. */
        fun imageFiles(context: Context, orbitId: String, item: Item): List<File> {
            val folder = imageFolder(OrbitPreferences.getInstance(context).orbitDir(orbitId), item.id)
            return item.images.filter { IMAGE_NAME.matches(it) }.map { File(folder, it) }
        }

        suspend fun readBrief(context: Context, orbitId: String): String = withContext(Dispatchers.IO) {
            briefFile(OrbitPreferences.getInstance(context).orbitDir(orbitId)).takeIf { it.isFile }?.readText().orEmpty()
        }

        suspend fun writeBrief(context: Context, orbitId: String, brief: String) = withContext(Dispatchers.IO) {
            require(brief.length <= MAX_BRIEF) { "The feed brief is at most $MAX_BRIEF characters" }
            val directory = OrbitPreferences.getInstance(context).orbitDir(orbitId)
            atomic(directory, briefFile(directory), brief.trim())
        }

        private fun atomic(directory: File, file: File, content: String) {
            atomicBytes(directory, file, content.toByteArray(Charsets.UTF_8))
        }

        private fun atomicBytes(directory: File, file: File, content: ByteArray) {
            check(directory.mkdirs() || directory.isDirectory)
            val temp = File.createTempFile(".board-", ".tmp", directory)
            try {
                temp.outputStream().use { it.write(content); it.fd.sync() }
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

    /** Everything saved, expired ideas included: the user's old answers still teach Orbit their taste. */
    suspend fun listAll(): List<Item> = withContext(Dispatchers.IO) { mutex.withLock { readAll() } }

    suspend fun get(id: String): Item? = list().firstOrNull { it.id == id }

    /** The item a call with [requestId] already made, so a replayed call can skip its slow work. */
    suspend fun madeBy(requestId: String): Item? {
        val id = UUID.nameUUIDFromBytes("${board.name}:$requestId".toByteArray()).toString()
        return listAll().firstOrNull { it.id == id }
    }

    /** A feed post's Dynamic UI card, or null when it has none (or the file is gone). */
    suspend fun readCard(id: String): String? = withContext(Dispatchers.IO) {
        cardFile(directory, id).takeIf { it.isFile && it.length() <= MAX_CARD * 4L }?.readText()?.takeIf { it.isNotBlank() }
    }

    suspend fun create(
        title: String,
        body: String,
        extra: String = "",
        requestId: String = UUID.randomUUID().toString(),
        expiresAt: Long = 0,
        state: String? = null,
        emoji: String = "",
        parts: List<Part> = emptyList(),
        category: String = "",
        /** Feed only: a checked Dynamic UI card document shown under the post. */
        card: String = "",
        scores: Scores = Scores(0, 0, 0),
        rationale: String = "",
        sources: List<String> = emptyList(),
        /** Feed only: encoded pictures (bytes and "jpg" or "png"), already shrunk for the feed. */
        images: List<Pair<ByteArray, String>> = emptyList(),
    ): Item = change { items ->
        val id = UUID.nameUUIDFromBytes("${board.name}:$requestId".toByteArray()).toString()
        items.firstOrNull { it.id == id }?.let { return@change items to it }
        state?.let { require(it in states()) { "State must be one of ${states()}" } }
        val checkedCategory = checkCategory(category)
        require(card.isEmpty() || board == Board.FEED) { "Only feed posts take a card" }
        require(card.length <= MAX_CARD) { "The card is larger than $MAX_CARD characters" }
        require(images.isEmpty() || board == Board.FEED) { "Only feed posts take pictures" }
        require(images.size <= MAX_IMAGES) { "A post has at most $MAX_IMAGES pictures" }
        require(images.all { it.second == "jpg" || it.second == "png" }) { "Pictures are saved as jpg or png" }
        require(board == Board.IDEAS || (rationale.isBlank() && sources.isEmpty() && !scores.rated)) {
            "Only ideas take ratings, a rationale and sources"
        }
        listOf(scores.feasibility, scores.fit, scores.novelty).forEach {
            require(it in 0..5) { "Ratings are whole numbers from 1 to 5" }
        }
        val checkedRationale = rationale.trim().also {
            require(it.length <= MAX_RATIONALE) { "The rationale is at most $MAX_RATIONALE characters" }
        }
        val checkedSources = sources.map { it.trim() }.filter { it.isNotEmpty() }.also { list ->
            require(list.size <= MAX_SOURCES) { "At most $MAX_SOURCES sources" }
            require(list.all { it.length <= MAX_SOURCE }) { "A source is at most $MAX_SOURCE characters" }
        }
        val now = System.currentTimeMillis()
        val imageNames = images.mapIndexed { index, picture -> "${index + 1}.${picture.second}" }
        val item = Item(id, checkTitle(title), checkBody(body), checkExtra(extra), state ?: initialState(), 0, now, now,
            if (board == Board.IDEAS) (expiresAt.takeIf { it > now } ?: (now + IDEA_LIFETIME_MS)) else 0L, emptyList(),
            emojiOf(emoji), checkParts(parts), checkedCategory, card = card.isNotBlank(),
            scores = scores, rationale = checkedRationale, sources = checkedSources, images = imageNames)
        // The card is written before the post is saved, so a saved post never points at a missing card.
        if (item.card) atomic(File(directory, CARD_FOLDER), cardFile(directory, id), card)
        // Pictures too: written into the post's own folder first, so the post never names a missing one.
        if (images.isNotEmpty()) {
            val folder = imageFolder(directory, id)
            folder.deleteRecursively()
            images.forEachIndexed { index, picture -> atomicBytes(folder, File(folder, imageNames[index]), picture.first) }
        }
        val all = listOf(item) + items
        val kept = if (board == Board.IDEAS) keepIdeas(all, now, item.id) else if (all.size <= board.limit) all else {
            // Keep at most `limit` items; finished ones go before open ones, older before newer.
            // Briefings and progress entries are equals: the oldest go first.
            val drop = all.drop(1).sortedWith(compareBy<Item> { board != Board.GOAL_NOTES && it.state == initialState() }
                .thenBy { it.updatedAt })
                .take(all.size - board.limit).map { it.id }.toSet()
            all.filterNot { it.id in drop }
        }
        kept to item
    }

    /**
     * Ideas the user answered (liked, disliked, built) are worth more than the rest, so they have a
     * limit of their own: the newest [IDEA_VALUED_LIMIT] of them stay. Of the unanswered ones the
     * newest [IDEA_PLAIN_LIMIT] stay, cards still showing before dismissed or expired ones. The card
     * just made or changed ([keepId]) always stays.
     */
    private fun keepIdeas(all: List<Item>, now: Long, keepId: String): List<Item> {
        val kept = all.firstOrNull { it.id == keepId }
        val (valued, plain) = all.filter { it.id != keepId }.partition { it.valued }
        val keep = HashSet<String>().apply { add(keepId) }
        valued.sortedByDescending { it.updatedAt }
            .take(IDEA_VALUED_LIMIT - if (kept?.valued == true) 1 else 0).mapTo(keep) { it.id }
        plain.sortedWith(compareByDescending<Item> { it.state == "OPEN" && (it.expiresAt == 0L || it.expiresAt > now) }
            .thenByDescending { it.updatedAt })
            .take(IDEA_PLAIN_LIMIT - if (kept != null && !kept.valued) 1 else 0).mapTo(keep) { it.id }
        return all.filter { it.id in keep }
    }

    suspend fun update(
        id: String,
        title: String? = null,
        body: String? = null,
        extra: String? = null,
        state: String? = null,
        vote: Int? = null,
        note: String? = null,
        emoji: String? = null,
        category: String? = null,
    ): Item = change { items ->
        val old = items.firstOrNull { it.id == id } ?: error("Not found: $id")
        state?.let { require(it in states()) { "State must be one of ${states()}" } }
        vote?.let { require(it in -1..1) { "Vote must be -1, 0 or 1" } }
        val newEmoji = emoji?.let { value -> emojiOf(value).also { require(it.isNotEmpty()) { "emoji must be one emoji, like 📦" } } }
        val newCategory = category?.let(::checkCategory)
        val now = System.currentTimeMillis()
        val newNote = note?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= MAX_NOTE) { "A note is at most $MAX_NOTE characters" }
        }
        val updated = old.copy(
            title = title?.let(::checkTitle) ?: old.title,
            body = body?.let(::checkBody) ?: old.body,
            extra = extra?.let(::checkExtra) ?: old.extra,
            state = state ?: old.state,
            vote = vote ?: old.vote,
            notes = if (newNote == null) old.notes else (old.notes + Note(now, newNote)).takeLast(50),
            emoji = newEmoji ?: old.emoji,
            category = newCategory ?: old.category,
            // Ideas done before doneAt existed keep their old date as the done date.
            doneAt = when {
                state == "BUILT" && old.state != "BUILT" -> now
                old.state == "BUILT" -> old.builtAt
                else -> old.doneAt
            },
            updatedAt = now,
        )
        val all = items.map { if (it.id == id) updated else it }
        // A vote or a build moves the card between the two limits, so both are checked again.
        (if (board == Board.IDEAS && (vote != null || state != null)) keepIdeas(all, now, id) else all) to updated
    }

    /** Removes [id]; false when there was no such item. */
    suspend fun delete(id: String): Boolean =
        change { items -> items.filterNot { it.id == id } to items.any { it.id == id } }

    /**
     * Moves [id] one place up or down in the user's own ordering. Gives its place before and after,
     * counted from 1; the same place when it was already at that end.
     */
    suspend fun move(id: String, up: Boolean): Pair<Int, Int> =
        change { items ->
            val index = items.indexOfFirst { it.id == id }
            require(index >= 0) { "Not found: $id" }
            val target = if (up) index - 1 else index + 1
            if (target !in items.indices) return@change items to (index + 1 to index + 1)
            items.toMutableList().apply { add(target, removeAt(index)) } to (index + 1 to target + 1)
        }

    private fun initialState() = when (board) {
        Board.FEED -> "LIVE"
        Board.IDEAS -> "OPEN"
        Board.TRACKING -> "ACTIVE"
        Board.GOAL_NOTES -> "LOG"
    }

    fun states(): Set<String> = when (board) {
        Board.FEED -> setOf("LIVE")
        Board.IDEAS -> setOf("OPEN", "DISMISSED", "BUILT")
        Board.TRACKING -> setOf("ACTIVE", "DONE")
        Board.GOAL_NOTES -> setOf("LOG", "BRIEF")
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

    private fun checkCategory(value: String): String {
        val text = value.trim().lowercase()
        if (text.isEmpty() || board != Board.IDEAS) return ""
        require(text in IDEA_CATEGORIES) { "category must be one of ${IDEA_CATEGORIES.joinToString("/")}" }
        return text
    }

    private fun checkParts(value: List<Part>): List<Part> {
        require(value.size <= MAX_PARTS) { "At most $MAX_PARTS included items" }
        return value.map { part ->
            val title = part.title.replace(Regex("\\s+"), " ").trim()
            val detail = part.detail.trim()
            require(title.isNotEmpty() && title.length <= MAX_PART_TITLE) { "An included item's title is 1 to $MAX_PART_TITLE characters" }
            require(detail.length <= MAX_PART_DETAIL) { "An included item's detail is at most $MAX_PART_DETAIL characters" }
            Part(title, detail)
        }
    }

    private suspend fun <T> change(block: (List<Item>) -> Pair<List<Item>, T>): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = readAll()
            val (items, result) = block(before)
            atomic(directory, file, JSONArray(items.map(::json)).toString())
            // A post that left the feed (deleted or past the limit) takes its card with it.
            if (board == Board.FEED) {
                val left = items.mapTo(HashSet()) { it.id }
                before.filter { it.card && it.id !in left }.forEach { cardFile(directory, it.id).delete() }
                before.filter { it.images.isNotEmpty() && it.id !in left }.forEach { imageFolder(directory, it.id).deleteRecursively() }
            }
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
        // A liked idea stays until it is built or dismissed; the rest retire after about two weeks.
        return items.filter {
            board != Board.IDEAS || it.state != "OPEN" || it.liked || it.expiresAt == 0L || it.expiresAt > now
        }
    }

    private fun readAll(): List<Item> {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { item(array.getJSONObject(it)) }
    }

    private fun json(item: Item) = JSONObject().put("id", item.id).put("title", item.title).put("body", item.body)
        .put("extra", item.extra).put("state", item.state).put("vote", item.vote).put("createdAt", item.createdAt)
        .put("updatedAt", item.updatedAt).put("expiresAt", item.expiresAt)
        .put("notes", JSONArray(item.notes.map { JSONObject().put("at", it.at).put("text", it.text) }))
        .put("emoji", item.emoji)
        .put("parts", JSONArray(item.parts.map { JSONObject().put("title", it.title).put("detail", it.detail) }))
        .put("category", item.category)
        .put("doneAt", item.doneAt)
        .put("card", item.card)
        .apply {
            if (item.scores.rated) put("scores", JSONObject().put("feasibility", item.scores.feasibility)
                .put("fit", item.scores.fit).put("novelty", item.scores.novelty))
            if (item.rationale.isNotEmpty()) put("rationale", item.rationale)
            if (item.sources.isNotEmpty()) put("sources", JSONArray(item.sources))
            if (item.images.isNotEmpty()) put("images", JSONArray(item.images))
        }

    private fun item(value: JSONObject): Item {
        val notes = value.optJSONArray("notes") ?: JSONArray()
        val parts = value.optJSONArray("parts") ?: JSONArray()
        val scores = value.optJSONObject("scores")
        val sources = value.optJSONArray("sources") ?: JSONArray()
        val images = value.optJSONArray("images") ?: JSONArray()
        return Item(
            value.getString("id"), value.getString("title"), value.optString("body"), value.optString("extra"),
            value.optString("state", initialState()),
            // Boards saved before votes only knew "liked".
            if (value.has("vote")) value.optInt("vote").coerceIn(-1, 1) else if (value.optBoolean("liked")) 1 else 0,
            value.getLong("createdAt"),
            value.optLong("updatedAt", value.getLong("createdAt")), value.optLong("expiresAt"),
            (0 until notes.length()).map { notes.getJSONObject(it).let { n -> Note(n.getLong("at"), n.getString("text")) } },
            value.optString("emoji"),
            (0 until parts.length()).map { parts.getJSONObject(it).let { p -> Part(p.optString("title"), p.optString("detail")) } },
            value.optString("category"),
            value.optLong("doneAt"),
            value.optBoolean("card"),
            Scores(scores?.optInt("feasibility") ?: 0, scores?.optInt("fit") ?: 0, scores?.optInt("novelty") ?: 0),
            value.optString("rationale"),
            (0 until sources.length()).map { sources.optString(it) }.filter { it.isNotBlank() },
            (0 until images.length()).map { images.optString(it) }.filter { IMAGE_NAME.matches(it) },
        )
    }
}

/**
 * The feed's brief and the "write one now" button. Posts themselves come from Orbit's feed routine
 * (see [OrbitRoutines.ensureFeed]), a background subagent that runs every 12 hours and reads the
 * brief each time, so changing the brief never needs the task itself to change.
 */
internal object OrbitFeedGenerator {
    /** What the feed follows while the user has not written a brief of their own. */
    fun defaultBrief(context: Context): String =
        context.getString(com.ai.assistance.operit.R.string.orbit_feed_default_brief)

    /** The brief the routine follows: the user's own, or the default one. */
    suspend fun effectiveBrief(context: Context, orbitId: String): String =
        OrbitBoardRepository.readBrief(context, orbitId).trim().ifEmpty { defaultBrief(context) }

    /** Starts one post now. False when the feed routine is paused (or cannot be made). */
    suspend fun writeOne(context: Context, orbitId: String): Boolean = OrbitRoutines.runNow(context, orbitId, feed = true)

    /**
     * Saves the brief; an empty one goes back to the default. A real change starts a post now, so
     * the user sees the new direction without waiting 12 hours.
     *
     * Returns null when nothing changed, true when a post started, false when the feed routine is
     * paused so no post could start.
     */
    suspend fun saveBrief(context: Context, orbitId: String, brief: String): Boolean? {
        val old = effectiveBrief(context, orbitId)
        val next = brief.trim().takeUnless { it == defaultBrief(context) }.orEmpty()
        OrbitBoardRepository.writeBrief(context, orbitId, next)
        return if (effectiveBrief(context, orbitId) != old) writeOne(context, orbitId) else null
    }

    /** The title of the daily post task made before the feed routine; those are retired. */
    const val DAILY_TITLE = "Daily feed post"
}

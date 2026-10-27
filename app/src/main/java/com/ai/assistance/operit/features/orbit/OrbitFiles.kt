package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Orbit's own files besides SOUL.md, IDENTITY.md, user.md and memory.md, all inside its folder:
 * notes on people and groups (with an INDEX.md the app keeps), the daily log, the lessons book,
 * a few small settings files, and files/ for things made for the user. Deleting moves a file to
 * a trash that keeps it for [TRASH_DAYS] days.
 */
class OrbitFiles(private val root: File, englishProvider: () -> Boolean = { false }) {
    data class Entry(val path: String, val size: Long, val modifiedAt: Long, val title: String)
    data class TrashItem(val id: String, val path: String, val deletedAt: Long, val size: Long)
    /** The Library's kinds of file. */
    enum class Kind { IMAGE, AUDIO, DOCUMENT, OTHER }

    private val mutex = locks.computeIfAbsent(root.absolutePath) { Mutex() }
    // Read on first use, which is always inside io(), so the UI never blocks on the setting.
    private val english by lazy(englishProvider)

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    fun fileFor(path: String): File {
        val p = normalize(path)
        require(allowed(p)) { notOrbitFile(path, p) }
        return inside(File(root, p))
    }

    /**
     * Refuses a file that a link would take outside Orbit's folder: checks the file if it exists,
     * otherwise the nearest folder above it that does, so a linked folder is caught too.
     */
    private fun inside(f: File): File {
        root.mkdirs()
        var probe: File? = f
        // A dangling link counts as existing, so toRealPath below fails on it instead of skipping it.
        while (probe != null && !Files.exists(probe.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) probe = probe.parentFile
        // toRealPath resolves links on every platform (canonicalFile does not on all of them).
        val base = root.toPath().toRealPath()
        val real = probe?.let { runCatching { it.toPath().toRealPath() }.getOrNull() }
        require(real != null && real.startsWith(base)) { "Not one of Orbit's files" }
        return f
    }

    /** Deletes a folder without following links inside it. */
    private fun deleteTree(dir: File) {
        val start = dir.toPath()
        if (!Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(start)) { Files.delete(start); return }
        Files.walkFileTree(start, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.delete(file) }
            override fun postVisitDirectory(d: java.nio.file.Path, e: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.delete(d) }
        })
    }

    private fun trashDir(id: String): File = inside(File(root, "$TRASH/$id"))

    /** The trash folder, or null when it is a link out of Orbit's folder. */
    private fun safeTrashRoot(): File? = File(root, TRASH).takeIf { runCatching { inside(it) }.isSuccess }

    /**
     * Every file, including the fixed files that are still empty templates (size -1). With
     * [includeReview], also the nightly reviews (dreams/) and the alignment state the review keeps.
     */
    suspend fun list(includeReview: Boolean = false): List<Entry> = io {
        val found = mutableListOf<Entry>()
        SINGLE.forEach { p ->
            val f = File(root, p)
            found += if (f.isFile) entry(p, f) else Entry(p, -1, 0, p)
        }
        if (includeReview) {
            ALIGNMENT_DATA.forEach { p ->
                val f = File(root, p)
                found += if (f.isFile) entry(p, f) else Entry(p, -1, 0, p)
            }
            File(root, "dreams").listFiles()?.filter { it.isFile && DREAM.matches("dreams/${it.name}") }
                ?.forEach { found += entry("dreams/${it.name}", it) }
        }
        found += peopleLocked()
        File(root, "memory").listFiles()?.filter { it.isFile && DAILY.matches("memory/${it.name}") }
            ?.forEach { found += entry("memory/${it.name}", it) }
        val filesRoot = File(root, "files")
        if (filesRoot.isDirectory) filesRoot.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { f ->
            val p = "files/" + f.relativeTo(filesRoot).invariantSeparatorsPath
            if (FILES.matches(p) && p.split('/').none { it.startsWith(".") }) found += entry(p, f)
        }
        // Leave out anything a link takes outside Orbit's folder; it could not be opened anyway.
        found.filter { it.size < 0 || runCatching { inside(File(root, it.path)) }.isSuccess }
    }

    /** Only the person and circle notes (and their indexes), without walking the rest of the folder. */
    suspend fun listPeople(): List<Entry> = io {
        peopleLocked().filter { runCatching { inside(File(root, it.path)) }.isSuccess }
    }

    private fun peopleLocked(): List<Entry> = listOf("memory/people", "memory/groups").flatMap { dir ->
        File(root, dir).listFiles()?.filter { it.isFile && it.name.endsWith(".md") }
            ?.map { entry("$dir/${it.name}", it) }.orEmpty()
    }

    suspend fun read(path: String): String = io {
        val p = normalize(path)
        // user.md and memory.md are kept by the memory tools; reading them here is harmless and saves a dead end.
        CORE_READABLE[p.lowercase()]?.let { real ->
            val f = inside(File(root, real))
            return@io if (f.isFile) f.readText() else ""
        }
        require(allowed(p) || DOCS.matches(p)) { notOrbitFile(path, p) }
        val f = inside(File(root, p))
        if (!f.isFile) return@io template(p) ?: error("No such file: $p")
        require(isText(p)) { "Not a text file: $p" }
        require(f.length() <= MAX_TEXT * 4L) { "File is too large to read" }
        f.readText()
    }

    /** The text of one of Orbit's text files as stored, or null when it does not exist yet. */
    suspend fun readStored(path: String): String? = io {
        val p = normalize(path)
        require(allowed(p) && isText(p)) { "Not one of Orbit's text files: $path" }
        val f = File(root, p)
        if (!f.isFile) return@io null
        require(inside(f).length() <= MAX_TEXT * 4L) { "File is too large to read" }
        f.readText()
    }

    /** Library pins: files/ paths the user keeps at the top of the Library, newest pin first. */
    suspend fun pins(): List<String> = io { readPins() }

    suspend fun setPinned(path: String, pinned: Boolean) = io {
        val p = normalize(path)
        require(FILES.matches(p)) { "Only files Orbit made can be pinned" }
        val next = readPins().filterNot { it == p }.let { if (pinned) listOf(p) + it else it }.take(100)
        atomic(File(root, PINS), org.json.JSONArray(next).toString())
    }

    private fun readPins(): List<String> {
        val f = File(root, PINS)
        if (!f.isFile || runCatching { inside(f) }.isFailure) return emptyList()
        return runCatching {
            val array = org.json.JSONArray(f.readText())
            (0 until array.length()).map { array.getString(it) }.filter { FILES.matches(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * One change to a file: its path, its text before (null when it was new, or too big to compare)
     * and after, and whether the file was there before.
     */
    class Written(
        val path: String,
        val before: String?,
        val after: String,
        val appendedLines: Int = 0,
        val existed: Boolean = before != null,
    )

    suspend fun write(path: String, text: String): Written = io {
        val p = writable(path)
        require(text.length <= MAX_TEXT) { "Text is longer than $MAX_TEXT characters" }
        // Every chat carries the synthesis in full, so it must fit what the prompt takes.
        require(p != ALIGNMENT || text.length <= MAX_ALIGNMENT_PROMPT) {
            "$ALIGNMENT is longer than $MAX_ALIGNMENT_PROMPT characters; shorten it"
        }
        val f = File(root, p)
        val existed = f.isFile
        // Only a text the size Orbit can write is read back to compare.
        val before = if (existed && inside(f).length() <= MAX_TEXT * 4L) f.readText() else null
        atomic(f, text)
        afterChange(p)
        Written(p, before, text, existed = existed)
    }

    /** The newest nightly review, dreams/YYYY-MM-DD.md, or null before the first one. */
    suspend fun latestDream(): String? = io {
        File(root, "dreams").listFiles()?.map { "dreams/${it.name}" }
            ?.filter { DREAM.matches(it) && runCatching { inside(File(root, it)) }.isSuccess }?.maxOrNull()
    }

    suspend fun append(path: String, text: String): Written = io {
        val p = writable(path)
        appendLocked(p, text)
    }

    /**
     * Applies a reviewed rewrite only if the file still holds [before] (a missing file counts as its
     * template or empty), so a background review never overwrites an edit made after it read the file.
     * Already holding [after] counts as done, which lets an interrupted apply be retried.
     */
    suspend fun writeIfUnchanged(path: String, before: String, after: String): Written = io {
        val p = writable(path)
        require(after.length <= MAX_TEXT) { "Text is longer than $MAX_TEXT characters" }
        require(p != ALIGNMENT || after.length <= MAX_ALIGNMENT_PROMPT) {
            "$ALIGNMENT is longer than $MAX_ALIGNMENT_PROMPT characters; shorten it"
        }
        val f = File(root, p)
        val existed = f.isFile
        val current = if (existed) inside(f).readText() else ""
        // An apply interrupted after the write but before the index update finishes that here.
        if (current == after) return@io Written(p, current, after, existed = existed).also { afterChange(p) }
        if (current != before && !(!existed && before == template(p).orEmpty())) throw ChangedMeanwhile(p)
        atomic(f, after)
        afterChange(p)
        Written(p, if (existed) current else null, after, existed = existed)
    }

    /** The file is no longer what the writer read; nothing was written. */
    class ChangedMeanwhile(path: String) : IllegalStateException("$path changed after it was read; read it again")

    /** Appends [text] unless the file already holds it as whole lines, so a retried apply adds it once. */
    suspend fun appendOnce(path: String, text: String): Written = io {
        val p = writable(path)
        val f = File(root, p)
        val current = if (f.isFile) inside(f).readText() else template(p).orEmpty()
        val lines = text.trim().lines().map { it.trim() }
        val held = current.lines().map { it.trim() }
        if (lines.isNotEmpty() && java.util.Collections.indexOfSubList(held, lines) >= 0)
            Written(p, current, current, existed = f.isFile)
        else appendLocked(p, text)
    }

    /** Adds one line to today's (or [time]'s) daily log, memory/YYYY-MM-DD.md. */
    suspend fun appendDaily(line: String, time: Long = System.currentTimeMillis()): Written = io {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(Date(time))
        val p = "memory/$day.md"
        val f = File(root, p)
        val existed = f.isFile
        if (!existed) atomic(f, "# $day\n")
        val w = appendLocked(p, "- $clock ${line.replace(Regex("\\s+"), " ").trim().take(MAX_DAILY_LINE)}")
        if (existed) w else Written(p, null, w.after, w.appendedLines, existed = false)
    }

    private fun appendLocked(p: String, text: String): Written {
        val f = File(root, p)
        val existed = f.isFile
        val old = if (existed) inside(f).readText() else template(p).orEmpty()
        val next = old.trimEnd() + "\n" + text.trim() + "\n"
        require(next.length <= MAX_TEXT) { "The file is full; tidy it up with write first" }
        require(p != ALIGNMENT || next.length <= MAX_ALIGNMENT_PROMPT) {
            "$ALIGNMENT would be longer than $MAX_ALIGNMENT_PROMPT characters; rewrite it shorter with write"
        }
        val after = next.trimStart('\n')
        atomic(f, after)
        afterChange(p)
        val added = text.trim().let { if (it.isEmpty()) 0 else it.lines().size }
        return Written(p, if (existed) old else null, after, added)
    }

    /** Moves a file to the trash. It can be restored for [TRASH_DAYS] days. */
    suspend fun delete(path: String): TrashItem = io {
        val p = normalize(path)
        require(allowed(p) && !isIndex(p)) { "This file cannot be deleted: $path" }
        val f = inside(File(root, p))
        require(f.isFile) { "No such file: $p" }
        val now = System.currentTimeMillis()
        val id = "$now-${UUID.randomUUID().toString().take(8)}"
        val dir = trashDir(id)
        check(dir.mkdirs()) { "Could not make the trash folder" }
        val size = f.length()
        // Write the note first, so a trashed file is never left without a way back.
        try {
            File(dir, META).writeText(JSONObject().put("path", p).put("deletedAt", now).put("size", size).toString())
            Files.move(f.toPath(), File(dir, PAYLOAD).toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            deleteTree(dir)
            throw e
        }
        afterChange(p)
        purgeOld()
        TrashItem(id, p, now, size)
    }

    suspend fun trash(): List<TrashItem> = io {
        purgeOld()
        safeTrashRoot()?.listFiles()?.mapNotNull(::trashItem)?.sortedByDescending { it.deletedAt }.orEmpty()
    }

    suspend fun restore(id: String): String = io {
        require(TRASH_ID.matches(id)) { "Bad trash id" }
        val dir = trashDir(id)
        val item = trashItem(dir) ?: error("Nothing in the trash with id $id")
        require(allowed(item.path)) { "Cannot restore this file" }
        val target = inside(File(root, item.path))
        require(!target.exists()) { "A file already exists at ${item.path}; rename or delete it first" }
        target.parentFile?.mkdirs()
        Files.move(File(dir, PAYLOAD).toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        deleteTree(dir)
        afterChange(item.path)
        item.path
    }

    /** Deletes a trashed file for good; only the user does this, from the Files page. */
    suspend fun purge(id: String) = io {
        require(TRASH_ID.matches(id)) { "Bad trash id" }
        deleteTree(trashDir(id))
        Unit
    }

    /** The text of a trashed file, for the forget scan; null for binary or large files. */
    suspend fun trashText(id: String): String? = io {
        require(TRASH_ID.matches(id)) { "Bad trash id" }
        val dir = trashDir(id)
        val item = trashItem(dir) ?: return@io null
        val payload = inside(File(dir, PAYLOAD))
        if (!isText(item.path) || !payload.isFile || payload.length() > MAX_TEXT * 4L) null
        else runCatching { payload.readText() }.getOrNull()
    }

    /** Saves a binary file under files/, keeping an existing one by adding a number to the name. */
    suspend fun saveBytes(path: String, input: InputStream, maxBytes: Long = MAX_FILE_BYTES): Entry = io {
        var p = normalize(path)
        require(p.startsWith("files/") && FILES.matches(p)) { "Only files/ takes new files: $path" }
        val folder = p.substringBeforeLast('/')
        val name = p.substringAfterLast('/')
        val base = "$folder/" + name.substringBeforeLast('.', name).take(100)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (File(root, p).exists()) p = "$base-${n++}$ext"
        require(FILES.matches(p)) { "File name is too long: $path" }
        val target = inside(File(root, p))
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".file-", ".tmp", target.parentFile)
        try {
            temporary.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBytes) { "File is larger than ${maxBytes / 1024 / 1024} MB" }
                    out.write(buffer, 0, count)
                }
            }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temporary.delete()
        }
        entry(p, target)
    }

    private fun writable(path: String): String {
        val p = normalize(path)
        require(allowed(p)) { notOrbitFile(path, p) }
        require(!isIndex(p)) { "INDEX.md is kept by the app; write the person or group file instead" }
        require(isText(p)) { "Only text files can be written: $p" }
        return p
    }

    private fun afterChange(p: String) {
        val dir = p.substringBeforeLast('/', "")
        if (dir == "memory/people" || dir == "memory/groups") rebuildIndex(dir)
    }

    private fun rebuildIndex(dir: String) {
        val folder = File(root, dir)
        val files = folder.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") && it.name != INDEX && runCatching { inside(it) }.isSuccess }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        val index = File(folder, INDEX)
        if (files.isEmpty()) { index.delete(); return }
        val heading = if (dir.endsWith("people")) (if (english) "People" else "人物")
            else (if (english) "Circles & relationships" else "圈子与关系")
        val lines = files.map { f ->
            val text = f.readText()
            val summary = text.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }.orEmpty().take(100)
            "- [${titleOf(text, f.nameWithoutExtension)}](${f.name})" + if (summary.isNotEmpty()) " — $summary" else ""
        }
        atomic(index, "# $heading\n\n" + lines.joinToString("\n") + "\n")
    }

    private fun entry(p: String, f: File): Entry {
        val safe = runCatching { inside(f) }.isSuccess
        return Entry(p, f.length(), f.lastModified(),
            if (safe && isText(p) && f.length() < 64_000) titleOf(f.readText(), f.nameWithoutExtension) else f.name)
    }

    private fun trashItem(dir: File): TrashItem? = runCatching {
        val meta = JSONObject(File(dir, META).readText())
        TrashItem(dir.name, meta.getString("path"), meta.getLong("deletedAt"), meta.optLong("size"))
    }.getOrNull()

    private fun purgeOld() {
        val limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 60 * 60 * 1000
        safeTrashRoot()?.listFiles()?.forEach { dir ->
            // A folder whose note cannot be read is judged by its own age.
            val deletedAt = trashItem(dir)?.deletedAt ?: dir.lastModified()
            if (deletedAt < limit) deleteTree(dir)
        }
    }

    private fun atomic(target: File, text: String) {
        inside(target)
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile(".orbit-", ".tmp", target.parentFile)
        try {
            temporary.writeText(text)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun template(p: String): String? = when (p) {
        LESSONS -> if (english) "# Lessons\n\nConventions and lessons you picked up while working, for your future self.\n\n## Conventions\n\n## Lessons\n"
            else "# 经验本\n\n做事时攒下的约定和教训，写给以后的自己。\n\n## 约定\n\n## 教训\n"
        PROACTIVE -> if (english) "# Proactive preferences\n\nWhen, about what and how the user wants you to reach out first.\n\n" +
            "## Tell me about\n\n## Never tell me about\n\n## When\n\n## How\n"
            else "# 主动消息偏好\n\n用户希望你在什么时候、为了什么事、用什么方式主动找他。\n\n" +
            "## 想听的\n\n## 别跟我说\n\n## 什么时候\n\n## 怎么说\n"
        TIMEZONES -> if (english) "# The user's time zone and those of people they deal with\nhome: \nothers: []\n"
            else "# 用户所在的时区，以及常联系的人的时区\nhome: \nothers: []\n"
        ALIGNMENT -> if (english) ALIGNMENT_EN else ALIGNMENT_ZH
        ALIGNMENT_STATE -> "schema_version: 1\nrelationship_contract: []\nuser_facets: {}\nworking_alliance: \"\"\n" +
            "observed_fit: \"\"\nproactivity_policies: []\nrepair_threads: []\n"
        PROGRESSION -> "outcomes: []\n"
        REPAIR -> "# Open frictions only: {id, trigger, opened, evidence: [message ids], clean_reviews}. No other text.\n[]\n"
        DEVICES -> if (english) "# Devices\n\nDevices you can reach (for example computers or a NAS on the user's Tailscale network): name, address, what it is for and how to connect. Never write passwords here.\n"
            else "# 设备\n\n你能连上的设备（比如用户 Tailscale 组网里的电脑、NAS）：名字、地址、用途和连接方式。不要写密码。\n"
        else -> null
    }

    companion object {
        const val MAX_TEXT = 200_000
        /** Longest line the daily log takes; the rest is cut. */
        const val MAX_DAILY_LINE = 600
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        const val TRASH_DAYS = 30
        const val LESSONS = "LESSONS.md"
        const val PROACTIVE = "PROACTIVE_PREFERENCES.md"
        const val TIMEZONES = "timezones.yaml"
        const val ALIGNMENT = "ALIGNMENT_SYNTHESIS.md"
        const val DEVICES = "DEVICES.md"
        /** The alignment review's own state, beside the synthesis it writes (layout follows the exported review schema). */
        const val ALIGNMENT_STATE = "alignment/ALIGNMENT_STATE.yaml"
        const val PROGRESSION = "alignment/PROGRESSION_HISTORY.yaml"
        const val REPAIR = "alignment/REPAIR_THREADS.yaml"
        /** What the review in progress has written down page by page; begun anew with each review. */
        const val REVIEW_NOTES = "alignment/REVIEW_NOTES.md"
        val ALIGNMENT_DATA = listOf(ALIGNMENT_STATE, PROGRESSION, REPAIR, REVIEW_NOTES)
        /** Not one of the files Orbit reads or writes; the app keeps the user's Library pins here. */
        private const val PINS = "library_pins.json"
        val SINGLE = listOf(LESSONS, PROACTIVE, TIMEZONES, ALIGNMENT, DEVICES)
        private const val INDEX = "INDEX.md"
        private const val TRASH = ".trash"
        private const val PAYLOAD = "payload"
        private const val META = "meta.json"
        private val PEOPLE = Regex("memory/(people|groups)/[^/]{1,80}\\.md")
        private val DAILY = Regex("memory/\\d{4}-\\d{2}-\\d{2}\\.md")
        /** One alignment review a night: dreams/YYYY-MM-DD.md. */
        private val DREAM = Regex("dreams/\\d{4}-\\d{2}-\\d{2}\\.md")
        private val FILES = Regex("files/(?:[^/]{1,80}/){0,3}[^/]{1,120}")
        /** The built-in product docs: readable, never written through these tools (see OrbitDocs). */
        private val DOCS = Regex("docs/[^/]{1,80}\\.md")
        private val TRASH_ID = Regex("\\d{1,15}-[0-9a-f]{8}")
        /** Read-only through orbit_file; written only by the memory tools. Keys are lower case. */
        private val CORE_READABLE = mapOf(
            "user.md" to "user.md",
            "memory.md" to "memory/memory.md",
            "memory/memory.md" to "memory/memory.md",
        )

        /** Why a path is refused, pointing at the tool that owns it when it is one of the core files. */
        private fun notOrbitFile(path: String, p: String): String = when (p.lowercase()) {
            "soul.md", "identity.md" ->
                "$path is not handled by orbit_file; it is already in your context in full. Use orbit_soul to read or change it."
            "user.md" ->
                "orbit_file can only read user.md. Change it with memory_review manage action=memory_change, target=user."
            "memory.md", "memory/memory.md" ->
                "orbit_file can only read memory.md. Change it with memory_review notes."
            else -> "Not one of Orbit's files: $path"
        }
        private val TEXT_EXTENSIONS = setOf("md", "txt", "yaml", "yml", "json", "csv", "html", "htm", "xml", "sh")
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun of(context: Context, orbitId: String) =
            OrbitFiles(OrbitPreferences.getInstance(context).orbitDir(orbitId)) {
                LocaleUtils.usesEnglishPrompt(context.applicationContext)
            }

        fun normalize(path: String): String {
            val p = path.trim().replace('\\', '/').trimStart('/')
            val parts = p.split('/')
            require(p.isNotEmpty() && p.none { it < ' ' } &&
                parts.none { it.isBlank() || it.startsWith(".") }) { "Bad path: $path" }
            return p
        }

        fun allowed(p: String) = p in SINGLE || PEOPLE.matches(p) || DAILY.matches(p) || FILES.matches(p) ||
            p in ALIGNMENT_DATA || DREAM.matches(p)
        fun isIndex(p: String) = PEOPLE.matches(p) && p.endsWith("/$INDEX")
        fun isText(p: String) = p.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

        private const val ALIGNMENT_ZH = "# 对齐总结\n\n你和这位用户怎么相处。每晚的对齐复盘会重写它，每次聊天都会全文带上。\n\n" +
            "## 他是谁、该怎么帮他\n\n## 他从你这里得到什么\n\n## 红线\n\n## 当前的摩擦\n\n## 怎样让关系更好\n\n## 细节去哪找\n"
        private const val ALIGNMENT_EN = "# Alignment synthesis\n\nHow to be with this user. The nightly alignment review " +
            "rewrites it, and every chat carries it in full.\n\n## Who this user is and how to act for them\n\n## User value\n\n" +
            "## Boundaries\n\n## Current frictions\n\n## How you can strengthen the relationship\n\n## Where to look for detail\n"
        /** Lines of the alignment templates, old and new, that say nothing about the user. */
        private val ALIGNMENT_FILLER = setOf(
            "你和这位用户怎么相处。每晚的对齐复盘会重写它，每次聊天都会全文带上。",
            "How to be with this user. The nightly alignment review rewrites it, and every chat carries it in full.",
            "你对这位用户最重要的理解：他在乎什么、怎么判断好坏、喜欢怎么和你合作。后台自我整理会定期更新它。",
            "What matters most about this user: what they care about, how they judge good work, and how they like to " +
                "work with you. Background self-improvement keeps it current.",
        )
        /** Longest synthesis put into the prompt; the review keeps it far shorter. */
        const val MAX_ALIGNMENT_PROMPT = 8000

        /** The first [max] characters of [file], without reading the rest; empty when it is missing. */
        fun readHead(file: File, max: Int): String {
            if (!file.isFile) return ""
            return file.bufferedReader().use { reader ->
                val buffer = CharArray(max)
                var read = 0
                while (read < max) {
                    val n = reader.read(buffer, read, max - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            }
        }

        /** The synthesis to put into every chat, or null while it is still an empty template. */
        fun alignmentForPrompt(text: String): String? {
            val said = text.lineSequence().map { it.trim() }
                .any { it.isNotEmpty() && !it.startsWith("#") && it !in ALIGNMENT_FILLER }
            return if (said) text.trim().take(MAX_ALIGNMENT_PROMPT) else null
        }

        /** The Library's kind of a file, by extension. */

        fun kindOf(p: String): Kind = when (p.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> Kind.IMAGE
            "mp3", "wav", "m4a", "ogg", "aac", "flac", "opus" -> Kind.AUDIO
            "md", "txt", "pdf", "doc", "docx", "html", "htm", "csv", "json", "xls", "xlsx", "ppt", "pptx",
            "yaml", "yml", "xml", "rtf", "odt" -> Kind.DOCUMENT
            else -> Kind.OTHER
        }
        fun titleOf(text: String, fallback: String) =
            text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("#") && !it.startsWith("#!") }
                ?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: fallback
    }
}

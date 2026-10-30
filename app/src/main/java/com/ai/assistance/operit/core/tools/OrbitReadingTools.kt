package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.features.orbit.OrbitFiles
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitReading
import com.ai.assistance.operit.features.orbit.forModel
import com.ai.assistance.operit.features.reading.ReaderProviderException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orbit's side of reading together in Legado: where the user is, the book's chapters and text,
 * and Orbit's own paragraph comments, which Legado shows in its comment bubbles. Notes and
 * summaries are not kept here; Orbit writes those itself with orbit_file (see docs/reading.md).
 */
internal object OrbitReadingTools {
    const val NAME = "orbit_reading"

    private const val MAX_READ_CHARS = 12_000
    private const val DEFAULT_CATALOG = 40
    private const val MAX_CATALOG = 200
    private const val DEFAULT_COPY = 10
    /** Chapters Legado has not saved are fetched from the book's website, so a copy goes a few at a time. */
    private const val MAX_COPY = 20

    val prompt = ToolPrompt(
        name = NAME,
        description = "Read along with the user in Legado (阅读) and leave paragraph comments that Legado shows under your name. " +
            "和用户一起在「阅读」App 里读书：看用户读到哪、读章节原文、把原文拷进自己的文件夹、写/删段评。先读 docs/reading.md。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "where / books / catalog / read / copy / write_comments / list_comments / delete_comments", required = true),
            ToolParameterSchema("book_id", description = "Book id from books or where. Default: the book the user has open", required = false),
            ToolParameterSchema("chapter", "integer", "Chapter number, from 1 (read, write_comments, list_comments, delete_comments; catalog and copy: the first chapter, default the user's)", false),
            ToolParameterSchema("from", "integer", "read: first paragraph, from 1. Default 1", false),
            ToolParameterSchema("to", "integer", "read: last paragraph. Default the end, cut at about $MAX_READ_CHARS characters", required = false),
            ToolParameterSchema("offset", "integer", "read: start this many characters into paragraph from; only to go on with a paragraph cut short (next_offset)", false),
            ToolParameterSchema("count", "integer", "catalog: how many chapters, default $DEFAULT_CATALOG, at most $MAX_CATALOG. copy: default $DEFAULT_COPY, at most $MAX_COPY", false),
            ToolParameterSchema("comments", description = "write_comments: JSON array [{\"paragraph\":3,\"text\":\"...\"}], at most ${OrbitReading.MAX_COMMENTS_PER_CALL}, each at most ${OrbitReading.MAX_COMMENT_CHARS} characters", required = false),
            ToolParameterSchema("mode", description = "write_comments: add (default, keeps the chapter's other comments) or replace (the chapter keeps only these)", required = false),
            ToolParameterSchema("ids", description = "delete_comments: comment ids from list_comments, comma separated", required = false),
            ToolParameterSchema("all", "boolean", "delete_comments: true deletes every comment of chapter", false),
        ),
        details = """
  - where: the open book and chapter, which paragraphs the user's page shows (page_paragraphs; exact=false means guessed), and how many chapters ahead already have your comments.
  - read: paragraphs come as "[n] text"; n is the paragraph number to comment on. "*" after [n] means you already commented there. You may read any chapter, also ones the user has not reached.
  - copy: saves chapters' text into your folder, one file a chapter under files/reading/<book-name-and-id>/text/, lines "[n] text" with the same numbers as read. Use reading_folder from the result for this book's notes and summaries; do not guess it from the title. Then read them with orbit_file read and look things up with grep_code (environment=android, path = absolute_folder from the result). Chapters Legado has not saved are fetched from the book's website, so copy a few at a time and go on with next_chapter.
  - write_comments: comments go on the chapter as Legado lays it out now; read the chapter first so the numbers match. The result says what was saved and where.
  - Comments are kept per Orbit and shown in Legado right away. Notes, summaries and where you stopped are yours to keep with orbit_file under files/reading/.
""",
    )

    fun execute(context: Context, tool: AITool): ToolResult = runBlockingIoPreservingToolRuntimeContext { run(context, tool) }

    suspend fun run(context: Context, tool: AITool): ToolResult = try {
        val caller = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val orbit = checkNotNull(OrbitMemoryScope.owner(context, caller)) { "This is not an Orbit chat" }
        val args = tool.parameters.associate { it.name to it.value?.trim().orEmpty() }
        val reading = OrbitReading(context, orbit.id, readScope = caller ?: orbit.id)
        val result = when (val action = args["action"].orEmpty()) {
            "where" -> where(reading, args)
            "books" -> books(reading)
            "catalog" -> catalog(reading, args)
            "read" -> read(reading, args)
            "copy" -> copy(context, orbit.id, reading, args)
            "write_comments" -> write(reading, args)
            "list_comments" -> list(reading, args)
            "delete_comments" -> delete(reading, args)
            else -> throw IllegalArgumentException(
                "action must be where, books, catalog, read, copy, write_comments, list_comments or delete_comments; got \"$action\"")
        }
        ToolResult(NAME, true, StringResultData(result.toString()))
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: ReaderProviderException) {
        ToolResult(NAME, false, StringResultData(""), error = e.forModel())
    } catch (e: Exception) {
        ToolResult(NAME, false, StringResultData(""), error = e.message ?: e.toString())
    }

    private fun int(args: Map<String, String>, name: String): Int? = args[name]?.takeIf { it.isNotEmpty() }?.let {
        requireNotNull(it.toIntOrNull()) { "$name must be a whole number; got \"$it\"" }
    }

    private suspend fun bookId(reading: OrbitReading, args: Map<String, String>): String =
        args["book_id"]?.takeIf { it.isNotEmpty() } ?: reading.readingState(null).book.id

    /** Chapter number from 1 in [args], checked against the book; returns Legado's index from 0. */
    private suspend fun chapterIndex(reading: OrbitReading, bookId: String, args: Map<String, String>): Int {
        val number = requireNotNull(int(args, "chapter")) { "Give chapter, a chapter number from 1" }
        val total = reading.catalog(bookId).size
        require(number in 1..total) { "chapter must be 1 to $total; got $number" }
        return number - 1
    }

    private suspend fun where(reading: OrbitReading, args: Map<String, String>): JSONObject {
        val state = reading.readingState(args["book_id"]?.takeIf { it.isNotEmpty() })
        val bookId = state.book.id
        val catalog = reading.catalog(bookId)
        val index = state.chapterIndex
        val commented = reading.commentedIndexes(bookId)
        var ahead = 0
        while (index + 1 + ahead in commented) ahead++
        val out = JSONObject()
            .put("book", JSONObject().put("book_id", bookId).put("name", state.book.name).put("author", state.book.author)
                .put("chapters", catalog.size))
            .put("chapter", index + 1)
            .put("chapter_title", state.chapterTitle ?: catalog.getOrNull(index)?.title.orEmpty())
            .put("this_chapter_comments", reading.comments(bookId, index)?.comments?.size ?: 0)
            .put("chapters_ahead_with_comments", ahead)
            .put("last_read_at", state.book.lastReadAt)
        val chapter = runCatching { reading.chapter(bookId, index) }.getOrNull()
        val page = chapter?.let { OrbitReading.locate(it.paragraphs, reading.readable(bookId, index)) }
        if (chapter != null) out.put("chapter_paragraphs", chapter.paragraphs.size)
        if (page != null) {
            out.put("page_paragraphs", JSONObject().put("from", page.first).put("to", page.last).put("exact", page.exact))
        } else {
            out.put("page_note", "Legado did not say where on the chapter the page is.")
        }
        return out
    }

    private suspend fun books(reading: OrbitReading): JSONObject = JSONObject().put("books", JSONArray().apply {
        reading.books().sortedByDescending { it.lastReadAt }.forEach { book ->
            put(JSONObject().put("book_id", book.id).put("name", book.name).put("author", book.author)
                .put("chapters", book.totalChapterCount).put("last_read_at", book.lastReadAt))
        }
    })

    private suspend fun catalog(reading: OrbitReading, args: Map<String, String>): JSONObject {
        val bookId = bookId(reading, args)
        val chapters = reading.catalog(bookId)
        val count = (int(args, "count") ?: DEFAULT_CATALOG).coerceIn(1, MAX_CATALOG)
        val first = (int(args, "chapter") ?: (reading.readingState(bookId).chapterIndex + 1)).coerceIn(1, maxOf(1, chapters.size))
        val commented = reading.commentedIndexes(bookId)
        val shown = chapters.drop(first - 1).take(count)
        return JSONObject().put("book_id", bookId).put("chapters_total", chapters.size)
            .put("chapters", JSONArray().apply {
                shown.forEach { c ->
                    put(JSONObject().put("chapter", c.index + 1).put("title", c.title)
                        .apply { if (c.isVolume) put("volume", true) }
                        .apply { if (c.index in commented) put("has_comments", true) })
                }
            })
            .apply { if (first - 1 + shown.size < chapters.size) put("next_chapter", first + shown.size) }
    }

    private suspend fun read(reading: OrbitReading, args: Map<String, String>): JSONObject {
        val bookId = bookId(reading, args)
        val index = chapterIndex(reading, bookId, args)
        val chapter = reading.chapter(bookId, index)
        val total = chapter.paragraphs.size
        val from = (int(args, "from") ?: 1)
        require(from in 1..maxOf(1, total)) { "from must be 1 to $total; got $from" }
        val to = (int(args, "to") ?: total).coerceAtMost(total)
        require(to >= from) { "to ($to) is before from ($from)" }
        val marked = reading.comments(bookId, index, chapter.sourceId)
            ?.takeIf { it.contractHash == chapter.contractHash }?.comments?.mapTo(HashSet()) { it.paragraph }.orEmpty()
        val offset = int(args, "offset") ?: 0
        require(offset in 0 until maxOf(1, chapter.paragraphs[from - 1].length)) {
            "offset must be 0 to ${maxOf(0, chapter.paragraphs[from - 1].length - 1)} for paragraph $from; got $offset"
        }
        val text = StringBuilder()
        var last = from - 1
        var nextOffset: Int? = null
        for (n in from..to) {
            val start = if (n == from) offset else 0
            val body = chapter.paragraphs[n - 1].substring(start)
            val head = "[$n]${if (n in marked) "*" else ""} ${if (start > 0) "… " else ""}"
            if (text.isEmpty() && body.length > MAX_READ_CHARS) {
                // One paragraph longer than a whole page (a book with no line breaks) is read in pieces.
                text.append(head).append(body, 0, MAX_READ_CHARS).append(" …\n")
                nextOffset = start + MAX_READ_CHARS
                last = n
                break
            }
            val line = "$head$body\n"
            if (text.isNotEmpty() && text.length + line.length > MAX_READ_CHARS) break
            text.append(line)
            last = n
        }
        // Noted only once the text is ready to hand over, so a rejected read changes nothing.
        reading.markSeen(chapter)
        return JSONObject().put("book_id", bookId).put("chapter", index + 1).put("title", chapter.title)
            .put("paragraphs_total", total).put("from", from).put("to", last)
            .put("text", text.toString())
            .apply {
                if (nextOffset != null) {
                    put("next_from", last).put("next_offset", nextOffset)
                    put("note", "Paragraph $last is longer than one read; go on with from=$last, offset=$nextOffset.")
                } else if (last < to) put("next_from", last + 1)
            }
    }

    /**
     * Copies chapters' text into files/reading/<book>/text/, one Markdown file a chapter named by
     * its number, so Orbit can read and search the book with its file tools. A chapter that fails
     * is listed and the rest still copied.
     */
    private suspend fun copy(context: Context, orbitId: String, reading: OrbitReading, args: Map<String, String>): JSONObject {
        val bookId = bookId(reading, args)
        val state = reading.readingState(bookId)
        val catalog = reading.catalog(bookId)
        require(catalog.isNotEmpty()) { "This book has no chapters in Legado" }
        val first = int(args, "chapter") ?: (state.chapterIndex + 1)
        require(first in 1..catalog.size) { "chapter must be 1 to ${catalog.size}; got $first" }
        val count = int(args, "count") ?: DEFAULT_COPY
        require(count in 1..MAX_COPY) { "count must be 1 to $MAX_COPY per call; got $count" }
        val last = minOf(catalog.size, first + count - 1)
        val files = OrbitFiles.of(context, orbitId)
        val readingFolder = readingFolder(state.book.name, bookId)
        val folder = "$readingFolder/text"
        val width = maxOf(4, catalog.size.toString().length)
        val copied = JSONArray()
        val failed = JSONArray()
        var firstError: Exception? = null
        for (number in first..last) {
            try {
                val chapter = reading.chapter(bookId, number - 1, fresh = true, keep = false)
                val title = chapter.title.ifBlank { catalog[number - 1].title }
                val body = buildString {
                    append("# ").append(title).append("\n\n")
                    chapter.paragraphs.forEachIndexed { i, p -> if (p.isNotBlank()) append('[').append(i + 1).append("] ").append(p).append('\n') }
                }
                require(body.length <= OrbitFiles.MAX_TEXT) { "Chapter $number has ${body.length} characters, too long for one file" }
                val prefix = number.toString().padStart(width, '0')
                val path = "$folder/" + "$prefix ${safeName(title)}".trim() + ".md"
                // A copy saved under an older title goes, so each chapter has one file.
                // Matched by the number's value, so a copy padded to another width (the book grew) goes too.
                files.fileFor(folder).list().orEmpty()
                    .filter { chapterOf(it) == number && "$folder/$it" != path }
                    .forEach { runCatching { files.delete("$folder/$it") } }
                files.write(path, body)
                // Only a copy that was saved counts as seen.
                reading.markSeen(chapter)
                copied.put(JSONObject().put("chapter", number).put("title", title).put("path", path)
                    .put("paragraphs", chapter.paragraphs.size))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                failed.put(JSONObject().put("chapter", number)
                    .put("error", (e as? ReaderProviderException)?.forModel() ?: e.message ?: e.toString()))
            }
        }
        firstError?.takeIf { copied.length() == 0 }?.let { throw it }
        return JSONObject().put("book_id", bookId).put("book", state.book.name).put("folder", folder)
            .put("reading_folder", readingFolder)
            .put("absolute_folder", files.fileFor(folder).absolutePath)
            .put("copied", copied)
            .apply { if (failed.length() > 0) put("failed", failed) }
            .apply { if (last < catalog.size) put("next_chapter", last + 1) }
            .put("note", "Copied ${copied.length()} chapter(s) into $folder. Read one with orbit_file read path=<path>; " +
                "find words across the copied text with grep_code environment=android path=<absolute_folder>. [n] is the " +
                "paragraph number write_comments takes, as long as the chapter has not changed in Legado since." +
                (if (failed.length() > 0) " ${failed.length()} chapter(s) failed; see failed, and try them again later." else ""))
    }

    /** Book URLs, rather than cleaned or truncated titles, distinguish books with the same name. */
    internal fun readingFolder(name: String, bookId: String): String =
        "files/reading/${safeName(name).ifEmpty { "book" }}-${OrbitReading.bookKey(bookId)}"

    /** A name usable as one folder or file name part: no path or reserved characters, no leading dot, at most 60 characters. */
    internal fun safeName(name: String): String = name
        .map { if (it < ' ' || it in "/\\:*?\"<>|") ' ' else it }.joinToString("")
        .replace(Regex("\\s+"), " ").trimStart { it == '.' || it.isWhitespace() }.take(60).trim()

    /** The chapter number a copied file's name starts with ("0012 Title.md" or "0012.md"), or null. */
    internal fun chapterOf(fileName: String): Int? {
        val digits = fileName.takeWhile { it.isDigit() }
        if (digits.isEmpty()) return null
        val rest = fileName.substring(digits.length)
        return if (rest.startsWith(" ") || rest == ".md") digits.toIntOrNull() else null
    }

    private suspend fun write(reading: OrbitReading, args: Map<String, String>): JSONObject {
        val bookId = bookId(reading, args)
        val index = chapterIndex(reading, bookId, args)
        val mode = args["mode"].orEmpty().ifEmpty { "add" }
        require(mode == "add" || mode == "replace") { "mode must be add or replace; got \"$mode\"" }
        val raw = args["comments"].orEmpty()
        require(raw.isNotEmpty()) { "Give comments, a JSON array like [{\"paragraph\":3,\"text\":\"...\"}]" }
        val list = runCatching { JSONArray(raw) }.getOrElse {
            throw IllegalArgumentException("comments is not a JSON array: ${it.message}")
        }
        require(list.length() in 1..OrbitReading.MAX_COMMENTS_PER_CALL) {
            "Give 1 to ${OrbitReading.MAX_COMMENTS_PER_CALL} comments per call; got ${list.length()}"
        }
        // Taken before the fetch below, which records the version it gets.
        val seen = reading.seen(bookId, index)
        val chapter = reading.chapter(bookId, index, fresh = true)
        require(seen == null || seen == chapter.contractHash) {
            "Chapter ${index + 1} changed in Legado since you read or copied it, so the paragraph numbers may have moved. " +
                "Read it again (or copy it again), then write."
        }
        val total = chapter.paragraphs.size
        val entries = (0 until list.length()).map { i ->
            val item = list.optJSONObject(i) ?: throw IllegalArgumentException("comments[$i] is not an object")
            val paragraph = item.optInt("paragraph", -1)
            require(paragraph in 1..total) { "comments[$i].paragraph must be 1 to $total; got ${item.opt("paragraph")}" }
            require(chapter.paragraphs[paragraph - 1].isNotBlank()) { "comments[$i]: paragraph $paragraph is empty; pick one with text" }
            val text = item.optString("text").trim()
            require(text.isNotEmpty()) { "comments[$i].text is empty" }
            require(text.length <= OrbitReading.MAX_COMMENT_CHARS) {
                "comments[$i].text has ${text.length} characters; keep it to ${OrbitReading.MAX_COMMENT_CHARS}"
            }
            paragraph to text
        }
        val (saved, dropped) = reading.write(chapter, entries, replace = mode == "replace")
        val added = entries.count { (p, t) -> saved.comments.any { it.paragraph == p && it.text == t } }
        return JSONObject().put("saved", true).put("book_id", bookId).put("chapter", index + 1).put("title", chapter.title)
            .put("mode", mode)
            .put("written", JSONArray().apply {
                entries.forEach { (p, t) ->
                    val c = saved.comments.lastOrNull { it.paragraph == p && it.text == t }
                    put(JSONObject().put("paragraph", p).put("id", c?.id ?: JSONObject.NULL).put("text", t))
                }
            })
            .put("chapter_comments_now", saved.comments.size)
            .put("note", "Saved $added comment(s) on chapter ${index + 1}; Legado shows them in its paragraph bubbles." +
                (if (dropped > 0) " $dropped older comment(s) were removed because their paragraphs are gone from the new layout." else ""))
    }

    private suspend fun list(reading: OrbitReading, args: Map<String, String>): JSONObject {
        val bookId = bookId(reading, args)
        val index = chapterIndex(reading, bookId, args)
        // Fresh, so a chapter Legado laid out again since the last read is caught.
        val chapter = reading.chapter(bookId, index, fresh = true)
        val saved = reading.comments(bookId, index, chapter.sourceId) ?: reading.comments(bookId, index)
        // Comments saved on another layout keep their old numbers until the next write moves them.
        val current = saved == null || (saved.sourceId == chapter.sourceId && saved.contractHash == chapter.contractHash)
        return JSONObject().put("book_id", bookId).put("chapter", index + 1)
            .put("comments", JSONArray().apply {
                saved?.comments?.forEach { c ->
                    put(JSONObject().put("id", c.id).put(if (current) "paragraph" else "saved_paragraph", c.paragraph).put("text", c.text))
                }
            })
            .apply { if (saved == null) put("note", "No comments on chapter ${index + 1} yet.") }
            .apply {
                if (!current && saved?.sourceId == chapter.sourceId) put("note", "Chapter ${index + 1} has changed in Legado " +
                    "since these were written, so saved_paragraph is the old number. Legado still places most of them by " +
                    "their text. To tidy them, read the chapter, then write_comments (add) moves the ones it can and removes the rest.")
                else if (!current) put("note", "Chapter ${index + 1} now comes from a different source in Legado, so these " +
                    "comments (saved_paragraph is their old number) may not show. Delete them with delete_comments if they no longer fit.")
            }
    }

    private suspend fun delete(reading: OrbitReading, args: Map<String, String>): JSONObject {
        val bookId = bookId(reading, args)
        if (args["all"].equals("true", ignoreCase = true)) {
            val index = chapterIndex(reading, bookId, args)
            val gone = reading.deleteChapter(bookId, index)
            return JSONObject().put("deleted", gone).put("chapter", index + 1)
                .put("note", if (gone == 0) "Chapter ${index + 1} had no comments." else "Deleted all $gone comment(s) of chapter ${index + 1}.")
        }
        val ids = args["ids"].orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        require(ids.isNotEmpty()) { "Give ids (from list_comments), or chapter with all=true" }
        val gone = reading.delete(bookId, ids)
        val missing = ids - gone.map { it.second.id }.toSet()
        return JSONObject().put("deleted", gone.size)
            .put("removed", JSONArray().apply {
                gone.forEach { (chapter, c) -> put(JSONObject().put("id", c.id).put("chapter", chapter.index + 1).put("paragraph", c.paragraph)) }
            })
            .apply { if (missing.isNotEmpty()) put("not_found", JSONArray(missing.toList())) }
    }
}

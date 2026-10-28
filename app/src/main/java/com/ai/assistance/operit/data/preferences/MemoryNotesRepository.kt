package com.ai.assistance.operit.data.preferences

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Small, space-scoped notes included when a system prompt is built. All writers re-read under
 * the same lock; an editor must supply the version it opened so it cannot overwrite new notes.
 */
class MemoryNotesRepository internal constructor(
    private val root: File,
    val profileId: String,
    /** Set for an Orbit: memory.md lives in this folder instead of under [root]. */
    private val folder: File? = null,
    /** An Orbit's memory.md is sorted into [MemoryNoteSections]; other spaces keep free text. */
    private val format: SectionFormat? = null,
) {
    constructor(context: Context, profileId: String) :
        this(File(context.filesDir, "memory_notes"), profileId,
            MemorySearchSettingsPreferences.orbitMemoryDir(context.filesDir, profileId),
            sectionFormat(context, profileId))

    /** How a sectioned document is rendered: only the heading language differs. */
    data class SectionFormat(val english: Boolean)

    /** Whether edits of this document must name a section. */
    val sectioned: Boolean get() = format != null

    /** The text an editor shows: a sectioned document with every heading and no marker lines. */
    fun editorText(markdown: String): String =
        format?.let { MemoryNoteSections.editable(markdown, it.english) } ?: markdown

    /** The document an editor's text stores; the inverse of [editorText] for unchanged text. */
    fun fromEditor(text: String): String =
        format?.let { MemoryNoteSections.parse(text).markdown(it.english) } ?: text

    companion object {
        const val MAX_CHARS = 6_000
        /** Guidance the extraction model needs when an edit would push memory.md over the limit. */
        internal const val OVERFLOW_MESSAGE =
            "memory.md would exceed its character limit; remove or replace existing text in this batch before adding"
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun sectionFormat(context: Context, profileId: String): SectionFormat? =
            if (MemorySearchSettingsPreferences.isOrbitSpace(profileId))
                SectionFormat(com.ai.assistance.operit.util.LocaleUtils.usesEnglishPrompt(context.applicationContext))
            else null

        /**
         * The one implementation of note edit semantics. [MemoryNotesRepository.mutate] and the
         * extraction tool both route through this, so the two cannot drift apart or disagree on
         * which edits are legal.
         *
         * With a [format] the document is sectioned: an add goes into [section], and a replace or
         * remove edits the one section holding [oldText] (or [section] when given), so an edit can
         * never break a section marker. A free-text document from before is kept whole in the
         * unsorted section on its first edit.
         */
        internal fun applyEdit(
            current: String, action: String, content: String, oldText: String,
            section: String = "", format: SectionFormat? = null,
        ): String {
            if (format == null) return applyPlainEdit(current, action, content, oldText)
            val sections = MemoryNoteSections.parse(stripInvisibleCharacters(current))
            val target = section.trim().lowercase()
            val key = when {
                action == "add" -> target.takeIf { it in MemoryNoteSections.WRITABLE || it == MemoryNoteSections.UNSORTED }
                    ?: throw NotesException(Failure.SECTION)
                target.isNotEmpty() -> target.takeIf { it in MemoryNoteSections.WRITABLE || it == MemoryNoteSections.UNSORTED }
                    ?: throw NotesException(Failure.SECTION)
                action != "replace" && action != "remove" -> throw NotesException(Failure.INVALID)
                else -> {
                    val old = stripInvisibleCharacters(oldText)
                    if (old.isBlank()) throw NotesException(Failure.EMPTY)
                    val holders = sections.bodies().filter { it.second.contains(old) }
                    holders.singleOrNull()?.first ?: throw NotesException(Failure.NOT_UNIQUE)
                }
            }
            // Cleaned before the duplicate check, so a retried add compares what was stored.
            val edited = applyPlainEdit(sections.get(key), action, MemoryNoteSections.cleanEntry(content), oldText)
            return sections.with(key, edited).markdown(format.english)
        }

        private fun applyPlainEdit(current: String, action: String, content: String, oldText: String): String {
            // The document is stripped at its write point, so every side of the comparison is judged on
            // the characters that will be stored. A document an older version wrote with a carried
            // invisible character would otherwise never match the edit that describes it, which either
            // rejects a legal edit or appends a second copy of a note the document already holds.
            val stored = stripInvisibleCharacters(current)
            val text = stripInvisibleCharacters(content)
            val old = stripInvisibleCharacters(oldText)
            return when (action) {
                "add" -> {
                    val entry = text.trim()
                    if (entry.isEmpty()) throw NotesException(Failure.EMPTY)
                    // Match whole paragraphs, not prefixes such as "port 22" inside "port 2202".
                    val document = "\n\n${stored.trim().replace("\r\n", "\n")}\n\n"
                    if (document.contains("\n\n${entry.replace("\r\n", "\n")}\n\n")) stored
                    else listOf(stored.trimEnd(), entry).filter { it.isNotEmpty() }.joinToString("\n\n")
                }
                "replace", "remove" -> {
                    if (old.isBlank()) throw NotesException(Failure.EMPTY)
                    val first = stored.indexOf(old)
                    if (first < 0 || stored.indexOf(old, first + 1) >= 0) {
                        throw NotesException(Failure.NOT_UNIQUE)
                    }
                    if (action == "replace" && text.isBlank()) throw NotesException(Failure.EMPTY)
                    stored.replaceRange(
                        first, first + old.length, if (action == "remove") "" else text
                    ).trim()
                }
                else -> throw NotesException(Failure.INVALID)
            }
        }
    }

    data class Snapshot(val markdown: String, val version: String)
    data class Proposal(val before: Snapshot, val after: String)
    enum class Failure { FULL, CONFLICT, EMPTY, NOT_UNIQUE, INVALID, SECTION }
    class NotesException(val reason: Failure) : IllegalArgumentException(reason.name)

    private val legacyFile = File(File(root, digest(profileId)), "memory.md")
    private val file = folder?.let { File(it, "memory.md") } ?: legacyFile
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }

    private fun read(): Snapshot {
        MemorySearchSettingsPreferences.moveLegacyFile(legacyFile, file)
        val text = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        return Snapshot(text, digest(text))
    }

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun delete() = withContext(Dispatchers.IO) {
        mutex.withLock {
            Files.deleteIfExists(file.toPath())
            Files.deleteIfExists(legacyFile.toPath())
            legacyFile.parentFile?.delete()
            // Only removes the folder once it is empty, so an Orbit's changes.json stays put.
            file.parentFile?.delete()
            Unit
        }
    }

    suspend fun save(markdown: String, expectedVersion: String): Snapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (read().version != expectedVersion) throw NotesException(Failure.CONFLICT)
                write(markdown)
            }
        }

    suspend fun mutate(action: String, content: String = "", oldText: String = "", section: String = ""): Snapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read()
                write(edit(current, action, content, oldText, section))
            }
        }

    suspend fun preview(action: String, content: String = "", oldText: String = "", section: String = ""): Proposal =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read()
                val next = edit(current, action, content, oldText, section)
                if (next.length > MAX_CHARS) throw NotesException(Failure.FULL)
                Proposal(current, next)
            }
        }

    private fun edit(current: Snapshot, action: String, content: String, oldText: String, section: String): String =
        applyEdit(current.markdown, action, content, oldText, section, format)

    private fun write(markdown: String): Snapshot {
        // memory.md is injected into later system prompts, so this single write point is where a
        // hidden character must not survive, whichever caller produced the text.
        val text = stripInvisibleCharacters(markdown)
        if (text.length > MAX_CHARS) throw NotesException(Failure.FULL)
        file.parentFile!!.mkdirs()
        val temp = File.createTempFile(".memory-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        syncDirectory(file.parentFile!!)
        return Snapshot(text, digest(text))
    }
}

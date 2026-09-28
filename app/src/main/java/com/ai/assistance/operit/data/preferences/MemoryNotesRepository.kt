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
class MemoryNotesRepository internal constructor(private val root: File, val profileId: String) {
    constructor(context: Context, profileId: String) :
        this(File(context.filesDir, "memory_notes"), profileId)

    companion object {
        const val MAX_CHARS = 6_000
        /** Guidance the extraction model needs when an edit would push memory.md over the limit. */
        internal const val OVERFLOW_MESSAGE =
            "memory.md would exceed its character limit; remove or replace existing text in this batch before adding"
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * The one implementation of note edit semantics. [MemoryNotesRepository.mutate] and the
         * extraction tool both route through this, so the two cannot drift apart or disagree on
         * which edits are legal.
         */
        internal fun applyEdit(current: String, action: String, content: String, oldText: String): String =
            when (action) {
                "add" -> {
                    // The document is stripped at its write point, so the edit has to be judged on the
                    // same characters: a carried invisible character can never match what is stored and
                    // would append a second copy of text the notes already hold.
                    val entry = stripInvisibleCharacters(content).trim()
                    if (entry.isEmpty()) throw NotesException(Failure.EMPTY)
                    // Match whole paragraphs, not prefixes such as "port 22" inside "port 2202".
                    val document = "\n\n${current.trim().replace("\r\n", "\n")}\n\n"
                    if (document.contains("\n\n${entry.replace("\r\n", "\n")}\n\n")) current
                    else listOf(current.trimEnd(), entry).filter { it.isNotEmpty() }.joinToString("\n\n")
                }
                "replace", "remove" -> {
                    // Same reason as above: old_text is matched against the stored text, so it must be
                    // stripped too or an edit that is legal on disk is rejected as not found or as
                    // ambiguous, and the replacement writes the mark back in.
                    val old = stripInvisibleCharacters(oldText)
                    val text = stripInvisibleCharacters(content)
                    if (old.isBlank()) throw NotesException(Failure.EMPTY)
                    val first = current.indexOf(old)
                    if (first < 0 || current.indexOf(old, first + 1) >= 0) {
                        throw NotesException(Failure.NOT_UNIQUE)
                    }
                    if (action == "replace" && text.isBlank()) throw NotesException(Failure.EMPTY)
                    current.replaceRange(
                        first, first + old.length, if (action == "remove") "" else text
                    ).trim()
                }
                else -> throw NotesException(Failure.INVALID)
            }
    }

    data class Snapshot(val markdown: String, val version: String)
    data class Proposal(val before: Snapshot, val after: String)
    enum class Failure { FULL, CONFLICT, EMPTY, NOT_UNIQUE, INVALID }
    class NotesException(val reason: Failure) : IllegalArgumentException(reason.name)

    private val file = File(File(root, digest(profileId)), "memory.md")
    private val mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }

    private fun read(): Snapshot {
        val text = if (file.exists()) file.readText(Charsets.UTF_8) else ""
        return Snapshot(text, digest(text))
    }

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) { mutex.withLock { read() } }

    suspend fun delete() = withContext(Dispatchers.IO) {
        mutex.withLock {
            Files.deleteIfExists(file.toPath())
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

    suspend fun mutate(action: String, content: String = "", oldText: String = ""): Snapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read()
                write(edit(current, action, content, oldText))
            }
        }

    suspend fun preview(action: String, content: String = "", oldText: String = ""): Proposal =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = read()
                val next = edit(current, action, content, oldText)
                if (next.length > MAX_CHARS) throw NotesException(Failure.FULL)
                Proposal(current, next)
            }
        }

    private fun edit(current: Snapshot, action: String, content: String, oldText: String): String =
        applyEdit(current.markdown, action, content, oldText)

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

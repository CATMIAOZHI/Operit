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
        private val locks = ConcurrentHashMap<String, Mutex>()
        private fun digest(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
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
                when (action) {
                    "add" -> append(current.markdown, content)
                    "replace", "remove" -> {
                        if (oldText.isBlank()) throw NotesException(Failure.EMPTY)
                        val first = current.markdown.indexOf(oldText)
                        if (first < 0 || current.markdown.indexOf(oldText, first + 1) >= 0) {
                            throw NotesException(Failure.NOT_UNIQUE)
                        }
                        if (action == "replace" && content.isBlank()) throw NotesException(Failure.EMPTY)
                        current.markdown.replaceRange(
                            first, first + oldText.length, if (action == "remove") "" else content
                        ).trim()
                    }
                    else -> throw NotesException(Failure.INVALID)
                }

    /** Background extraction only has this append API, never a replace/remove operation. */
    suspend fun appendFromBackground(notes: List<String>): Snapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                var next = read().markdown
                notes.filter { it.isNotBlank() }.forEach { next = append(next, it) }
                // The batch is all-or-nothing. In particular, never evict old notes to make room.
                write(next)
            }
        }

    private fun append(current: String, content: String): String {
        val entry = content.trim()
        if (entry.isEmpty()) throw NotesException(Failure.EMPTY)
        // Match whole paragraphs, not prefixes such as "port 22" inside "port 2202".
        val document = "\n\n${current.trim().replace("\r\n", "\n")}\n\n"
        if (document.contains("\n\n${entry.replace("\r\n", "\n")}\n\n")) return current
        return listOf(current.trimEnd(), entry).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    private fun write(text: String): Snapshot {
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
        return Snapshot(text, digest(text))
    }
}

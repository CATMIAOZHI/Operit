package com.ai.assistance.operit.data.preferences

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryNotesRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun repo(id: String = "default") = MemoryNotesRepository(folder.root, id)

    @Test fun `notes survive reload and spaces including path-like ids stay isolated`() = runBlocking {
        repo().mutate("add", "Use port 2202")
        repo("../../other").mutate("add", "Use port 3303")
        assertEquals("Use port 2202", repo().load().markdown)
        assertEquals("Use port 3303", repo("../../other").load().markdown)
        assertEquals(2, folder.root.listFiles()!!.size)
        assertTrue(folder.root.listFiles()!!.all { File(it, "memory.md").isFile })
    }

    @Test fun `parallel foreground and background writes preserve all additions`() = runBlocking {
        // Every writer is a read-modify-write through the same lock; none may drop another's entry.
        (1..20).map { n -> async { repo().mutate("add", "entry-$n.") } }.awaitAll()
        val saved = repo().load().markdown
        (1..20).forEach { assertTrue(saved.contains("entry-$it.")) }
        repo().mutate("add", "entry-1.")
        assertEquals(saved, repo().load().markdown)
    }

    @Test fun `stale editor cannot erase a background addition`() = runBlocking {
        val initial = repo().load()
        repo().mutate("add", "New fact")
        fails(MemoryNotesRepository.Failure.CONFLICT) { repo().save("Old draft", initial.version) }
        assertEquals("New fact", repo().load().markdown)
    }

    @Test fun `a prefix of an existing note is not an exact duplicate`() = runBlocking {
        repo().mutate("add", "Use port 2202")
        repo().mutate("add", "Use port 22")
        assertEquals("Use port 2202\n\nUse port 22", repo().load().markdown)
    }

    @Test fun `deleting one space keeps the other space notes`() = runBlocking {
        repo("work").mutate("add", "Work notes")
        repo("home").mutate("add", "Home notes")
        repo("work").delete()
        assertEquals("", repo("work").load().markdown)
        assertEquals("Home notes", repo("home").load().markdown)
    }

    @Test fun `overflow rejects additions and replacement without evicting notes`() = runBlocking {
        repo().mutate("add", "a".repeat(MemoryNotesRepository.MAX_CHARS - 3))
        val before = repo().load()
        // Three characters of headroom: one more paragraph (plus its blank-line separator) no longer fits.
        fails(MemoryNotesRepository.Failure.FULL) { repo().mutate("add", "bcd") }
        assertEquals(before, repo().load())
        fails(MemoryNotesRepository.Failure.FULL) {
            repo().mutate("replace", "z".repeat(MemoryNotesRepository.MAX_CHARS + 1), before.markdown)
        }
        assertEquals(before, repo().load())
    }

    @Test fun `ambiguous edits fail and exact removal and replacement persist`() = runBlocking {
        repo().mutate("add", "Project A uses Kotlin. Project B uses Kotlin.")
        fails(MemoryNotesRepository.Failure.NOT_UNIQUE) { repo().mutate("remove", oldText = "Kotlin") }
        repo().mutate("replace", "Project A uses Rust.", "Project A uses Kotlin.")
        repo().mutate("remove", oldText = "Project B uses Kotlin.")
        assertEquals("Project A uses Rust.", repo().load().markdown)
        fails(MemoryNotesRepository.Failure.NOT_UNIQUE) { repo().mutate("remove", oldText = "missing") }
    }

    private suspend fun fails(reason: MemoryNotesRepository.Failure, block: suspend () -> Unit) {
        try { block(); fail("Expected $reason") }
        catch (e: MemoryNotesRepository.NotesException) { assertEquals(reason, e.reason) }
    }
}

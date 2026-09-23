package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.any

class MemoryReviewRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun context(): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(folder.root)
        val preferences = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(it.getSharedPreferences(any(), any())).thenReturn(preferences)
        whenever(preferences.edit()).thenReturn(editor)
        whenever(editor.putString(any(), any())).thenReturn(editor)
    }

    @Test fun `history keeps unfinished items and only the newest finished ones`() {
        fun change(id: Int, status: String) = MemoryReviewChange(
            id = "c$id", kind = "notes", title = "memory.md", body = "b$id",
            createdAt = id.toLong(), reviewedAt = id.toLong(), status = status)
        val items = listOf(change(1,"pending"), change(2,"applying")) +
            (1..MemoryReviewRepository.HISTORY_LIMIT + 5).map { change(100 + it, "approved") }
        val kept = MemoryReviewRepository.pruneHistory(items)
        assertEquals(MemoryReviewRepository.HISTORY_LIMIT + 2, kept.size)
        // Unfinished work is never dropped, even though its timestamps are the oldest here.
        assertTrue(kept.any { it.id == "c1" })
        assertTrue(kept.any { it.id == "c2" })
        // The five oldest finished entries fall out; the newest one survives.
        assertFalse(kept.any { it.id == "c105" })
        assertTrue(kept.any { it.id == "c${100 + MemoryReviewRepository.HISTORY_LIMIT + 5}" })
    }

    @Test fun `proposals do not write notes and rejection retains content and audit`() = runBlocking {
        val context = context()
        val notes = MemoryNotesRepository(context, "a")
        val repo = MemoryReviewRepository(context, "a")
        val change = repo.proposeNotes(notes.load(), "proposed")
        assertEquals("", notes.load().markdown)
        repo.audit(change.id, "Needs verification", "ai")
        repo.decide(context, change.id, false, "user", "Unverified")
        val saved = MemoryReviewRepository(context, "a").list().single()
        assertEquals("rejected", saved.status)
        assertEquals("proposed", saved.body)
        assertEquals("Unverified", saved.reason)
        assertTrue(saved.audits.contains("Needs verification"))
        assertEquals("", notes.load().markdown)
        assertTrue(MemoryReviewRepository(context, "b").list().isEmpty())
    }

    @Test fun `approval is idempotent and stale replacement stays pending`() = runBlocking {
        val context = context()
        val notes = MemoryNotesRepository(context, "a")
        val repo = MemoryReviewRepository(context, "a")
        val old = notes.load()
        val addition = repo.proposeNotes(old, "first", "first")
        notes.mutate("add", "other")
        repo.decide(context, addition.id, true, "user", "Reviewed")
        repo.decide(context, addition.id, true, "ai", "Retry")
        assertEquals("other\n\nfirst", notes.load().markdown)
        val stale = repo.proposeNotes(old, "overwrite")
        try { repo.decide(context, stale.id, true, "user", "Reviewed"); fail() }
        catch (e: MemoryNotesRepository.NotesException) { assertEquals(MemoryNotesRepository.Failure.CONFLICT, e.reason) }
        assertEquals("pending", repo.list().first { it.id == stale.id }.status)
        assertEquals("other\n\nfirst", notes.load().markdown)
        try { repo.decide(context, addition.id, false, "user", "Undo"); fail() }
        catch (_: IllegalStateException) { }
    }

    @Test fun `editing draft preserves previous reviewed content under a different identity`() = runBlocking {
        val context = context()
        val repo = MemoryReviewRepository(context, "a")
        val initial = repo.proposeNotes(MemoryNotesRepository(context, "a").load(), "draft")
        repo.audit(initial.id, "Old audit", "ai")
        val revised = repo.revise(initial.id, "revised", "")
        assertNotEquals(initial.id, revised.id)
        assertEquals("", revised.audits)
        val history = repo.list().first { it.id == initial.id }
        assertEquals("superseded", history.status)
        assertEquals("draft", history.body)
        assertTrue(history.audits.contains("Old audit"))
    }

    @Test fun `same content can be proposed again after a terminal decision`() = runBlocking {
        val context = context()
        val repo = MemoryReviewRepository(context, "a")
        val notes = MemoryNotesRepository(context, "a")
        suspend fun save(value: String): String {
            val proposal = repo.proposeNotes(notes.load(), value)
            repo.decide(context, proposal.id, true, "user", "Edit")
            return proposal.id
        }
        val first = save("B")
        save("")
        val last = save("B")
        assertNotEquals(first, last)
        assertEquals("B", notes.load().markdown)
        assertEquals(3, repo.list().size)
    }

    @Test fun `skill extraction rejects malformed and unsafe names`() {
        val valid = JSONObject().put("name", "check-build").put("description", "Check build")
            .put("body", "A verified procedure with prerequisites, commands and checks.".repeat(2))
        assertEquals(1, parseSkillDrafts(JSONArray().put(valid), "chat").size)
        assertTrue(parseSkillDrafts(JSONArray().put(JSONObject(valid.toString()).put("name", "../bad")), "chat").isEmpty())
        assertTrue(parseSkillDrafts(JSONObject(), "chat").isEmpty())
        assertTrue(parseSkillDrafts(JSONArray().put(JSONObject(valid.toString()).put("name", "tool_capability_inquiry")), "chat").isEmpty())
        assertEquals(1, parseSkillDrafts(JSONArray().put(JSONObject(valid.toString()).put("name", "capability-inquiry")), "chat").size)
    }

    @Test fun `a supplied skill frontmatter is folded away instead of doubled`() {
        val body = "A verified procedure with prerequisites, commands and checks.".repeat(2)
        val withHeader = "---\nname: check-build\ndescription: \"Check build\"\n---\n\n$body"
        assertEquals(body, stripSkillFrontmatter(withHeader))
        // No header, an unterminated header and plain dashes are left untouched.
        assertEquals(body, stripSkillFrontmatter(body))
        assertEquals("---\nname: check-build", stripSkillFrontmatter("---\nname: check-build"))
        assertEquals("-- dash led text", stripSkillFrontmatter("-- dash led text"))
        // The parsed draft is what gets installed, so the header cannot survive here either.
        val parsed = parseSkillDrafts(JSONArray().put(
            JSONObject().put("name", "check-build").put("description", "Check build").put("body", withHeader)), "chat")
        assertEquals(body, parsed.single().body)
        // A body that is nothing but frontmatter has no content left to install.
        assertTrue(parseSkillDrafts(JSONArray().put(JSONObject().put("name", "check-build")
            .put("description", "Check build").put("body", "---\nname: check-build\ndescription: \"x\"\n---\n")),
            "chat").isEmpty())
    }

    @Test fun `extraction logs retain outcome after reload and isolate spaces`() = runBlocking {
        val repo = MemoryExtractionLogRepository(folder.root, "a")
        val initial = MemoryExtractionLog(sourceChatId = "chat", graph = false, notes = true, skills = true)
        repo.save(initial)
        repo.save(initial.copy(status = "failed", finishedAt = initial.startedAt + 12, detail = "IOException",
            runId = "run", childChatId = "child", modelRounds = 2, toolCalls = 7))
        val result = MemoryExtractionLogRepository(folder.root, "a").list().single()
        assertEquals("failed", result.status)
        assertEquals(initial.id, result.id)
        assertEquals("run", result.runId)
        assertEquals("child", result.childChatId)
        assertEquals(2, result.modelRounds)
        assertEquals(7, result.toolCalls)
        assertTrue(MemoryExtractionLogRepository(folder.root, "b").list().isEmpty())
    }

    @Test fun `old extraction logs without audit metadata still load`() = runBlocking {
        val repo = MemoryExtractionLogRepository(folder.root, "a")
        val log = MemoryExtractionLog(sourceChatId = "chat", graph = false, notes = true, skills = true)
        repo.save(log)
        val file = folder.root.listFiles()!!.single { it.extension == "json" }
        val json = JSONArray(file.readText())
        listOf("runId", "childChatId", "modelRounds", "toolCalls").forEach { json.getJSONObject(0).remove(it) }
        file.writeText(json.toString())
        val restored = repo.list().single()
        assertEquals(log.id, restored.id)
        assertEquals("", restored.runId)
        assertEquals("", restored.childChatId)
        assertEquals(0, restored.modelRounds)
    }
}

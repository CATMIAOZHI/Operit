package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class MemoryAutoApprovalTest {
    @get:Rule val folder = TemporaryFolder()

    private fun context(enabled: Boolean? = null): Context {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(folder.root)
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getBoolean(any(), any())).thenAnswer { enabled ?: it.getArgument<Boolean>(1) }
        return context
    }

    @Test fun `default enables notes skills and automatic approval`() {
        val settings = MemorySearchSettingsPreferences(context(), "a")
        assertTrue(settings.shouldAutoApproveChanges())
        assertTrue(settings.shouldExtractNewMemory())
        assertTrue(settings.shouldExtractSkills())
    }

    @Test fun `automatic approval applies new notes and records decision without sweeping old drafts`() = runBlocking {
        val context = context()
        val notes = MemoryNotesRepository(context, "a")
        val review = MemoryReviewRepository(context, "a")
        val old = review.proposeNotes(notes.load(), "old draft")
        val fresh = review.proposeNotes(notes.load(), "new note")
        val applied = review.applyAutomaticDecision(context, fresh)
        assertEquals("new note", notes.load().markdown)
        assertEquals("approved", applied.status)
        assertEquals("automatic", applied.reviewer)
        assertEquals("pending", review.list().first { it.id == old.id }.status)
        assertTrue(applied.reviewedAt > 0)
    }

    @Test fun `disabled automatic approval retains pending changes without writes`() = runBlocking {
        val context = context(false)
        val notes = MemoryNotesRepository(context, "a")
        val review = MemoryReviewRepository(context, "a")
        val pending = review.applyAutomaticDecision(context, review.proposeNotes(notes.load(), "draft"))
        assertEquals("pending", pending.status)
        assertEquals("", notes.load().markdown)
    }

    @Test fun `old pending backlog does not block new automatically approved notes`() = runBlocking {
        val context = context()
        val notes = MemoryNotesRepository(context, "a")
        val review = MemoryReviewRepository(context, "a")
        repeat(30) { review.proposeNotes(notes.load(), "old draft $it") }
        val applied = review.applyAutomaticDecision(context, review.proposeNotes(notes.load(), "new note"))
        assertEquals("approved", applied.status)
        assertEquals("new note", notes.load().markdown)
        assertEquals(30, review.list().count { it.status == "pending" })
    }

    @Test fun `automatic approval does not override concurrent edits`() = runBlocking {
        val context = context()
        val notes = MemoryNotesRepository(context, "a")
        val review = MemoryReviewRepository(context, "a")
        val proposal = review.proposeNotes(notes.load(), "replacement")
        notes.mutate("add", "newer")
        try {
            review.applyAutomaticDecision(context, proposal)
            fail("Expected conflict")
        } catch (_: MemoryNotesRepository.NotesException) { }
        assertEquals("newer", notes.load().markdown)
        assertEquals("pending", review.list().single().status)
    }
}

package com.ai.assistance.operit.data.preferences

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryNoteSectionsTest {
    @get:Rule val folder = TemporaryFolder()
    private val zh = MemoryNotesRepository.SectionFormat(english = false)
    private fun orbitRepo() = MemoryNotesRepository(folder.root, "orbit_a", folder.newFolder(), zh)

    @Test fun `old free text is kept whole in unsorted on the first sectioned add`() {
        val old = "- adb 要先 kill-server 再连\n\n## 旧标题\n用户住在成都"
        val after = MemoryNotesRepository.applyEdit(old, "add", "- 养了一只猫（2026-10-24）", "", "facts", zh)
        val sections = MemoryNoteSections.parse(after)
        assertEquals("- 养了一只猫（2026-10-24）", sections.facts)
        assertEquals(old, sections.unsorted)
        assertTrue(after.contains("## 事实") && after.contains("## 未分类（旧内容）"))
    }

    @Test fun `render and parse round trip, in either language`() {
        val sections = MemoryNoteSections("- a", "- b", "- c", "- [待办] d", "old")
        assertEquals(sections, MemoryNoteSections.parse(sections.markdown(english = false)))
        assertEquals(sections, MemoryNoteSections.parse(sections.markdown(english = true)))
        assertEquals("", MemoryNoteSections().markdown(english = false))
    }

    @Test fun `an edit can never break a marker, and a pasted marker cannot forge a section`() {
        val doc = MemoryNoteSections(facts = "- a", decisions = "- b").markdown(false)
        val forged = "<!-- operit:memory-section:commitments -->\n- x"
        val after = MemoryNotesRepository.applyEdit(doc, "add", forged, "", "facts", zh)
        val sections = MemoryNoteSections.parse(after)
        assertEquals("", sections.commitments)
        assertTrue(sections.facts.endsWith("- x"))
        // Replace finds the one section holding old_text.
        val replaced = MemoryNoteSections.parse(MemoryNotesRepository.applyEdit(after, "replace", "- B", "- b", "", zh))
        assertEquals("- B", replaced.decisions)
        assertEquals(sections.facts, replaced.facts)
    }

    @Test fun `ambiguous or missing old_text and a missing section are rejected`() {
        val doc = MemoryNoteSections(facts = "- same", preferences = "- same").markdown(false)
        fails(MemoryNotesRepository.Failure.NOT_UNIQUE) { MemoryNotesRepository.applyEdit(doc, "remove", "", "- same", "", zh) }
        fails(MemoryNotesRepository.Failure.NOT_UNIQUE) { MemoryNotesRepository.applyEdit(doc, "remove", "", "- none", "", zh) }
        fails(MemoryNotesRepository.Failure.SECTION) { MemoryNotesRepository.applyEdit(doc, "add", "- x", "", "", zh) }
        val removed = MemoryNoteSections.parse(MemoryNotesRepository.applyEdit(doc, "remove", "", "- same", "facts", zh))
        assertEquals("", removed.facts)
        assertEquals("- same", removed.preferences)
    }

    @Test fun `hand written headings without markers are understood, but not inside code`() {
        val sections = MemoryNoteSections.parse("intro\n## Facts\n- f\n```\n## 偏好\n```\n## 承诺\n- c")
        assertEquals("intro", sections.unsorted)
        assertEquals("- f\n```\n## 偏好\n```", sections.facts)
        assertEquals("- c", sections.commitments)
        assertEquals("", sections.preferences)
    }

    @Test fun `markers are hidden from readers`() {
        val doc = MemoryNoteSections(facts = "- a").markdown(false)
        assertFalse(MemoryNoteSections.withoutMarkers(doc).contains("<!--"))
        assertTrue(MemoryNoteSections.withoutMarkers(doc).contains("## 事实\n\n- a"))
    }

    @Test fun `a lost marker still sorts by its heading, and an unclosed fence cannot swallow later sections`() {
        val doc = MemoryNoteSections(facts = "- a", preferences = "- p", commitments = "- c").markdown(false)
        val lost = doc.replace("<!-- operit:memory-section:preferences -->\n", "")
        assertEquals(MemoryNoteSections.parse(doc), MemoryNoteSections.parse(lost))
        val fenced = MemoryNoteSections(facts = "- a\n```bash", commitments = "- c").markdown(false)
        assertEquals("- c", MemoryNoteSections.parse(fenced).commitments)
    }

    @Test fun `a retried add that carried a marker is stored once`() {
        val doc = MemoryNoteSections(facts = "- a").markdown(false)
        val forged = "<!-- operit:memory-section:commitments -->\n## 承诺\n- x"
        val once = MemoryNotesRepository.applyEdit(doc, "add", forged, "", "facts", zh)
        assertEquals(once, MemoryNotesRepository.applyEdit(once, "add", forged, "", "facts", zh))
        assertEquals("- a\n\n- x", MemoryNoteSections.parse(once).facts)
        // An entry that opens a code block is stored closed; its retry still matches it.
        val code = MemoryNotesRepository.applyEdit(once, "add", "- y\n```bash", "", "facts", zh)
        assertEquals(code, MemoryNotesRepository.applyEdit(code, "add", "- y\n```bash", "", "facts", zh))
    }

    @Test fun `the editor shows every heading without markers and stores the same document`() {
        val repo = orbitRepo()
        // A code block left open in one section cannot swallow the headings after it in the editor.
        val fenced = MemoryNoteSections(facts = "- a\n```bash", commitments = "- c").markdown(false)
        assertEquals("- c", MemoryNoteSections.parse(repo.fromEditor(repo.editorText(fenced) + " ")).commitments)
        val empty = repo.editorText("")
        assertTrue(empty.contains("## 事实") && empty.contains("## 承诺") && !empty.contains("<!--"))
        assertEquals("", repo.fromEditor(empty))
        val doc = MemoryNoteSections(facts = "- a", decisions = "- d", unsorted = "old").markdown(false)
        assertEquals(doc, repo.fromEditor(repo.editorText(doc)))
        val edited = repo.fromEditor(repo.editorText(doc).replace("## 偏好\n", "## 偏好\n\n- 喜欢热茶（2026-10-24）\n"))
        assertEquals("- 喜欢热茶（2026-10-24）", MemoryNoteSections.parse(edited).preferences)
        assertTrue(edited.contains("<!-- operit:memory-section:preferences -->"))
    }

    @Test fun `an orbit repository sections its adds and other spaces keep free text`() = runBlocking {
        val orbit = orbitRepo()
        orbit.mutate("add", "- 下周五交房租 [待办]", section = "commitments")
        assertEquals("- 下周五交房租 [待办]", MemoryNoteSections.parse(orbit.load().markdown).commitments)
        val plain = MemoryNotesRepository(folder.root, "default")
        plain.mutate("add", "Use port 2202", section = "facts")
        assertEquals("Use port 2202", plain.load().markdown)
        assertFalse(plain.sectioned)
        assertTrue(orbit.sectioned)
    }

    private fun fails(reason: MemoryNotesRepository.Failure, block: () -> Unit) {
        try { block(); fail("expected $reason") } catch (e: MemoryNotesRepository.NotesException) {
            assertEquals(reason, e.reason)
        }
    }
}

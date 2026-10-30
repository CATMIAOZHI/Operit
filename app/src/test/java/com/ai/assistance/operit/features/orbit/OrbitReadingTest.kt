package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.features.reading.ReadableChapterContent
import com.ai.assistance.operit.features.reading.ReaderProvider
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class OrbitReadingTest {
    private fun reading(root: File) = OrbitReading(mock<Context>(), "o1", mock<ReaderProvider>(), root)

    private fun chapter(paragraphs: List<String>, hash: String) =
        OrbitReading.Chapter("book", 4, "src-5", "Five", paragraphs, hash)

    @Test
    fun commentsFollowTheirParagraphWhenTheLayoutChanges(): Unit = runBlocking {
        val root = Files.createTempDirectory("orbit-reading").toFile()
        val reading = reading(root)
        reading.write(chapter(listOf("a", "bee", "sea"), "h1"), listOf(2 to "on bee", 3 to "on sea"), replace = false)
        // "bee" moved down; "sea" now appears twice, so its comment cannot be placed.
        val (saved, dropped) = reading.write(chapter(listOf("x", "y", "bee", "sea", "sea"), "h2"),
            listOf(1 to "on x"), replace = false)
        assertEquals(1, dropped)
        assertEquals(listOf(1 to "on x", 3 to "on bee"), saved.comments.map { it.paragraph to it.text })
        assertEquals("h2", saved.contractHash)
        assertEquals(setOf(4), reading.commentedIndexes("book"))
        root.deleteRecursively()
    }

    @Test
    fun replaceAndDelete(): Unit = runBlocking {
        val root = Files.createTempDirectory("orbit-reading").toFile()
        val reading = reading(root)
        val one = chapter(listOf("a", "b", "c"), "h1")
        reading.write(one, listOf(1 to "first", 2 to "second"), replace = false)
        val (saved, _) = reading.write(one, listOf(3 to "only"), replace = true)
        assertEquals(listOf("only"), saved.comments.map { it.text })
        val id = saved.comments.single().id
        val gone = reading.delete("book", setOf(id, "5-missing"))
        assertEquals(listOf(id), gone.map { it.second.id })
        assertNull(reading.comments("book", 4))
        reading.write(one, listOf(1 to "again"), replace = false)
        assertEquals(1, reading.deleteChapter("book", 4))
        assertTrue(reading.commentedIndexes("book").isEmpty())
        root.deleteRecursively()
    }

    @Test
    fun summaryOffersRemappingOnlyWhenAskedAndOnlyForUniqueParagraphs() {
        val comments = OrbitReading.ChapterComments("src", 0, "t", "old", 3, 1L, listOf(
            OrbitReading.Comment("1-a", 1, "fa", true, "unique", 1L),
            OrbitReading.Comment("1-b", 2, "fb", false, "repeated", 1L),
        ))
        val stale = OrbitReadingLegado.summaryOf(comments, "new", mappingRequested = false)
        assertFalse(stale.getBoolean("ready"))
        assertTrue(stale.getBoolean("stale"))
        val remap = OrbitReadingLegado.summaryOf(comments, "new", mappingRequested = true)
        assertTrue(remap.getBoolean("remapRequired"))
        assertEquals(1, remap.getJSONArray("comments").length())
        val exact = OrbitReadingLegado.summaryOf(comments, "old", mappingRequested = true)
        assertFalse(exact.getBoolean("remapRequired"))
        assertEquals(2, exact.getJSONArray("comments").length())
    }

    private fun summary(hash: String, remap: Boolean, vararg paragraphs: Pair<Int, String>) = JSONObject()
        .put("enabled", true).put("ready", true).put("contentHash", hash).put("remapRequired", remap)
        .put("comments", JSONArray(paragraphs.map { (p, preview) ->
            JSONObject().put("paragraphIndex", p).put("count", 1).put("preview", preview)
        }))

    @Test
    fun mergeSummary() {
        val merged = OrbitReadingLegado.mergeSummary(summary("h", false, 1 to "main", 3 to "main3"),
            summary("h", false, 1 to "orbit", 2 to "orbit2"))
        val list = merged.getJSONArray("comments")
        assertEquals(listOf(1, 2, 3), (0 until list.length()).map { list.getJSONObject(it).getInt("paragraphIndex") })
        assertEquals(2, list.getJSONObject(0).getInt("count"))
        assertEquals("main", list.getJSONObject(0).getString("preview"))
        val orbitOnly = summary("h", false, 2 to "orbit")
        assertEquals(orbitOnly, OrbitReadingLegado.mergeSummary(summary("old", true, 1 to "main"), orbitOnly))
        assertEquals(orbitOnly, OrbitReadingLegado.mergeSummary(JSONObject().put("enabled", true).put("ready", false), orbitOnly))
        assertEquals(orbitOnly, OrbitReadingLegado.mergeSummary(null, orbitOnly))
        assertFalse(OrbitReadingLegado.mergeSummary(null, null).getBoolean("enabled"))
        // Both saved on the same older layout: still merged, with what Legado needs to place them.
        val old = OrbitReadingLegado.mergeSummary(summary("old", true, 1 to "main"), summary("old", true, 2 to "orbit"))
        assertTrue(old.getBoolean("remapRequired"))
        assertTrue(old.has("mappingVersion"))
        assertEquals(2, old.getJSONArray("comments").length())
    }

    @Test
    fun mergeDetail() {
        val main = JSONObject().put("ready", true).put("comments", JSONArray().put(JSONObject().put("id", "m")))
        val orbit = JSONArray().put(JSONObject().put("id", "o"))
        val merged = OrbitReadingLegado.mergeDetail(main, orbit).getJSONArray("comments")
        assertEquals(listOf("m", "o"), (0 until merged.length()).map { merged.getJSONObject(it).getString("id") })
        assertEquals(main, OrbitReadingLegado.mergeDetail(main, null))
        assertEquals(1, OrbitReadingLegado.mergeDetail(null, orbit).getJSONArray("comments").length())
    }

    @Test
    fun locateFindsThePageByItsText() {
        val paragraphs = listOf("The first line here", "Second paragraph text", "Third one is longer", "Fourth")
        val text = paragraphs.joinToString("\n")
        val start = text.indexOf("Second")
        val end = text.indexOf("Third") + 5
        val readable = ReadableChapterContent("book", "src", 0, "t", text, end, false, 0, 0L, start, end)
        val page = OrbitReading.locate(paragraphs, readable)!!
        assertEquals(2, page.first)
        assertEquals(3, page.last)
        assertTrue(page.exact)
        val guessed = OrbitReading.locate(paragraphs, readable.copy(visibleStart = null, visibleEnd = null))!!
        assertFalse(guessed.visible)
        assertNull(OrbitReading.locate(paragraphs, null))
    }

    @Test
    fun searchFindsLinesInCopiedText(): Unit = runBlocking {
        val root = Files.createTempDirectory("orbit-files").toFile()
        val files = OrbitFiles(root)
        files.write("files/reading/Book/text/0001 One.md", "# One\n\n[1] Ye Wenjie looks up.\n[2] Nothing here.\n")
        files.write("files/reading/Book/text/0002 Two.md", "# Two\n\n[1] ye wenjie again, " + "x".repeat(400) + "\n")
        files.write("files/other.md", "Ye Wenjie elsewhere\n")
        val (hits, total) = files.search("Ye Wenjie", "files/reading/Book/text", 50)
        assertEquals(2, total)
        assertEquals(listOf("files/reading/Book/text/0001 One.md" to 3, "files/reading/Book/text/0002 Two.md" to 3),
            hits.map { it.path to it.line })
        assertTrue(hits[1].text.length < 260)
        assertEquals(3, files.search("ye wenjie", null, 1).second)
        assertEquals(1, files.search("ye wenjie", null, 1).first.size)
        root.deleteRecursively()
    }

    @Test
    fun safeNameKeepsAFileNameUsable() {
        assertEquals("a b c", com.ai.assistance.operit.core.tools.OrbitReadingTools.safeName(" ..a/b\\c "))
        assertEquals("", com.ai.assistance.operit.core.tools.OrbitReadingTools.safeName("..."))
        assertEquals(60, com.ai.assistance.operit.core.tools.OrbitReadingTools.safeName("长".repeat(100)).length)
        assertEquals("三体", com.ai.assistance.operit.core.tools.OrbitReadingTools.safeName(". .三体"))
    }

    @Test
    fun chapterOfReadsTheNumberWhateverItsPadding() {
        val tools = com.ai.assistance.operit.core.tools.OrbitReadingTools
        assertEquals(12, tools.chapterOf("0012 Title.md"))
        assertEquals(12, tools.chapterOf("00012 Title.md"))
        assertEquals(12, tools.chapterOf("0012.md"))
        assertEquals(null, tools.chapterOf("0012x.md"))
        assertEquals(null, tools.chapterOf("notes.md"))
    }
}

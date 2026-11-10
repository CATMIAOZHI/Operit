package com.ai.assistance.operit.ui.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactPagesTest {
    private fun text(pages: List<ArtifactPage>, source: String) =
        pages.joinToString("") { source.substring(it.start, it.end) }

    @Test
    fun manyShortLinesAreSplitIntoLightPages() {
        val source = (1..1000).joinToString("") { "# $it\n\n" }
        val pages = artifactPages(source, markdown = true)
        assertTrue(pages.size >= 6)
        pages.forEach { page -> assertTrue(source.substring(page.start, page.end).count { it == '\n' } <= 302) }
        assertEquals(source, text(pages, source))
    }

    @Test
    fun aLongTableIsNotCutWithoutABlankLine() {
        val source = "| a | b |\n|---|---|\n" + (1..600).joinToString("") { "| $it | x |\n" } + "\nAfter.\n"
        val pages = artifactPages(source, markdown = true)
        assertTrue(source.substring(pages[0].start, pages[0].end).contains("| 600 | x |"))
        assertEquals(source, text(pages, source))
    }

    @Test
    fun lineAfterLineWithoutBlanksStillEndsAPage() {
        val source = (1..2000).joinToString("") { "# $it\n" }
        val pages = artifactPages(source, markdown = true)
        assertEquals(2, pages.size)
        assertEquals(source, text(pages, source))
    }

    @Test
    fun aShortDocumentIsOnePage() {
        val source = "# Title\n\nSome words.\n"
        val pages = artifactPages(source, markdown = true)
        assertEquals(1, pages.size)
        assertEquals(source, text(pages, source))
    }

    @Test
    fun linesInsideACodeBlockDoNotFillAPage() {
        val code = (1..500).joinToString("") { "line $it\n" }
        val source = "```\n$code```\n"
        val pages = artifactPages(source, markdown = true)
        assertEquals(1, pages.size)
    }
}

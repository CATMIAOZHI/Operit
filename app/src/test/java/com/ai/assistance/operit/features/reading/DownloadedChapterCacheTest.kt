package com.ai.assistance.operit.features.reading

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadedChapterCacheTest {
    private val chapters = (0..3).map { ReaderChapter("book", "source-$it", it, "chapter $it") }
    private fun content(chapter: ReaderChapter) = ReadableChapterContent(
        chapter.bookId, chapter.sourceId, chapter.index, chapter.title, "text", 4, true, 5, 1L)

    @Test fun `missing downloads are skipped and later chapters copied with resumable snapshots`() = runBlocking {
        val saved = mutableSetOf(0)
        val reads = mutableListOf<Int>()
        suspend fun copy() = cacheDownloadedChapterSnapshots(chapters,
            hasCompleteSnapshot = { it.index in saved },
            readLocal = { reads += it.index; if (it.index == 1) null else content(it) },
            save = { chapter, _ -> saved += chapter.index }, onProgress = {})
        val result = copy()
        assertEquals(listOf(1, 2, 3), reads)
        assertEquals(2, result.getInt("copiedCount"))
        assertEquals(1, result.getInt("unavailableCount"))
        reads.clear()
        val resumed = copy()
        assertEquals(listOf(1), reads)
        assertEquals(3, resumed.getInt("existingCount"))
    }

    @Test fun `wrong source is rejected before saving`() = runBlocking {
        var saved = false
        val result = runCatching { cacheDownloadedChapterSnapshots(chapters.take(1), { false },
            { content(it).copy(sourceId = "replacement") }, { _, _ -> saved = true }, {}) }
        assertTrue(result.isFailure)
        assertFalse(saved)
    }
}

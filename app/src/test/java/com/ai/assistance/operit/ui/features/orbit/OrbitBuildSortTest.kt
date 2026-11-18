package com.ai.assistance.operit.ui.features.orbit

import com.ai.assistance.operit.features.orbit.OrbitFiles
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitBuildSortTest {
    private fun file(path: String, size: Long, at: Long) = OrbitFiles.Entry(path, size, at, path.substringAfterLast('/'))

    private val files = listOf(
        file("files/report.md", 10, 5),
        file("files/reading/book/text/01.md", 100, 7),
        file("files/reading/book/text/02.md", 50, 9),
        file("files/reading/notes.md", 3, 2),
        file("files/voice/a.ogg", 200, 1),
    )

    @Test fun topShowsItsOwnFilesAndOneRowPerFolderSummingWhatIsInside() {
        val (folders, own) = madeFolderView(files, "")
        assertEquals(listOf("files/report.md"), own.map { it.path })
        val reading = folders.single { it.name == "reading" }
        assertEquals("reading", reading.path)
        assertEquals(3, reading.count)
        assertEquals(153L, reading.size)
        assertEquals(9L, reading.modifiedAt)
        assertEquals(setOf("reading", "voice"), folders.map { it.name }.toSet())
    }

    @Test fun aFolderShowsOnlyWhatIsDirectlyInIt() {
        val (folders, own) = madeFolderView(files, "reading")
        assertEquals(listOf("files/reading/notes.md"), own.map { it.path })
        assertEquals(listOf("reading/book"), folders.map { it.path })
        assertEquals(2, madeFolderView(files, "reading/book/text").second.size)
        assertEquals(Pair(emptyList<MadeFolder>(), emptyList<OrbitFiles.Entry>()), madeFolderView(files, "gone"))
    }

    @Test fun anEmptiedFolderGivesWayToTheNearestOneStillHoldingFiles() {
        assertEquals("reading/book/text", nearestMadeFolder(files, "reading/book/text"))
        assertEquals("reading", nearestMadeFolder(files, "reading/gone/deeper"))
        assertEquals("", nearestMadeFolder(files, "gone"))
    }

    private data class Row(val name: String, val time: Long, val size: Long, val folder: Boolean = false)

    private val rows = listOf(
        Row("b.txt", 3, 10), Row("a.pdf", 1, 30), Row("Docs", 0, 0, folder = true), Row("c.md", 2, 20),
    )

    private fun sorted(sort: BuildSort) =
        rows.sortedWith(buildOrder(sort, { it.name }, { it.time }, { it.size }, { it.folder })).map { it.name }

    @Test fun foldersStayOnTopInEveryOrder() {
        assertEquals(listOf("Docs", "b.txt", "c.md", "a.pdf"), sorted(BuildSort.TIME))
        assertEquals(listOf("Docs", "a.pdf", "b.txt", "c.md"), sorted(BuildSort.NAME))
        assertEquals(listOf("Docs", "a.pdf", "c.md", "b.txt"), sorted(BuildSort.SIZE))
        assertEquals(listOf("Docs", "c.md", "a.pdf", "b.txt"), sorted(BuildSort.TYPE))
    }
}

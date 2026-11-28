package com.ai.assistance.operit.util

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveUtilEntryFileTest {
    private val root: File = Files.createTempDirectory("archive-entry").toFile().canonicalFile

    @Test
    fun normalNamesStayInsideTheFolder() {
        assertEquals(File(root, "a/b.txt"), ArchiveUtil.entryFile(root, "a/b.txt"))
        assertEquals(File(root, "b.txt"), ArchiveUtil.entryFile(root, "a/../b.txt"))
        assertEquals(root, ArchiveUtil.entryFile(root, "./"))
    }

    @Test
    fun namesThatClimbOutAreRejected() {
        assertThrows(SecurityException::class.java) { ArchiveUtil.entryFile(root, "../evil.txt") }
        assertThrows(SecurityException::class.java) { ArchiveUtil.entryFile(root, "a/../../evil.txt") }
        assertThrows(SecurityException::class.java) {
            ArchiveUtil.entryFile(root, "../${root.name}-sibling/evil.txt")
        }
    }
}

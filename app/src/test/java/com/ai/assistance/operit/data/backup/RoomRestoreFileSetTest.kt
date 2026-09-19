package com.ai.assistance.operit.data.backup

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class RoomRestoreFileSetTest {
    @Test
    fun `every partial replacement rolls back as a set after restart`() {
        for (failureIndex in 0..2) {
            val root = kotlin.io.path.createTempDirectory("room-restore-test").toFile()
            try {
                val db = File(root, "app_database")
                val workspace = File(root, "staging").apply { mkdirs() }
                val names = listOf("app_database", "app_database-wal", "app_database-shm")
                names.forEach { name ->
                    File(root, name).writeText("old-$name")
                    File(workspace, name).writeText("new-$name")
                }
                val spool = File(root, "spool").apply { mkdirs() }
                File(spool, "pending").writeText("unrecorded usage")
                val replacing = RoomRestoreFileSet(db, workspace,
                    beforeReplace = { if (it == failureIndex) throw IOException("injected") },
                    spoolDirectory = spool, syncDirectory = {})
                assertThrows(IOException::class.java) { replacing.replace() }
                // Also simulate partial cleanup after database replacement.
                File(spool, "pending").delete()
                val restarted = RoomRestoreFileSet(db, workspace,
                    spoolDirectory = spool, syncDirectory = {})
                assertTrue(restarted.hasRollback)
                restarted.rollback()
                restarted.rollback() // rollback sources survive interruption/retry
                names.forEach { name -> assertEquals("old-$name", File(root, name).readText()) }
                assertEquals("unrecorded usage", File(spool, "pending").readText())
            } finally {
                root.deleteRecursively()
            }
        }
    }

    @Test
    fun `rollback removes companion files absent from original database`() {
        val root = kotlin.io.path.createTempDirectory("room-restore-test").toFile()
        try {
            val db = File(root, "app_database").apply { writeText("original") }
            val workspace = File(root, "staging").apply { mkdirs() }
            File(workspace, db.name).writeText("replacement")
            File(workspace, "${db.name}-wal").writeText("new wal")
            val files = RoomRestoreFileSet(db, workspace, syncDirectory = {})
            files.replace()
            assertEquals("replacement", db.readText())
            assertTrue(File(root, "${db.name}-wal").exists())
            files.rollback()
            assertEquals("original", db.readText())
            assertFalse(File(root, "${db.name}-wal").exists())
            assertFalse(File(root, "${db.name}-shm").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `missing rollback source fails before touching the database`() {
        val root = kotlin.io.path.createTempDirectory("room-restore-test").toFile()
        try {
            val db = File(root, "app_database").apply { writeText("original") }
            val workspace = File(root, "staging").apply { mkdirs() }
            File(workspace, db.name).writeText("replacement")
            val files = RoomRestoreFileSet(db, workspace, syncDirectory = {})
            files.replace()
            File(workspace, "old/${db.name}").delete()
            assertThrows(IllegalStateException::class.java) { files.rollback() }
            assertEquals("replacement", db.readText())
            assertTrue(files.hasRollback)
        } finally {
            root.deleteRecursively()
        }
    }
}

package com.ai.assistance.operit.data.terminal.startup

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalStartupServicePolicyTest {
    @Test
    fun `startup logs stop reaching a detached UI listener`() {
        val received = mutableListOf<String>()
        val forwarder = DetachableLogForwarder(received::add)

        forwarder.emit("before detach")
        forwarder.detach()
        forwarder.emit("after detach")

        assertEquals(listOf("before detach"), received)
    }

    @Test
    fun `executable startup configuration lives under no-backup storage`() {
        val noBackupRoot = File("private-no-backup")

        assertEquals(
            File(noBackupRoot, "operit/terminal-startup-services"),
            terminalStartupServiceDirectory(noBackupRoot),
        )
    }

    @Test
    fun `completion snapshots are not appended as incremental service logs`() {
        assertEquals("line", incrementalStartupLogChunk("line", isCompleted = false))
        assertEquals(null, incrementalStartupLogChunk("line", isCompleted = true))
        assertEquals(null, incrementalStartupLogChunk("  ", isCompleted = false))
    }

    @Test
    fun `minimum process-only timeout becomes ready before its deadline`() {
        assertEquals(750L, processOnlyStartupReadyDelayMs(1_000L))
        assertEquals(1_000L, processOnlyStartupReadyDelayMs(30_000L))
    }

    @Test
    fun `failed config persistence restores the previous imported script`() = runBlocking {
        val directory = Files.createTempDirectory("terminal-startup-script-test").toFile()
        try {
            val target = File(directory, "service.sh").apply { writeText("old") }
            val staged = File(directory, "service.pending").apply { writeText("new") }

            try {
                replaceStagedFileAndPersist(staged, target) {
                    throw IllegalStateException("persist failed")
                }
                fail("Expected persistence failure")
            } catch (expected: IllegalStateException) {
                assertEquals("persist failed", expected.message)
            }

            assertEquals("old", target.readText())
            assertFalse(staged.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `failed first save leaves no imported script behind`() = runBlocking {
        val directory = Files.createTempDirectory("terminal-startup-script-test").toFile()
        try {
            val target = File(directory, "service.sh")
            val staged = File(directory, "service.pending").apply { writeText("new") }

            try {
                replaceStagedFileAndPersist(staged, target) {
                    throw IllegalStateException("persist failed")
                }
                fail("Expected persistence failure")
            } catch (_: IllegalStateException) {
                // Expected.
            }

            assertFalse(target.exists())
            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `successful config persistence commits the staged script`() = runBlocking {
        val directory = Files.createTempDirectory("terminal-startup-script-test").toFile()
        try {
            val target = File(directory, "service.sh").apply { writeText("old") }
            val staged = File(directory, "service.pending").apply { writeText("new") }
            var persisted = false

            replaceStagedFileAndPersist(staged, target) { persisted = true }

            assertTrue(persisted)
            assertEquals("new", target.readText())
            assertFalse(staged.exists())
            assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancelling script staging still removes the pending file`() = runBlocking {
        val directory = Files.createTempDirectory("terminal-startup-script-test").toFile()
        try {
            val staged = File(directory, "service.pending").apply { writeText("new") }
            val entered = CompletableDeferred<Unit>()
            val job = launch {
                useStagedFile(staged) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }

            entered.await()
            job.cancelAndJoin()

            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `cancelling an entered commit still publishes the persisted replacement`() = runBlocking {
        val directory = Files.createTempDirectory("terminal-startup-script-test").toFile()
        try {
            val target = File(directory, "service.sh").apply { writeText("old") }
            val staged = File(directory, "service.pending").apply { writeText("new") }
            val persistEntered = CompletableDeferred<Unit>()
            val releasePersist = CompletableDeferred<Unit>()
            var published = false
            val job = launch {
                replaceStagedFilePersistAndPublish(
                    stagedFile = staged,
                    targetFile = target,
                    persist = {
                        persistEntered.complete(Unit)
                        releasePersist.await()
                    },
                    publish = { published = true },
                )
            }

            persistEntered.await()
            job.cancel()
            releasePersist.complete(Unit)
            job.join()

            assertTrue(published)
            assertEquals("new", target.readText())
            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}

package com.ai.assistance.operit.data.terminal.startup

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TerminalStartupServicePolicyTest {
    @Test
    fun `startup logs stop reaching a detached UI listener`() {
        val received = mutableListOf<String>()
        val forwarder = DetachableLogForwarder(received::add)

        forwarder.emit("first")
        forwarder.emit("second")
        assertTrue(received.isEmpty())
        forwarder.flush()
        assertEquals(listOf("first\nsecond"), received)
        forwarder.emit("before detach")
        forwarder.detach()
        forwarder.emit("after detach")

        assertEquals(listOf("first\nsecond", "before detach"), received)
    }

    @Test
    fun `detach waits for an in-flight final log flush`() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val detachFinished = AtomicBoolean(false)
        val received = mutableListOf<String>()
        val forwarder = DetachableLogForwarder { message ->
            callbackEntered.countDown()
            releaseCallback.await()
            received += message
        }
        forwarder.emit("final")

        val flushThread = Thread(forwarder::flush).apply { start() }
        callbackEntered.await()
        val detachThread = Thread {
            forwarder.detach()
            detachFinished.set(true)
        }.apply { start() }
        Thread.sleep(50L)
        assertFalse(detachFinished.get())

        releaseCallback.countDown()
        flushThread.join()
        detachThread.join()

        assertTrue(detachFinished.get())
        assertEquals(listOf("final"), received)
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
        assertEquals(10L, startupPollDelayMs(10L))
        assertEquals(250L, startupPollDelayMs(1_000L))
        assertEquals(0L, startupPollDelayMs(-1L))
    }

    @Test
    fun `invalid persisted health-check ports fail closed`() {
        assertEquals(null, decodePersistedHealthCheckPort(null))
        assertEquals(1, decodePersistedHealthCheckPort(1))
        assertEquals(65535, decodePersistedHealthCheckPort(65535L))
        listOf<Any>(0, 65536, 1.5, 4_294_967_297L, Double.NaN, "123").forEach { raw ->
            try {
                decodePersistedHealthCheckPort(raw)
                fail("Expected invalid port failure for $raw")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `invalid persisted startup timeouts fail closed`() {
        assertEquals(
            TerminalStartupServiceConfig.DEFAULT_STARTUP_TIMEOUT_MS,
            decodePersistedStartupTimeoutMs(null),
        )
        assertEquals(1_000L, decodePersistedStartupTimeoutMs(1_000))
        assertEquals(300_000L, decodePersistedStartupTimeoutMs(300_000L))
        listOf<Any>(999, 300_001, 1_000.5, Double.NaN, "30000").forEach { raw ->
            try {
                decodePersistedStartupTimeoutMs(raw)
                fail("Expected invalid timeout failure for $raw")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `invalid persisted enabled flags fail closed`() {
        assertTrue(decodePersistedEnabled(null))
        assertTrue(decodePersistedEnabled(true))
        assertFalse(decodePersistedEnabled(false))
        listOf<Any>(1, 0, "true", "false").forEach { raw ->
            try {
                decodePersistedEnabled(raw)
                fail("Expected invalid enabled flag failure for $raw")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `invalid persisted auto restart flags fail closed`() {
        assertTrue(decodePersistedAutoRestart(null))
        assertTrue(decodePersistedAutoRestart(true))
        assertFalse(decodePersistedAutoRestart(false))
        listOf<Any>(1, 0, "true", "false").forEach { raw ->
            try {
                decodePersistedAutoRestart(raw)
                fail("Expected invalid auto-restart flag failure for $raw")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `invalid persisted restart limits fail closed`() {
        assertEquals(
            TerminalStartupServiceConfig.DEFAULT_MAX_RESTART_ATTEMPTS,
            decodePersistedMaxRestartAttempts(null),
        )
        assertEquals(0, decodePersistedMaxRestartAttempts(0))
        assertEquals(3, decodePersistedMaxRestartAttempts(3L))
        listOf<Any>(-1, 4, 1.5, Double.NaN, "3").forEach { raw ->
            try {
                decodePersistedMaxRestartAttempts(raw)
                fail("Expected invalid restart-limit failure for $raw")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `persisted service IDs must be canonical UUID path components`() {
        val validId = UUID.randomUUID().toString()
        assertEquals(validId, decodePersistedTerminalStartupServiceId(validId))
        assertEquals(
            validId.uppercase(),
            decodePersistedTerminalStartupServiceId(validId.uppercase()),
        )
        listOf<Any?>(null, "", "service", "../service", "a/b", "1-1-1-1-1", 7)
            .forEach { raw ->
                try {
                    decodePersistedTerminalStartupServiceId(raw)
                    fail("Expected invalid service ID failure for $raw")
                } catch (_: IllegalArgumentException) {
                    // Expected.
                }
            }
    }

    @Test
    fun `disable preempts runtime before persistence while enable waits for commit`() {
        assertTrue(shouldPreemptRuntimeBeforePersisting(enabled = false))
        assertFalse(shouldPreemptRuntimeBeforePersisting(enabled = true))
    }

    @Test
    fun `deletion recovery cannot overtake a newer runtime intent`() {
        assertTrue(
            isRuntimeOperationCurrent(
                currentOperation = 1L,
                currentGeneration = 1L,
                operation = 1L,
            )
        )
        assertFalse(
            isRuntimeOperationCurrent(
                currentOperation = 2L,
                currentGeneration = 2L,
                operation = 1L,
            )
        )
        assertFalse(
            isRuntimeOperationCurrent(
                currentOperation = 2L,
                currentGeneration = 1L,
                operation = 1L,
            )
        )
    }

    @Test
    fun `bounded log buffer trims old chunks without changing recent output`() {
        val buffer = BoundedLogBuffer(12)

        buffer.append("first")
        buffer.append("second")
        buffer.append("third")

        assertEquals("second\nthird", buffer.snapshot())
    }

    @Test
    fun `restart policy rejects disabled exhausted and manual-only services`() {
        assertTrue(shouldRestartTerminalService(true, true, 0, 3))
        assertFalse(shouldRestartTerminalService(false, true, 0, 3))
        assertFalse(shouldRestartTerminalService(true, false, 0, 3))
        assertFalse(shouldRestartTerminalService(true, true, 3, 3))
    }

    @Test
    fun `blocking endpoint probes return within their hard timeout`() {
        val release = CountDownLatch(1)
        val startedAt = System.nanoTime()

        val result = runBlockingProbeWithTimeout(UUID.randomUUID().toString(), 50L) {
            release.await()
            true
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        release.countDown()

        assertNull(result)
        assertTrue("probe took ${elapsedMs}ms", elapsedMs < 1_000L)
    }

    @Test
    fun `long waiter reuses short waiter's endpoint probe without shortening its work`() {
        val key = UUID.randomUUID().toString()
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val executions = AtomicInteger()
        var shortResult: Boolean? = true
        val shortWaiter = Thread {
            shortResult = runBlockingProbeWithTimeout(key, 20L) {
                executions.incrementAndGet()
                probeStarted.countDown()
                releaseProbe.await()
                true
            }
        }
        shortWaiter.start()
        probeStarted.await()

        val releaseThread = Thread {
            Thread.sleep(80L)
            releaseProbe.countDown()
        }.apply { start() }
        val longResult = runBlockingProbeWithTimeout(key, 500L) {
            executions.incrementAndGet()
            false
        }
        shortWaiter.join()
        releaseThread.join()

        assertNull(shortResult)
        assertEquals(true, longResult)
        assertEquals(1, executions.get())
    }

    @Test
    fun `completed probe is not reused by a later health-check attempt`() {
        val key = UUID.randomUUID().toString()
        val probeStarted = CountDownLatch(1)
        val releaseProbe = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val executions = AtomicInteger()

        val firstResult = runBlockingProbeWithTimeout(key, 20L) {
            executions.incrementAndGet()
            probeStarted.countDown()
            releaseProbe.await()
            firstFinished.countDown()
            true
        }
        assertNull(firstResult)
        probeStarted.await()
        releaseProbe.countDown()
        firstFinished.await()
        val cleanupDeadline = System.nanoTime() + 1_000_000_000L
        while (hasActiveTcpProbe(key) && System.nanoTime() < cleanupDeadline) {
            Thread.yield()
        }
        assertFalse(hasActiveTcpProbe(key))

        val secondResult = runBlockingProbeWithTimeout(key, 500L) {
            executions.incrementAndGet()
            false
        }

        assertFalse(secondResult ?: true)
        assertEquals(2, executions.get())
    }

    @Test
    fun `failed persisted deletion restores a stopped runtime`() = runBlocking {
        var restored = false

        try {
            stopRuntimeThenDeletePersisted(
                stopRuntime = { true },
                deletePersisted = { throw IllegalStateException("persist failed") },
                restoreRuntime = { restored = true; true },
                terminationFailure = { IllegalStateException("stop failed") },
            )
            fail("Expected persistence failure")
        } catch (expected: IllegalStateException) {
            assertEquals("persist failed", expected.message)
        }

        assertTrue(restored)
    }

    @Test
    fun `failed persisted deletion waits for and reports failed runtime restoration`() = runBlocking {
        var restorationFinished = false

        try {
            stopRuntimeThenDeletePersisted(
                stopRuntime = { true },
                deletePersisted = { throw IllegalStateException("persist failed") },
                restoreRuntime = {
                    restorationFinished = true
                    false
                },
                terminationFailure = { IllegalStateException("stop failed") },
            )
            fail("Expected restoration failure")
        } catch (expected: TerminalStartupRuntimeRestoreException) {
            val rootCause = generateSequence<Throwable>(expected) { it.cause }.last()
            assertEquals("persist failed", rootCause.message)
        }

        assertTrue(restorationFinished)
    }

    @Test
    fun `failed runtime termination keeps persisted service`() = runBlocking {
        var deleted = false
        try {
            stopRuntimeThenDeletePersisted(
                stopRuntime = { false },
                deletePersisted = { deleted = true },
                restoreRuntime = { true },
                terminationFailure = { IllegalStateException("stop failed") },
            )
            fail("Expected termination failure")
        } catch (expected: IllegalStateException) {
            assertEquals("stop failed", expected.message)
        }
        assertFalse(deleted)
    }

    @Test
    fun `cancelling an entered deletion still completes stop and persistence`() = runBlocking {
        val deleteEntered = CompletableDeferred<Unit>()
        val releaseDelete = CompletableDeferred<Unit>()
        var deleted = false
        val job = launch {
            stopRuntimeThenDeletePersisted(
                stopRuntime = { true },
                deletePersisted = {
                    deleteEntered.complete(Unit)
                    releaseDelete.await()
                    deleted = true
                },
                restoreRuntime = { true },
                terminationFailure = { IllegalStateException("stop failed") },
            )
        }

        deleteEntered.await()
        job.cancel()
        releaseDelete.complete(Unit)
        job.join()

        assertTrue(deleted)
    }

    @Test
    fun `cancelling an entered plain commit still publishes persisted state`() = runBlocking {
        val persistEntered = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        var published = false
        val job = launch {
            persistAndPublish(
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

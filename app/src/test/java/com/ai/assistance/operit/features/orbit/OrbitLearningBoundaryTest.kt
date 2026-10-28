package com.ai.assistance.operit.features.orbit

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrbitLearningBoundaryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `correction survives restart and rejects a learner holding old evidence`() = runBlocking {
        val file = File(folder.root, "a/boundary.json")
        val boundary = OrbitLearningBoundary(file) { 42 }
        val old = boundary.snapshot()
        boundary.edit("correction", old.generation) {}
        val restored = OrbitLearningBoundary(file)
        assertEquals(OrbitLearningBoundary.Guard(1, 42), restored.snapshot())
        var wrote = false
        assertTrue(runCatching { restored.learn(old.generation) { wrote = true } }.isFailure)
        assertFalse(wrote)
        restored.learn(1) { wrote = true }
        assertTrue(wrote)
    }

    @Test fun `interrupted edit retry keeps original cutoff and older mutations cannot replay`() = runBlocking {
        var watermark = 10L
        val boundary = OrbitLearningBoundary(File(folder.root, "boundary.json")) { watermark }
        assertTrue(runCatching { boundary.edit("a", 0) { error("interrupted") } }.isFailure)
        watermark = 20
        boundary.edit("a", 0) {}
        assertEquals(OrbitLearningBoundary.Guard(1, 10), boundary.snapshot())
        boundary.edit("b", 1) {}
        assertFalse(boundary.accepts(0, "a"))
        assertTrue(runCatching { boundary.edit("a", 0) {} }.isFailure)
        assertEquals(OrbitLearningBoundary.Guard(2, 20), boundary.snapshot())
    }

    @Test fun `correction waits for an ongoing commit then wins without blocking another Orbit`() = runBlocking {
        val a = OrbitLearningBoundary(File(folder.root, "a.json")) { 7 }
        val b = OrbitLearningBoundary(File(folder.root, "b.json"))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var content = ""
        val learning = async { a.learn(0) { started.complete(Unit); release.await(); content = "old" } }
        started.await()
        val correction = async { a.edit("edit", 0) { content = "new" } }
        b.learn(0) {}
        release.complete(Unit)
        learning.await(); correction.await()
        assertEquals("new", content)
        assertEquals(0L, b.snapshot().generation)
    }

    @Test fun `damaged governance journal cannot silently reopen old evidence`() = runBlocking {
        val file = File(folder.root, "broken.json").apply { writeText("{") }
        assertTrue(runCatching { OrbitLearningBoundary(file).snapshot() }.isFailure)
    }
}

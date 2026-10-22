package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Test

class OrbitBrowserControlTest {
    @Test fun `human ownership rejects AI and fences delayed callbacks even after release`() {
        var old: Long? = null
        OrbitBrowserControl.aiCall { old = OrbitBrowserControl.token() }
        OrbitBrowserControl.acquire("task")
        try {
            assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.aiCall { error("Must not execute") } }
            assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.check(old) }
        } finally { OrbitBrowserControl.release("task") }
        assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.check(old) }
        OrbitBrowserControl.aiCall { OrbitBrowserControl.check(OrbitBrowserControl.token()) }
    }

    @Test fun `handoff waits for in-flight call and failure releases call count`() {
        assertThrows(IllegalArgumentException::class.java) {
            OrbitBrowserControl.aiCall {
                assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.acquire("task") }
                throw IllegalArgumentException("test")
            }
        }
        OrbitBrowserControl.acquire("task")
        try { assertTrue(OrbitBrowserControl.isOwner("task")) }
        finally { OrbitBrowserControl.release("task") }
    }

    @Test fun `watch takeover makes AI calls wait until the user hands back`() {
        assertTrue(OrbitBrowserControl.takeOver("watch:t"))
        assertFalse(OrbitBrowserControl.takeOver("watch:other"))
        val ran = java.util.concurrent.CountDownLatch(1)
        val worker = Thread { OrbitBrowserControl.aiCall { ran.countDown() } }.apply { start() }
        try {
            assertFalse(ran.await(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally {
            OrbitBrowserControl.handBack("watch:t", "t", null)
        }
        assertTrue(ran.await(5, java.util.concurrent.TimeUnit.SECONDS))
        worker.join()
        assertTrue(OrbitBrowserControl.idle())
    }

    @Test fun `hand back note goes once to the next call of that chat only`() {
        assertTrue(OrbitBrowserControl.takeOver("watch:t"))
        OrbitBrowserControl.handBack("watch:t", "t", "look again")
        // A call for no Orbit chat runs normally and leaves the note for its chat.
        OrbitBrowserControl.aiCall { }
        val error = assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.beginAi("t") }
        assertEquals("look again", error.message)
        assertTrue(OrbitBrowserControl.idle())
    }
}

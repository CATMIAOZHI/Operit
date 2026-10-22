package com.ai.assistance.operit.features.orbit

import org.junit.Assert.*
import org.junit.Test

class OrbitBrowserControlTest {
    @Test fun `independent tasks overlap while same task queues`() {
        // These executor IDs deliberately collide: distinct tasks must never share a queue.
        val taskA = "Aa"
        val taskB = "BB"
        assertEquals(taskA.hashCode(), taskB.hashCode())
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val sameTask = java.util.concurrent.CountDownLatch(1)
        val first = Thread {
            OrbitBrowserControl.callForTask(taskA) {
                started.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val second = Thread { OrbitBrowserControl.callForTask(taskA) { sameTask.countDown() } }.apply { start() }
        try {
            OrbitBrowserControl.callForTask(taskB) {
                assertFalse(OrbitBrowserControl.idle(taskA))
                assertEquals(taskB, com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserTaskScope.current())
            }
            assertFalse(sameTask.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        first.join(5_000)
        second.join(5_000)
        assertEquals(0, sameTask.count.toInt())
        assertTrue(OrbitBrowserControl.idle())
    }

    @Test fun `taking one task leaves another task running and fences only its own callbacks`() {
        assertThrows(IllegalStateException::class.java) {
        OrbitBrowserControl.callForTask("takeover-a") {
            val old = OrbitBrowserControl.token()
            assertTrue(OrbitBrowserControl.takeOver("watch:takeover-a"))
            try {
                OrbitBrowserControl.callForTask("takeover-b") {
                    OrbitBrowserControl.check(OrbitBrowserControl.token())
                }
                assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.check(old) }
            } finally { OrbitBrowserControl.handBack("watch:takeover-a", "takeover-a", null) }
            // The original action remains invalid even after handing back.
            assertThrows(IllegalStateException::class.java) { OrbitBrowserControl.check(old) }
        }
        }
    }

    @Test fun `failed task reservation releases its action and scope`() {
        assertThrows(IllegalStateException::class.java) {
            OrbitBrowserControl.callForTask("full", begin = { error("3 tasks occupied") }) {
                fail("No page action should execute")
            }
        }
        assertTrue(OrbitBrowserControl.idle())
        assertNull(com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserTaskScope.current())
    }

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

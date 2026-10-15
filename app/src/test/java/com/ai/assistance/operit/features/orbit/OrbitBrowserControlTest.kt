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
}

package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserTaskScopeTest {
    @Test fun `shared login never shares task pages`() {
        assertTrue(BrowserTaskScope.canAccess("main", "a", "main", "a"))
        assertFalse(BrowserTaskScope.canAccess("main", "a", "main", "b"))
        assertFalse(BrowserTaskScope.canAccess("main", "a", "main", null))
        assertFalse(BrowserTaskScope.canAccess("main", null, "main", "a"))
        assertTrue(BrowserTaskScope.canAccess("main", null, "main", null))
        assertFalse(BrowserTaskScope.canAccess("orbit", "a", "main", "a"))
        assertTrue(BrowserTaskScope.canAccess(null, null, "orbit", "a"))
    }

    @Test fun `nested scope and failure restore previous task`() {
        BrowserTaskScope.withScope("a") {
            assertThrows(IllegalStateException::class.java) {
                BrowserTaskScope.withScope("b") { check(BrowserTaskScope.current() == "a") }
            }
            assertEquals("a", BrowserTaskScope.current())
        }
        assertNull(BrowserTaskScope.current())
    }
}

package com.ai.assistance.operit.data.mcp.plugins

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MCPStarterPolicyTest {
    @Test
    fun `empty installation does not require terminal or bridge startup`() {
        assertFalse(requiresMcpRuntimeInitialization(installedPluginCount = 0))
    }

    @Test
    fun `installed plugins require runtime initialization even when they may all be disabled`() {
        assertTrue(requiresMcpRuntimeInitialization(installedPluginCount = 1))
    }
}

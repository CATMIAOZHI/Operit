package com.ai.assistance.operit.ui.features.startup.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLoadingPolicyTest {
    @Test
    fun `MCP runtime still initializes when no plugin remains enabled`() {
        assertTrue(shouldInitializeMcpRuntime(enabledPluginCount = 0, pluginDiscoveryError = null))
    }

    @Test
    fun `MCP runtime does not initialize from a failed discovery snapshot`() {
        assertFalse(
            shouldInitializeMcpRuntime(
                enabledPluginCount = 0,
                pluginDiscoveryError = IllegalStateException("discovery failed"),
            )
        )
    }

    @Test
    fun `terminal configuration failure does not block independent MCP cleanup`() {
        assertTrue(
            shouldInitializeMcpRuntime(
                enabledPluginCount = 0,
                pluginDiscoveryError = null,
                terminalConfigError = IllegalStateException("terminal config failed"),
            )
        )
    }
}

package com.ai.assistance.operit.ui.features.startup.screens

import com.ai.assistance.operit.data.mcp.plugins.MCPStarter
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

    @Test
    fun `only app boot starts terminal startup services`() {
        assertTrue(shouldStartTerminalServices(PluginStartupScope.APP_BOOT))
        assertFalse(shouldStartTerminalServices(PluginStartupScope.MCP_ONLY))
    }

    @Test
    fun `actionable MCP initialization failures are not replaced by generic summary`() {
        assertFalse(
            shouldReplaceStartupMessageWithSummary(
                MCPStarter.PluginInitStatus.TERMINAL_SERVICE_UNAVAILABLE
            )
        )
        assertFalse(
            shouldReplaceStartupMessageWithSummary(MCPStarter.PluginInitStatus.NODEJS_MISSING)
        )
        assertFalse(
            shouldReplaceStartupMessageWithSummary(MCPStarter.PluginInitStatus.BRIDGE_FAILED)
        )
        assertFalse(
            shouldReplaceStartupMessageWithSummary(MCPStarter.PluginInitStatus.OTHER_ERROR)
        )
        assertTrue(
            shouldReplaceStartupMessageWithSummary(MCPStarter.PluginInitStatus.SUCCESS)
        )
    }
}

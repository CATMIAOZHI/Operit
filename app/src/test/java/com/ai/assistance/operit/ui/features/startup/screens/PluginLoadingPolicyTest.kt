package com.ai.assistance.operit.ui.features.startup.screens

import com.ai.assistance.operit.data.mcp.plugins.MCPStarter
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupLaunchMode
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginLoadingPolicyTest {
    @Test
    fun `reset cannot cancel an active shared initialization`() {
        val guard = PluginInitializationGuard()
        var reset = false

        val lease = requireNotNull(guard.tryStart())
        assertFalse(guard.resetIfIdle { reset = true })
        assertFalse(reset)
        guard.finish(lease)
        assertTrue(guard.resetIfIdle { reset = true })
        assertTrue(reset)
    }

    @Test
    fun `an old completion cannot release a newer initialization lease`() {
        val guard = PluginInitializationGuard()
        val first = requireNotNull(guard.tryStart())
        guard.finish(first)
        val second = requireNotNull(guard.tryStart())

        guard.finish(first)

        assertEquals(null, guard.tryStart())
        guard.finish(second)
        assertTrue(guard.tryStart() != null)
    }

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
    fun `loading timeout covers the configured terminal retry budget`() {
        assertEquals(30_000L, combinedStartupLoadingTimeoutMs(emptyList()))
        val service = TerminalStartupServiceConfig(
            id = "slow",
            name = "slow",
            launchMode = TerminalStartupLaunchMode.COMMAND,
            command = "sleep 1",
            startupTimeoutMs = 300_000L,
            autoRestart = false,
            maxRestartAttempts = 3,
        )

        assertEquals(338_000L, combinedStartupLoadingTimeoutMs(listOf(service)))
        assertEquals(
            264_000L,
            combinedStartupLoadingTimeoutMs(
                listOf(
                    service.copy(
                        startupTimeoutMs = 30_000L,
                        autoRestart = true,
                        maxRestartAttempts = 3,
                    )
                )
            ),
        )
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

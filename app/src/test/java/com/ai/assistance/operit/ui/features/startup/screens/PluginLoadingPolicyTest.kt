package com.ai.assistance.operit.ui.features.startup.screens

import com.ai.assistance.operit.data.mcp.plugins.MCPStarter
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupLaunchMode
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginLoadingPolicyTest {
    @Test
    fun `registry teardown only clears the exact Activity binding`() {
        val state = PluginLoadingState()
        val first = PluginLoadingStateRegistry.bind(state, TestScope())
        val second = PluginLoadingStateRegistry.bind(state, TestScope())

        assertFalse(PluginLoadingStateRegistry.unbind(first))
        assertTrue(PluginLoadingStateRegistry.isActive(second))
        assertEquals(state, PluginLoadingStateRegistry.getActiveBinding()?.state)
        assertTrue(PluginLoadingStateRegistry.unbind(second))
        assertEquals(null, PluginLoadingStateRegistry.getActiveBinding())
    }

    @Test
    fun `completed startup cancels its armed loading timeout`() = runTest {
        val state = PluginLoadingState()
        val owner = state.startTimeoutCheck(timeoutMillis = 1_000L, scope = this)

        state.cancelTimeoutCheck(owner)
        advanceUntilIdle()

        assertFalse(state.hasTimedOut.value)
    }

    @Test
    fun `old timeout completion and cleanup cannot affect a newer owner`() = runTest {
        val state = PluginLoadingState()
        val oldOwner = state.startTimeoutCheck(timeoutMillis = 1_000L, scope = this)
        advanceTimeBy(1_000L)

        state.startTimeoutCheck(timeoutMillis = 2_000L, scope = this)
        state.cancelTimeoutCheck(oldOwner)
        runCurrent()
        assertFalse(state.hasTimedOut.value)

        advanceTimeBy(2_000L)
        runCurrent()
        assertTrue(state.hasTimedOut.value)
    }

    @Test
    fun `cleanup without an owned timeout cannot cancel another initialization timeout`() = runTest {
        val state = PluginLoadingState()
        state.startTimeoutCheck(timeoutMillis = 1_000L, scope = this)

        state.cancelTimeoutCheckIfOwned(null)
        advanceUntilIdle()

        assertTrue(state.hasTimedOut.value)
    }

    @Test
    fun `final result retires its timeout before publishing the final message`() = runTest {
        val state = PluginLoadingState()
        val owner = state.startTimeoutCheck(timeoutMillis = 1_000L, scope = this)
        advanceTimeBy(1_000L)

        state.cancelTimeoutCheck(owner)
        state.updateMessage("final result")
        runCurrent()

        assertFalse(state.hasTimedOut.value)
        assertEquals("final result", state.message.value)
    }

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
    fun `reserved app boot initialization rejects a concurrent restart lease`() {
        val state = PluginLoadingState()
        requireNotNull(state.reserveInitialization())

        assertFalse(state.reset())
        assertEquals(null, state.reserveInitialization())
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
    fun `partial snapshots are available only while their initialization lease is active`() {
        val guard = PluginInitializationGuard()
        val lease = requireNotNull(guard.tryStart())

        assertEquals("current batch", guard.withActive(lease) { "current batch" })
        guard.finish(lease)
        assertEquals(null, guard.withActive(lease) { "stale batch" })
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

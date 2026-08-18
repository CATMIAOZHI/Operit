package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardSoftwareSettingsModifyToolsPolicyTest {
    @Test
    fun `batch-level MCP failure fails even when no plugin item failed`() {
        assertFalse(
            isRequestedMcpRestartSuccessful(
                timedOut = false,
                batchSuccessful = false,
                failedCount = 0,
            )
        )
    }

    @Test
    fun `MCP restart succeeds only for a completed clean batch`() {
        assertTrue(
            isRequestedMcpRestartSuccessful(
                timedOut = false,
                batchSuccessful = true,
                failedCount = 0,
            )
        )
        assertFalse(isRequestedMcpRestartSuccessful(true, true, 0))
        assertFalse(isRequestedMcpRestartSuccessful(false, true, 1))
        assertFalse(isRequestedMcpRestartSuccessful(false, null, 0))
    }
}

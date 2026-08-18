package com.ai.assistance.operit.data.mcp.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MCPBridgePolicyTest {
    @Test
    fun `port selection prefers the direct bridge port before the SSH forwarded port`() {
        assertEquals(
            8752,
            selectBridgePort(
                primaryAvailable = lazyOf(true),
                fallbackAvailable = lazyOf(true),
                primaryPort = 8752,
                fallbackPort = 8751,
            ),
        )
        assertEquals(
            8752,
            selectBridgePort(
                primaryAvailable = lazyOf(true),
                fallbackAvailable = lazyOf(false),
                primaryPort = 8752,
                fallbackPort = 8751,
            ),
        )
        assertEquals(
            8751,
            selectBridgePort(
                primaryAvailable = lazyOf(false),
                fallbackAvailable = lazyOf(true),
                primaryPort = 8752,
                fallbackPort = 8751,
            ),
        )
    }

    @Test
    fun `port selection short circuits the fallback probe when the primary port is available`() {
        assertEquals(
            8752,
            selectBridgePort(
                primaryAvailable = lazyOf(true),
                fallbackAvailable =
                    lazy { error("fallback must not be probed when the primary port is available") },
                primaryPort = 8752,
                fallbackPort = 8751,
            ),
        )
    }

    @Test
    fun `port selection reports no listener when both ports are closed`() {
        assertNull(
            selectBridgePort(
                primaryAvailable = lazyOf(false),
                fallbackAvailable = lazyOf(false),
                primaryPort = 8752,
                fallbackPort = 8751,
            ),
        )
    }
}

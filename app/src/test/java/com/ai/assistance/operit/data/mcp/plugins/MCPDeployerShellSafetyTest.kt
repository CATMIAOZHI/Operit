package com.ai.assistance.operit.data.mcp.plugins

import org.junit.Assert.assertEquals
import org.junit.Test

class MCPDeployerShellSafetyTest {

    @Test
    fun `shell path arguments are quoted as one literal argument`() {
        assertEquals("'/home/user/mcp/plugin'", quotePosixShellArgument("/home/user/mcp/plugin"))
        assertEquals(
            "'/home/user/mcp/it'\"'\"'s plugin; touch escaped'",
            quotePosixShellArgument("/home/user/mcp/it's plugin; touch escaped"),
        )
        assertEquals(
            "\"\$HOME\"/'mcp_plugins/plugin'",
            quotePosixShellPath("~/mcp_plugins/plugin"),
        )
        assertEquals(
            "\"\$HOME\"/'mcp_plugins/it'\"'\"'s plugin; touch escaped'",
            quotePosixShellPath("~/mcp_plugins/it's plugin; touch escaped"),
        )
    }
}

package com.ai.assistance.operit.data.mcp.plugins

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `zero-installed cleanup fails closed when bridge reset is unverifiable`() {
        assertFalse(didMcpBridgeCleanupSucceed(null))
        assertFalse(didMcpBridgeCleanupSucceed(JSONObject().put("success", false)))
        assertFalse(didMcpBridgeCleanupSucceed(JSONObject().put("success", "true")))
        assertFalse(didMcpBridgeCleanupSucceed(JSONObject().put("success", 1)))
        assertFalse(didMcpBridgeCleanupSucceed(JSONObject().put("success", JSONObject.NULL)))
        assertTrue(didMcpBridgeCleanupSucceed(JSONObject().put("success", true)))
    }

    @Test
    fun `disabled service cleanup fails closed when unregister is unverifiable`() {
        val registeredServices = mutableSetOf("shared")
        assertFalse(
            recordMcpServiceUnregisterResult(
                registeredServices,
                "shared",
                null,
            )
        )
        assertTrue(registeredServices.contains("shared"))
        assertFalse(
            recordMcpServiceUnregisterResult(
                registeredServices,
                "shared",
                JSONObject().put("success", "true"),
            )
        )
        assertTrue(registeredServices.contains("shared"))
        assertTrue(
            recordMcpServiceUnregisterResult(
                registeredServices,
                "shared",
                JSONObject().put("success", true),
            )
        )
        assertFalse(registeredServices.contains("shared"))
        assertTrue(
            mcpStartupStatusAfterDisabledCleanup(cleanupSucceeded = false) ==
                MCPStarter.PluginInitStatus.BRIDGE_FAILED
        )
        assertTrue(
            mcpStartupStatusAfterDisabledCleanup(cleanupSucceeded = true) ==
                MCPStarter.PluginInitStatus.SUCCESS
        )
    }

    @Test
    fun `registered service list must be structurally verifiable`() {
        val valid =
            JSONObject()
                .put("success", true)
                .put(
                    "result",
                    JSONObject().put(
                        "services",
                        JSONArray().put(JSONObject().put("name", "first")).put(JSONObject().put("name", "second")),
                    ),
                )
        assertEquals(listOf("first", "second"), decodeRegisteredMcpServiceNames(valid))
        assertEquals(
            emptyList<String>(),
            decodeRegisteredMcpServiceNames(
                JSONObject()
                    .put("success", true)
                    .put("result", JSONObject().put("services", JSONArray()))
            ),
        )
        assertNull(decodeRegisteredMcpServiceNames(null))
        assertNull(decodeRegisteredMcpServiceNames(JSONObject().put("success", false)))
        assertNull(decodeRegisteredMcpServiceNames(JSONObject().put("success", "true")))
        assertNull(decodeRegisteredMcpServiceNames(JSONObject().put("success", true)))
        assertNull(
            decodeRegisteredMcpServiceNames(
                JSONObject()
                    .put("success", true)
                    .put("result", JSONObject().put("services", JSONArray().put(JSONObject())))
            )
        )
        assertNull(
            decodeRegisteredMcpServiceNames(
                JSONObject()
                    .put("success", true)
                    .put("result", JSONObject().put("services", JSONArray().put(1)))
            )
        )
    }
}

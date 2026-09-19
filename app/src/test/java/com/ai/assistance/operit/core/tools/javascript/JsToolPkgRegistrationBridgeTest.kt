package com.ai.assistance.operit.core.tools.javascript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsToolPkgRegistrationBridgeTest {
    @Test
    fun `registration bridge declares versioned APIs beside their implementations`() {
        val bridge = buildToolPkgRegistrationBridgeScript()

        assertTrue(bridge.contains("toolPkgApi.namespace('ToolPkg'"))
        assertTrue(bridge.contains("toolPkgApi.method().since("))
        assertVersionedApi(bridge, "registerChatMessageMenuItem")
        assertVersionedApi(bridge, "registerChatRuntimeHook")
        assertFalse(bridge.contains("requiredApi"))
        assertFalse(bridge.contains("requiredFeature"))
        assertFalse(bridge.contains("registrationBindings"))
        assertFalse(bridge.contains("__operit_toolpkg_api_version"))
        assertFalse(bridge.contains("supportsToolPkgApiVersion"))
    }

    @Test
    fun `registration session captures registrations after facade dispatch`() {
        val session = JsToolPkgRegistrationSession()
        session.begin()

        session.appendChatRuntimeHook("{\"id\":\"runtime\",\"function\":\"onRuntime\"}")
        val capture = session.finish(null)

        assertEquals(1, capture.chatRuntimeHooks.size)
        session.end()
    }

    private fun assertVersionedApi(bridge: String, apiName: String) {
        val versionedDeclaration =
            Regex("$apiName:\\s*toolPkgApi\\.method\\(\\)\\.since\\(\\s*'1\\.0\\.1'")
        assertTrue(
            "$apiName must be declared through the 1.0.1 version gate",
            versionedDeclaration.containsMatchIn(bridge)
        )
    }
}

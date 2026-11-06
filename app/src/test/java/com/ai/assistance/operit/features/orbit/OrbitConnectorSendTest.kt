package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.ToolParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitConnectorSendTest {
    @Test
    fun publicParametersAreAccepted() {
        val parameters = listOf(ToolParameter("text", "hello"), ToolParameter("recipient", "123"))
        assertEquals(parameters, OrbitConnectorSend.publicParameters(parameters))
    }

    @Test
    fun proxyHostContextIsRemovedBeforeApproval() {
        val parameters = listOf(ToolParameter("text", "hello"), ToolParameter("recipient", "123"))
        val proxyParameters = parameters + listOf(
            ToolParameter("__operit_package_chat_id", "chat"),
            ToolParameter("__operit_package_caller_name", "Orbit"),
            ToolParameter("__operit_package_caller_card_id", "card"),
        )
        assertEquals(parameters, OrbitConnectorSend.publicParameters(proxyParameters))
    }

    @Test
    fun hiddenExecutableParametersAreRejected() {
        for (name in listOf("__operit_inline_function_source", "__operit_inline_function_name", "__operit_execution_context_key")) {
            val error = runCatching {
                OrbitConnectorSend.publicParameters(listOf(
                    ToolParameter("text", "hello"), ToolParameter("__operit_package_chat_id", "chat"), ToolParameter(name, "hidden"),
                ))
            }.exceptionOrNull()
            assertTrue("$name must be rejected", error is IllegalArgumentException)
        }
    }
}

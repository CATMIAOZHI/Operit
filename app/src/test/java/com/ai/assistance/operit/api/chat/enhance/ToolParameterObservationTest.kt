package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.ToolParameterObservation
import com.ai.assistance.operit.core.tools.mcp.mcpDeclaredParameterNames
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolParameterObservationTest {
    private fun tool(name: String, vararg values: Pair<String, String>) =
        AITool(name, values.map { ToolParameter(it.first, it.second) })

    private suspend fun <T> observing(observer: ToolParameterObservation, block: suspend () -> T): T =
        withContext(ToolExecutionManager.toolRuntimeContextElement(
            ToolExecutionManager.ToolRuntimeContext(parameterObserver = observer),
        )) { block() }

    @Test fun successfulExecutionReadsNewExecutorDeclarationOnEveryCall() = runBlocking {
        val call = tool("new_plugin:read", "path" to "/tmp/a", "startline" to "2")
        var declaration = setOf("path")
        val executor = object : ToolExecutor {
            override fun parameterNames(tool: AITool) = declaration
            override fun invoke(tool: AITool) = ToolResult(tool.name, true, StringResultData("OK"))
        }
        val first = ToolParameterObservation(call, call) { throw AssertionError(it) }
        val invocation = ToolInvocation(call, "", 0..0)
        assertTrue(observing(first) { ToolExecutionManager.executeToolSafely(invocation, executor).last() }.success)
        assertEquals(listOf("startline"), first.snapshot())
        declaration = setOf("path", "startline")
        val second = ToolParameterObservation(call, call) { throw AssertionError(it) }
        observing(second) { ToolExecutionManager.executeToolSafely(invocation, executor).last() }
        assertTrue(second.snapshot().isEmpty())
        assertEquals(listOf("startline"), first.snapshot())
    }

    @Test fun proxyAndTargetParametersAreObservedWithoutNestedCallPollution() = runBlocking {
        val proxy = tool("proxy", "tool_name" to "read_file", "params" to "{\"path\":\"x\",\"endline\":3}", "startline" to "2")
        val target = tool("read_file", "path" to "x", "endline" to "3")
        val observer = ToolParameterObservation(proxy, target) { throw AssertionError(it) }
        observing(observer) {
            ToolExecutionManager.observeToolParameterNames(proxy) { setOf("tool_name", "params") }
            ToolExecutionManager.observeToolParameterNames(target) { setOf("path") }
            ToolExecutionManager.observeToolParameterNames(tool("other:internal", "secret" to "value")) { emptySet() }
        }
        assertEquals(listOf("startline", "params.endline"), observer.snapshot())
    }

    @Test fun mcpUsesItsOwnCurrentSchemaAndHonorsDynamicProperties() = runBlocking {
        val call = tool("same_name:tool", "mcp_argument" to "yes")
        val observer = ToolParameterObservation(call, call) { throw AssertionError(it) }
        observing(observer) {
            ToolExecutionManager.observeToolParameterNames(call) {
                mcpDeclaredParameterNames(JSONObject("""{"properties":{"mcp_argument":{"type":"string"}}}"""))
            }
        }
        assertTrue(observer.snapshot().isEmpty())
        assertNull(mcpDeclaredParameterNames(JSONObject("""{"properties":{},"additionalProperties":true}""")))
        assertNull(mcpDeclaredParameterNames(JSONObject("""{"patternProperties":{"^x":{}}}""")))
    }

    @Test fun declarationFailureDoesNotPreventExecutionOrInventParameterErrors() = runBlocking {
        val call = tool("plugin:tool", "valid" to "x")
        var reported = false
        var invoked = false
        val observer = ToolParameterObservation(call, call) { reported = true }
        val executor = object : ToolExecutor {
            override fun parameterNames(tool: AITool): Set<String> = error("Metadata unavailable")
            override fun invoke(tool: AITool): ToolResult {
                invoked = true
                return ToolResult(tool.name, true, StringResultData("OK"))
            }
        }
        observing(observer) { ToolExecutionManager.executeToolSafely(ToolInvocation(call, "", 0..0), executor).last() }
        assertTrue(invoked)
        assertTrue(reported)
        assertTrue(observer.snapshot().isEmpty())
    }
}

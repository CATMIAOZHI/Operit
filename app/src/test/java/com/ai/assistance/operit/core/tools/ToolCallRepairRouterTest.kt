package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolCallRepairRouterTest {
    private fun call(name: String, vararg params: Pair<String, String>) =
        ToolInvocation(AITool(name, params.map { ToolParameter(it.first, it.second) }),
            rawText = "original provider response", responseLocation = 4..12,
            callId = "call-7", invocationIndex = 2)

    @Test fun lineReadPreservesValuesAndProviderIdentity() {
        val original = call("read_file", "path" to "/tmp/a.txt", "environment" to "linux",
            "start_line" to "20", "end_line" to "30")
        val repaired = ToolCallRepairRouter.route(original)!!.invocation
        assertEquals("read_file_part", repaired.tool.name)
        assertEquals(original.tool.parameters, repaired.tool.parameters)
        assertEquals(original, repaired.copy(tool = original.tool))
        assertNull(ToolCallRepairRouter.route(repaired))
    }

    @Test fun eitherLineBoundarySelectsLineReaderWithoutGuessingValues() {
        for (parameter in listOf("start_line", "end_line")) {
            val original = call("read_file", "path" to "/tmp/a", parameter to "7")
            assertEquals(original.tool.parameters,
                ToolCallRepairRouter.route(original)!!.invocation.tool.parameters)
        }
    }

    @Test fun ordinaryReadsAndOtherToolsAreUnchanged() {
        assertNull(ToolCallRepairRouter.route(call("read_file", "path" to "/tmp/a")))
        assertNull(ToolCallRepairRouter.route(call("read_file_part", "start_line" to "2")))
        assertNull(ToolCallRepairRouter.route(call("other:read_file", "start_line" to "2")))
    }

    @Test fun hiddenReadKeepsProxyEnvelopeAndExactParamsText() {
        val raw = """{ "path": "/tmp/a", "environment":"linux", "start_line":2 }"""
        val original = call("proxy", "tool_name" to "read_file", "params" to raw)
        val repaired = ToolCallRepairRouter.route(original)!!.invocation
        assertEquals("proxy", repaired.tool.name)
        assertEquals("read_file_part", repaired.tool.parameters.first().value)
        assertEquals(raw, repaired.tool.parameters.last().value)
        assertEquals(original.callId, repaired.callId)
    }

    @Test fun malformedAndAmbiguousProxyCallsAreNotRepaired() {
        assertNull(ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "read_file", "params" to "not json")))
        assertNull(ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "read_file", "tool_name" to "edit_file", "params" to """{"start_line":2}""")))
        assertNull(ToolCallRepairRouter.route(call("proxy",
            "tool_name" to "read_file")))
    }

    @Test fun repairLogDoesNotContainArgumentValuesOrRawResponse() {
        val original = call("read_file", "path" to "/secret/file", "start_line" to "42")
        val text = ToolCallRepairLogger.entry(ToolCallRepairRouter.route(original)!!, 123)
        val record = JSONObject(text)
        assertEquals("read_file", record.getString("originalToolName"))
        assertEquals("read_file_part", record.getString("targetToolName"))
        assertEquals("call-7", record.getString("callId"))
        assertEquals(ToolCallRepairRouter.READ_FILE_LINE_RANGE, record.getString("rule"))
        assertFalse(text.contains("/secret/file"))
        assertFalse(text.contains(original.rawText))
    }

    @Test fun repairedParametersAreCheckedAgainstActualToolDeclaration() {
        val original = call("read_file", "path" to "/tmp/a", "start_line" to "2", "unexpected" to "x")
        val routed = ToolCallRepairRouter.route(original)!!.invocation.tool
        val observer = ToolParameterObservation(routed, routed) { throw AssertionError(it) }
        observer.inspect(routed) { setOf("path", "environment", "start_line", "end_line") }
        assertEquals(listOf("unexpected"), observer.snapshot())
    }

    @Test fun terminalTimeoutAliasPreservesValueAndDoesNotAffectShell() {
        val original = call("super_admin:terminal", "command" to "sleep 120", "timeout" to "180000")
        val repair = ToolCallRepairRouter.route(original)!!
        assertEquals("180000", repair.invocation.tool.parameters.single { it.name == "timeoutMs" }.value)
        assertEquals(original.rawText, repair.invocation.rawText)
        assertNull(ToolCallRepairRouter.route(repair.invocation))
        assertNull(ToolCallRepairRouter.route(call("super_admin:shell", "timeout" to "180000")))
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal", "timeout" to "180seconds")))
    }

    @Test fun equalTimeoutAliasesAreDeduplicatedAndConflictsAreLeftAlone() {
        val same = call("super_admin:terminal", "timeout" to "180000", "timeoutMs" to "180000")
        assertEquals(listOf(ToolParameter("timeoutMs", "180000")),
            ToolCallRepairRouter.route(same)!!.invocation.tool.parameters)
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal",
            "timeout" to "180000", "timeoutMs" to "15000")))
        assertNull(ToolCallRepairRouter.route(call("super_admin:terminal",
            "timeout" to "180000", "timeout" to "30000")))
        for (value in listOf("0", "-1", "2999", "2147483648", "null", "true", "3000.5")) {
            assertNull(value, ToolCallRepairRouter.route(call("super_admin:terminal", "timeout" to value)))
        }
    }

    @Test fun composedRulesPreserveProxyTypesAndAreLoggedTogether() {
        val original = call("proxy", "tool_name" to "super_admin::terminal",
            "package_name" to "super_admin",
            "params" to """{"command":"echo \"test\"","timeout":180000,"background":false}""")
        val repair = ToolCallRepairRouter.route(original)!!
        assertEquals(listOf(ToolCallRepairRouter.TERMINAL_SEPARATOR,
            ToolCallRepairRouter.REDUNDANT_PACKAGE_NAME, ToolCallRepairRouter.TERMINAL_TIMEOUT), repair.rules)
        assertEquals("proxy", repair.invocation.tool.name)
        assertFalse(repair.invocation.tool.parameters.any { it.name == "package_name" })
        val args = JSONObject(repair.invocation.tool.parameters.single { it.name == "params" }.value)
        assertEquals("echo \"test\"", args.getString("command"))
        assertTrue(args.get("timeoutMs") is Number)
        assertEquals(180000, args.getInt("timeoutMs"))
        assertEquals(false, args.get("background"))
        assertNull(ToolCallRepairRouter.route(repair.invocation))
        val log = JSONObject(ToolCallRepairLogger.entry(repair, 123))
        assertEquals(3, log.getJSONArray("rules").length())
        assertEquals("super_admin::terminal", log.getString("originalToolName"))
        assertEquals("super_admin:terminal", log.getString("targetToolName"))
        assertFalse(log.toString().contains("echo"))
    }

    @Test fun redundantPackageIsRemovedOnlyWhenExactlyMatching() {
        for (proxy in listOf("proxy", "package_proxy")) {
            val original = call(proxy, "tool_name" to "reading_companion:get_local_files",
                "package_name" to "reading_companion", "params" to "{ }")
            val repair = ToolCallRepairRouter.route(original)!!
            assertEquals(proxy, repair.invocation.tool.name)
            assertEquals("{ }", repair.invocation.tool.parameters.single { it.name == "params" }.value)
            assertNull(ToolCallRepairRouter.route(call(proxy,
                "tool_name" to "reading_companion:get_local_files",
                "package_name" to "another_package", "params" to "{}")))
        }
        assertNull(ToolCallRepairRouter.route(call("package_proxy",
            "tool_name" to "read_file", "params" to """{"start_line":1}""")))
    }

    @Test fun separatorRuleIsAnExactAllowlist() {
        assertEquals("super_admin:terminal",
            ToolCallRepairRouter.route(call("super_admin::terminal"))!!.invocation.tool.name)
        for (name in listOf("super_admin::shell", "another::terminal", "super_admin:::terminal")) {
            assertNull(ToolCallRepairRouter.route(call(name)))
        }
    }

    @Test fun brokenOrDuplicateArgumentJsonCannotBeRewritten() {
        for (raw in listOf(
            """{"timeout":3000,"timeout":4000}""",
            """{"timeout":3000,"timeoutMs":3000,"timeoutMs":4000}""",
            """{'timeout':3000}""", """{"timeout":3000,}""", """{"timeout":3000} junk""",
            """{"timeout":3000,"background":TRUE}""",
            """{"timeout":3000,"command":unquoted}""",
            """{"timeout":3000,"extra":{"x":unquoted,"x":1}}""",
            """{"timeout":3000,"extra":[{"x":1,"x":2}]}""",
            "{\"timeout\":3000,\"command\":\"raw\ttab\"}",
            "{\"timeout\":3000,\"command\":\"raw\nnewline\"}",
            "{\"timeout\":3000,\"command\":\"raw\u0001control\"}",
        )) {
            assertNull(raw, ToolCallRepairRouter.route(call("proxy",
                "tool_name" to "super_admin:terminal", "params" to raw)))
        }
    }

    @Test fun ruleIdsAreUnique() {
        val ids = ToolCallRepairRules.ordered.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test fun renamingDoesNotRoundOtherJsonNumbers() {
        val original = call("proxy", "tool_name" to "super_admin:terminal",
            "params" to """{"timeout":3000,"command":"echo\nok","extra":{"large":123456789012345678901234567890,"decimal":0.12345678901234567890123456789}}""")
        val routed = ToolCallRepairRouter.route(original)!!.invocation
        val text = routed.tool.parameters.single { it.name == "params" }.value
        assertTrue(text.contains("123456789012345678901234567890"))
        assertTrue(text.contains("0.12345678901234567890123456789"))
        assertEquals("echo\nok", JSONObject(text).getString("command"))
    }
}

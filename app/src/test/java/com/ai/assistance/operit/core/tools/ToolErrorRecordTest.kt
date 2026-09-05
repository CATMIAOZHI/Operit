package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.core.config.SystemToolPrompts
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolErrorRecordTest {
    private fun declared(toolName: String) = SystemToolPrompts.fileSystemTools.tools
        .single { it.name == toolName }.parametersStructured!!.map { it.name }.toSet()

    private fun invocation(name: String, vararg parameters: Pair<String, String>) =
        ToolInvocation(AITool(name, parameters.map { ToolParameter(it.first, it.second) }),
            rawText = "not stored", responseLocation = 0..0, callId = "call-1", invocationIndex = 3)

    @Test fun successfulReadFileWithIgnoredLineParametersIsRecorded() {
        val call = invocation("read_file", "path" to "/tmp/test.txt", "startline" to "10", "endline" to "20")
        val unknown = undeclaredToolParameters(call.tool, declared("read_file"))
        val record = createToolErrorRecord("batch:call", call,
            ToolResult("read_file", true, StringResultData("File contents")), 100, unknown)!!
        assertEquals(listOf("startline", "endline"), record.undeclaredParameters)
        assertFalse(record.executionFailed)
        assertEquals(call.tool.parameters, record.parameters)
        assertTrue(record.error.contains("startline"))
    }

    @Test fun validLineRangeOnReadFilePartDoesNotCreateAnIssue() {
        val call = invocation("read_file_part", "path" to "/tmp/test.txt", "start_line" to "10", "end_line" to "20")
        assertNull(createToolErrorRecord("batch:call", call,
            ToolResult("read_file_part", true, StringResultData("Lines")), 100,
            undeclaredToolParameters(call.tool, declared("read_file_part"))))
    }

    @Test fun rawValuesAndDuplicateParameterNamesSurviveExport() {
        val call = invocation("read_file", "path" to "  A<&\"\\文件\n  ", "path" to "second")
        val record = createToolErrorRecord("batch:call", call,
            ToolResult("read_file", false, StringResultData(""), error = "Missing file\n详细错误"), 100)!!
        val decoded = Json.decodeFromString<ToolErrorRecord>(record.toJson())
        assertEquals(call.tool.parameters, decoded.parameters)
        assertEquals("Missing file\n详细错误", decoded.error)
        assertFalse(record.toJson().contains("not stored"))
        assertFalse(record.toJson().contains("call-1"))
    }

    @Test fun executionFailureAndParameterIssueShareOneRecord() {
        val call = invocation("read_file", "path" to "/missing", "startline" to "10")
        val record = createToolErrorRecord("one-record", call,
            ToolResult("read_file", false, StringResultData(""), error = "File not found"), 100,
            undeclaredToolParameters(call.tool, declared("read_file")))!!
        assertTrue(record.executionFailed)
        assertEquals(listOf("startline"), record.undeclaredParameters)
        assertTrue(record.error.contains("File not found"))
    }

    @Test fun unknownDeclarationDoesNotLabelEveryParameterAsInvalid() {
        assertTrue(undeclaredToolParameters(AITool("external", listOf(ToolParameter("custom", "value"))), null).isEmpty())
    }
}

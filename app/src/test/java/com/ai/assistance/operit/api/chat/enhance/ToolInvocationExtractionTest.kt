package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class ToolInvocationExtractionTest {
    @Test
    fun markdownExamples_doNotBecomeToolInvocations() = runBlocking {
        val content =
            """
            ```xml
            <tool name="fenced_example"><param name="path">/fake</param></tool>
            ```
            <think><tool name="nested_example"><param name="path">/nested</param></tool></think>
            <tool><param name="path">/malformed</param></tool>
            <tool name="read_file"><param name="path">/real</param></tool>
            <tool name="file_info"><param name="path">/second</param></tool>
            """.trimIndent()

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(content)
        }

        assertEquals(listOf("read_file", "file_info"), invocations.map { it.tool.name })
        assertEquals("/real", invocations[0].tool.parameters.single().value)
        assertEquals("/second", invocations[1].tool.parameters.single().value)
    }

    private suspend fun <T> withoutAndroidLogging(block: suspend () -> T): T {
        return Mockito.mockStatic(AppLogger::class.java).use {
            try {
                StreamLogger.setEnabled(false)
                block()
            } finally {
                StreamLogger.setEnabled(true)
            }
        }
    }
}

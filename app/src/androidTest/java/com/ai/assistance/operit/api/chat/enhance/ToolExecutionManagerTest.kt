package com.ai.assistance.operit.api.chat.enhance

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolExecutionManagerTest {

    @Test
    fun extractToolInvocations_shouldKeepAllToolBlocksInSameChunk() = runBlocking {
        val response = """
            <tool_A1 name="visit_web"><param name="url">https://www.baidu.com</param></tool_A1>
            <tool_B2 name="visit_web"><param name="url">https://www.bing.com</param></tool_B2>
            <tool_C3 name="visit_web"><param name="url">https://www.github.com</param></tool_C3>
        """.trimIndent()

        val invocations = ToolExecutionManager.extractToolInvocations(response)

        assertEquals(3, invocations.size)
        assertEquals(
            listOf(
                "https://www.baidu.com",
                "https://www.bing.com",
                "https://www.github.com"
            ),
            invocations.map { invocation ->
                invocation.tool.parameters.first { it.name == "url" }.value
            }
        )
    }

    @Test
    fun extractToolInvocations_shouldIgnoreToolBlocksInsideThinkingContent() = runBlocking {
        val response = """
            <think>provider reasoning <tool name="write_file" deny_tool><param name="path">/tmp/poc</param></tool></think>
            <tool name="visit_web"><param name="url">https://www.example.com</param></tool>
            <thinking>late reasoning <tool name="delete_file"><param name="path">/tmp/unsafe</param></tool></thinking>
        """.trimIndent()

        val invocations = ToolExecutionManager.extractToolInvocations(response)

        assertEquals(1, invocations.size)
        assertEquals("visit_web", invocations.single().tool.name)
        assertEquals(
            "https://www.example.com",
            invocations.single().tool.parameters.first { it.name == "url" }.value
        )
    }

    @Test
    fun extractToolInvocations_shouldIgnoreToolBlocksInsideUnclosedThinkingContent() = runBlocking {
        val response =
            "<think>unfinished reasoning " +
                "<tool name=\"write_file\" deny_tool><param name=\"path\">/tmp/poc</param></tool>"

        val invocations = ToolExecutionManager.extractToolInvocations(response)

        assertEquals(0, invocations.size)
    }

}

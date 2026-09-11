package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.api.chat.enhance.ConversationService
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.repository.CustomEmojiRepository
import com.ai.assistance.operit.core.tools.defaultTool.standard.CollaborationToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises native XML replay with in-memory fixtures; no chat or model calls. */
@RunWith(AndroidJUnit4::class)
class CollaborationHistoryReplayAndroidTest {
    @Test fun executorRejectsLegacyForkAndUnknownFieldsBeforeStartingAnyAgent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = CollaborationToolExecutor(context)
        val legacy = executor.invoke(AITool("spawn_agent", listOf(
            ToolParameter("task_name", "never_started"), ToolParameter("message", "fixture"),
            ToolParameter("fork_context", "false"),
        )))
        assertFalse(legacy.success)
        assertTrue(legacy.error.orEmpty().contains("use fork_turns"))
        val unknown = executor.invoke(AITool("wait_agent", listOf(ToolParameter("timeout", "1"))))
        assertFalse(unknown.success)
        assertTrue(unknown.error.orEmpty().contains("Unknown parameters"))
    }

    @Test fun replayPreservesAssistantOriginAndRealUserBoundary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = ConversationService(context, CustomEmojiRepository.getInstance(context))
        val content = "CHECKING_SENTINEL\n<tool name=\"read_file\"><param name=\"path\">fixture</param></tool>" +
            "<tool_result name=\"read_file\">PRIVATE_SENTINEL</tool_result>" +
            "<status type=\"warning\">SYNTHETIC_SENTINEL</status>\nFINAL_SENTINEL"
        val replay = mutableListOf(PromptTurn(PromptTurnKind.USER, "REAL_TASK"))
        service.processChatMessageWithTools(content, service.splitXmlTag(content), replay, 0, 1)
        val fork = CollaborationPromptHistory.select(replay, AgentFork.LastTurns(1))
        assertEquals(listOf("REAL_TASK", "FINAL_SENTINEL"), fork.map { it.content.trim() })
        val intermediate = mutableListOf<PromptTurn>()
        service.processChatMessageWithTools(
            content, service.splitXmlTag(content), intermediate, 0, 1,
            mapOf(CollaborationPromptHistory.INTERMEDIATE_METADATA to true),
        )
        assertTrue(CollaborationPromptHistory.select(intermediate, AgentFork.All).isEmpty())
    }
}

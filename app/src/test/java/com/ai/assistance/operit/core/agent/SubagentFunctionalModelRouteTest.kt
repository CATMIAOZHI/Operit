package com.ai.assistance.operit.core.agent

import com.ai.assistance.operit.data.model.FunctionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentFunctionalModelRouteTest {
    @Test
    fun permissionReviewerFunctionTypeReachesChatTurnOptions() {
        val request =
            SubagentTaskRequest(
                parentChatId = "parent",
                parentToolCallId = "tool-call",
                parentAgentName = "parent-agent",
                title = "permission review",
                prompt = "review",
                subagentType = "permission-reviewer",
                functionType = FunctionType.PERMISSION_REVIEWER,
            )

        val options =
            request.toChatTurnOptions(
                systemPrompt = "review-system-prompt",
                assistantRoleName = "reviewer",
            )

        assertEquals(FunctionType.PERMISSION_REVIEWER, options.functionType)
        assertTrue(options.isSubTask)
        assertEquals("review-system-prompt", options.systemPromptOverride)
    }
}

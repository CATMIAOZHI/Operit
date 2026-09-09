package com.ai.assistance.operit.pet

import com.ai.assistance.operit.data.model.InputProcessingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import org.junit.Test

class PetActivityTest {
    @Test fun viewingOnlyAcknowledgesSuccessfulInactiveMainChat() {
        val completed = PetTask("run", "chat", ChatRuntimeSlot.MAIN, "title", PetActivity.COMPLETE, false, 1)
        assertTrue(shouldAcknowledgeViewedPetTask(completed, "chat"))
        assertFalse(shouldAcknowledgeViewedPetTask(completed, "other"))
        assertFalse(shouldAcknowledgeViewedPetTask(completed.copy(active = true), "chat"))
        assertFalse(shouldAcknowledgeViewedPetTask(completed.copy(activity = PetActivity.ERROR), "chat"))
        ChatRuntimeSlot.values().filter { it != ChatRuntimeSlot.MAIN }.forEach {
            assertFalse(shouldAcknowledgeViewedPetTask(completed.copy(slot = it), "chat"))
        }
        assertEquals(PetActivity.IDLE, acknowledgedPetTask(completed, 1).activity)
        assertEquals(PetActivity.COMPLETE, acknowledgedPetTask(completed.copy(startedOrder = 2), 1).activity)
    }
    @Test fun removedRunDoesNotClaimSuccessWithoutCompletion() {
        assertEquals(PetActivity.ENDED, petActivity(InputProcessingState.Idle, false))
        assertEquals(PetActivity.ENDED, petActivity(InputProcessingState.ExecutingTool("read_file"), false))
        assertEquals(PetActivity.COMPLETE, petActivity(InputProcessingState.Completed, false))
        assertEquals(PetActivity.ERROR, petActivity(InputProcessingState.Error("failed"), false))
    }

    @Test fun toolResultAndProgressRemainToolActivity() {
        assertEquals(PetActivity.TOOL, petActivity(InputProcessingState.ProcessingToolResult("read_file"), true))
        assertEquals(PetActivity.TOOL, petActivity(InputProcessingState.ToolProgress("read_file", 0.5f), true))
        assertEquals(PetActivity.SUMMARIZING, petActivity(InputProcessingState.Summarizing("summary"), true))
    }
}

package com.ai.assistance.operit.pet

import com.ai.assistance.operit.data.model.InputProcessingState
import org.junit.Assert.assertEquals
import org.junit.Test

class PetActivityTest {
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

package com.ai.assistance.operit.ui.features.orbit

import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.pet.PetActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitLiveActivityTest {
    @Test fun noRunsMeansIdleAndNoStatus() {
        assertEquals(OrbitLiveActivity(), orbitLiveActivity(emptyList(), false))
    }

    @Test fun backgroundTaskWithoutStreamYetStillThinks() {
        assertEquals(PetActivity.THINKING, orbitLiveActivity(emptyList(), true).pet)
        assertEquals(PetActivity.THINKING, orbitLiveActivity(listOf(null), false).pet)
    }

    @Test fun toolProgressAndResultKeepTheToolPose() {
        listOf(
            InputProcessingState.ExecutingTool("browser"),
            InputProcessingState.ToolProgress("browser", 0.5f),
            InputProcessingState.ProcessingToolResult("browser"),
        ).forEach { state ->
            assertEquals(
                OrbitLiveActivity(R.string.orbit_activity_browsing, PetActivity.TOOL),
                orbitLiveActivity(listOf(state), false),
            )
        }
    }

    @Test fun activeToolTakesPrecedenceOverThinkingAndSummarizing() {
        val result = orbitLiveActivity(listOf(
            InputProcessingState.Summarizing("summary"),
            InputProcessingState.Receiving("reply"),
            InputProcessingState.ExecutingTool("read_file"),
        ), true)
        assertEquals(OrbitLiveActivity(R.string.orbit_activity_files, PetActivity.TOOL), result)
        assertEquals(PetActivity.SUMMARIZING,
            orbitLiveActivity(listOf(InputProcessingState.Summarizing("summary")), false).pet)
    }
}

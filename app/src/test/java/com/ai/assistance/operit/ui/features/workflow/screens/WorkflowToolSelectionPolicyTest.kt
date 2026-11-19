package com.ai.assistance.operit.ui.features.workflow.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowToolSelectionPolicyTest {
    @Test
    fun chatOnlyAndInternalToolsAreExcludedFromWorkflowSelection() {
        assertEquals(
            listOf("sleep", "http_request"),
            filterWorkflowSelectableToolNames(
                listOf(
                    "package_proxy",
                    "sleep",
                    "todowrite",
                    "orbit_set_name",
                    "http_request",
                )
            ),
        )
    }
}

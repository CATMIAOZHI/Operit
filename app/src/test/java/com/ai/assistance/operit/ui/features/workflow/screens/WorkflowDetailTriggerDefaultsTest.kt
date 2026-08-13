package com.ai.assistance.operit.ui.features.workflow.screens

import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkflowDetailTriggerDefaultsTest {
    @Test
    fun newExternalTriggerDraftsDoNotDisplayTokensThatSaveWillRotate() {
        val intent = JSONObject(defaultWorkflowTriggerConfigJson("intent"))
        val tasker = JSONObject(defaultWorkflowTriggerConfigJson("tasker"))

        assertEquals(
            WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
            intent.getString(WorkflowIntentSecurity.CONFIG_ACTION),
        )
        assertEquals(
            "start_meeting",
            tasker.getString(WorkflowIntentSecurity.CONFIG_TASKER_COMMAND),
        )
        assertFalse(intent.has(WorkflowIntentSecurity.CONFIG_AUTH_TOKEN))
        assertFalse(tasker.has(WorkflowIntentSecurity.CONFIG_AUTH_TOKEN))
    }
}

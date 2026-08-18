package com.ai.assistance.operit.core.tools.defaultTool.standard

import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardWorkflowToolsSecurityTest {
    @Test
    fun modelFacingWorkflowDetailsRemoveAuthTokensFromEveryTrigger() {
        val sentinel = "sentinel_auth_token_that_must_not_reach_the_model"
        val workflow = Workflow(
            id = "protected",
            nodes = listOf(
                TriggerNode(
                    id = "intent",
                    triggerType = "intent",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_ACTION to
                            WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to sentinel,
                    ),
                ),
                TriggerNode(
                    id = "tasker",
                    triggerType = "tasker",
                    triggerConfig = mapOf(
                        WorkflowIntentSecurity.CONFIG_TASKER_COMMAND to "start",
                        WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to sentinel,
                    ),
                ),
            ),
        )

        val result = workflowDetailResultDataForModel(workflow)
        val triggerConfigs = result.nodes.filterIsInstance<TriggerNode>().map(TriggerNode::triggerConfig)

        assertEquals(2, triggerConfigs.size)
        assertTrue(triggerConfigs.all { WorkflowIntentSecurity.CONFIG_AUTH_TOKEN !in it })
        assertEquals(
            WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW,
            triggerConfigs.first()[WorkflowIntentSecurity.CONFIG_ACTION],
        )
        assertFalse(result.toString().contains(sentinel))
    }
}

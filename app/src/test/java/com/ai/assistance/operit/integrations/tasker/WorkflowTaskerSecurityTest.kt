package com.ai.assistance.operit.integrations.tasker

import android.content.Context
import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import com.ai.assistance.operit.data.repository.selectExternalWorkflowTriggerMatches
import com.ai.assistance.operit.data.repository.summarizeExternalTriggerResults
import com.joaomgcd.taskerpluginlibrary.input.TaskerInput
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputField
import com.joaomgcd.taskerpluginlibrary.input.TaskerInputRoot
import com.joaomgcd.taskerpluginlibrary.runner.TaskerPluginResultError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.joaomgcd.taskerpluginlibrary.config.TaskerPluginConfig
import org.mockito.kotlin.mock

class WorkflowTaskerSecurityTest {
    @Test
    fun runnerFailsClosedWhileMainWorkflowDataIsGated() {
        assertFalse(taskerWorkflowDataAccessAllowed(false))
        assertTrue(taskerWorkflowDataAccessAllowed(true))

        val runnerSource = java.io.File(
            "src/main/java/com/ai/assistance/operit/integrations/tasker/WorkflowTaskerActivity.kt"
        ).readText()
        assertTrue(
            runnerSource.contains(
                "taskerWorkflowDataAccessAllowed(OperitApplication.isMainDataAccessAllowed(context))"
            )
        )

        val receiverSource = java.io.File(
            "src/main/java/com/ai/assistance/operit/integrations/tasker/WorkflowTaskerReceiver.kt"
        ).readText()
        val gateIndex = receiverSource.indexOf("OperitApplication.isMainDataAccessAllowed(context)")
        val tokenReadIndex = receiverSource.indexOf("WorkflowIntentSecurity.readAuthTokenSafely(intent)")
        val managerIndex = receiverSource.indexOf("WorkflowAuthTokenManager(context)")
        assertTrue(gateIndex >= 0)
        assertTrue(gateIndex < tokenReadIndex)
        assertTrue(gateIndex < managerIndex)
    }

    @Test
    fun taskerInput_publishesCommandAndAuthTokenFields() {
        assertNotNull(WorkflowTaskerInput::class.java.getAnnotation(TaskerInputRoot::class.java))
        val annotatedNames = WorkflowTaskerInput::class.java.declaredFields
            .mapNotNull { field -> field.getAnnotation(TaskerInputField::class.java)?.key }
            .toSet()

        assertEquals(setOf("command", "auth_token"), annotatedNames)
    }

    @Test
    fun runner_rejectsLegacyOrForgedInputWithoutAuthTokenBeforeRepositoryAccess() {
        val input = TaskerInput(
            WorkflowTaskerInput(
                command = "start_meeting",
                authToken = null,
                params = arrayListOf("start_meeting")
            )
        )

        val result = WorkflowTaskerRunner().run(mock<Context>(), input)

        assertTrue(result is TaskerPluginResultError)
    }

    @Test
    fun configHelper_neverIncludesAuthTokenInTaskerStringBlurb() {
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        val input = TaskerInput(
            WorkflowTaskerInput(command = "start_meeting", authToken = token)
        )
        val helper = WorkflowTaskerConfigHelper(mock<TaskerPluginConfig<WorkflowTaskerInput>>())
        val blurb = StringBuilder()

        helper.addToStringBlurb(input, blurb)

        assertFalse(helper.addDefaultStringBlurb)
        assertTrue(helper.isInputValid(input).success)
        assertTrue(blurb.toString().contains("start_meeting"))
        assertFalse(blurb.toString().contains(token))
    }

    @Test
    fun productionSelector_acceptsExpectedTaskerTokenAndRejectsStrongForgery() {
        val expectedToken = "01234567-89ab-cdef-0123-456789abcdef"
        val forgedToken = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        val workflows = listOf(
            Workflow(
                id = "workflow-1",
                name = "Protected",
                enabled = true,
                nodes = listOf(
                    TriggerNode(
                        id = "trigger-1",
                        triggerType = "tasker",
                        triggerConfig = mapOf(
                            WorkflowIntentSecurity.CONFIG_TASKER_COMMAND to "start_meeting",
                            WorkflowIntentSecurity.CONFIG_AUTH_TOKEN to expectedToken
                        )
                    )
                )
            )
        )

        val forgedMatches = selectExternalWorkflowTriggerMatches(workflows) { node ->
            WorkflowIntentSecurity.matchesTasker(node, "start_meeting", forgedToken)
        }
        val acceptedMatches = selectExternalWorkflowTriggerMatches(workflows) { node ->
            WorkflowIntentSecurity.matchesTasker(node, "start_meeting", expectedToken)
        }

        assertTrue(forgedMatches.isEmpty())
        assertEquals("workflow-1", acceptedMatches.single().workflowId)
    }

    @Test
    fun dispatchSummary_rejectsNoMatchOrExecutionFailureInsteadOfReportingSuccess() {
        assertTrue(summarizeExternalTriggerResults(emptyList()).isFailure)
        assertTrue(
            summarizeExternalTriggerResults(
                listOf(Result.success("ok"), Result.failure(IllegalStateException("failed")))
            ).isFailure
        )
        assertEquals(2, summarizeExternalTriggerResults(listOf(Result.success("a"), Result.success("b"))).getOrThrow())
    }
}

package com.ai.assistance.operit.integrations.tasker

import android.content.Context
import com.ai.assistance.operit.R
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
import org.mockito.kotlin.whenever

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
        val shapeCheckIndex = receiverSource.indexOf(
            "WorkflowIntentSecurity.isValidAuthToken(authToken)",
            startIndex = tokenReadIndex,
        )
        val goAsyncIndex = receiverSource.indexOf("val pendingResult = goAsync()")
        val ioScopeIndex = receiverSource.indexOf("CoroutineScope(Dispatchers.IO).launch")
        val managerIndex = receiverSource.indexOf("WorkflowAuthTokenManager(context.applicationContext)")
        assertTrue(gateIndex >= 0)
        assertTrue(gateIndex < tokenReadIndex)
        assertTrue(tokenReadIndex < shapeCheckIndex)
        assertTrue(shapeCheckIndex < goAsyncIndex)
        assertTrue(goAsyncIndex < ioScopeIndex)
        assertTrue(ioScopeIndex < managerIndex)
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
        val context = mock<Context>()
        whenever(context.getString(R.string.workflow_tasker_action_command, "start_meeting"))
            .thenReturn("Command: start_meeting")
        val config = mock<TaskerPluginConfig<WorkflowTaskerInput>>()
        whenever(config.context).thenReturn(context)
        val helper = WorkflowTaskerConfigHelper(config)
        val blurb = StringBuilder()

        helper.addToStringBlurb(input, blurb)

        assertFalse(helper.addDefaultStringBlurb)
        assertTrue(helper.isInputValid(input).success)
        assertTrue(blurb.toString().contains("start_meeting"))
        assertFalse(blurb.toString().contains(token))
    }

    @Test
    fun taskerUserVisibleMessagesComeFromLocalizedResources() {
        val source = java.io.File(
            "src/main/java/com/ai/assistance/operit/integrations/tasker/WorkflowTaskerActivity.kt"
        ).readText()
        listOf(
            "workflow_tasker_action_command",
            "workflow_tasker_action_input_required",
            "workflow_tasker_action_data_unavailable",
            "workflow_tasker_action_auth_invalid",
        ).forEach { resourceName ->
            assertTrue(source.contains("R.string.$resourceName"))
            assertTrue(java.io.File("src/main/res/values/strings.xml").readText().contains("name=\"$resourceName\""))
            assertTrue(java.io.File("src/main/res/values-en/strings.xml").readText().contains("name=\"$resourceName\""))
        }
        assertFalse(source.contains("blurbBuilder.append(\"Command: \""))
        assertFalse(source.contains("SimpleResultError(\"Workflow command"))
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

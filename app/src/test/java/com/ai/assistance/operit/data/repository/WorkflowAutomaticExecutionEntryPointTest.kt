package com.ai.assistance.operit.data.repository

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowAutomaticExecutionEntryPointTest {
    @Test
    fun unattendedEntryPointsUsePrivateOnlyRepositoryApis() {
        val worker = source("core/workflow/WorkflowWorker.kt")
        assertTrue(worker.contains("OperitApplication.isMainDataAccessAllowed(applicationContext)"))
        assertTrue(
            worker.indexOf("OperitApplication.isMainDataAccessAllowed(applicationContext)") <
                worker.indexOf("inputData.getString(KEY_WORKFLOW_ID)")
        )
        assertTrue(worker.contains("repository.triggerScheduledWorkflow("))
        assertTrue(worker.contains("scheduleFingerprint"))
        assertFalse(worker.contains("repository.triggerWorkflow(workflowId, triggerNodeId)"))

        val initializer = source("core/workflow/WorkflowSchedulerInitializer.kt")
        assertTrue(initializer.contains("OperitApplication.isMainDataAccessAllowed(context)"))
        assertTrue(
            initializer.indexOf("OperitApplication.isMainDataAccessAllowed(context)") <
                initializer.indexOf("WorkflowRepository(context.applicationContext)")
        )
        assertTrue(initializer.contains("repository.resetSchedulesForLegacyWorkflowIds()"))
        assertTrue(initializer.contains("repository.rebuildInternalWorkflowSchedules()"))

        val receivers = source("integrations/tasker/WorkflowTaskerReceiver.kt")
        assertTrue(receivers.contains("repository.resetSchedulesForLegacyWorkflowIds()"))
        assertTrue(receivers.contains("repository.rebuildInternalWorkflowSchedules()"))

        val repository = source("data/repository/WorkflowRepository.kt")
        assertTrue(repository.contains("executionOrigin = WorkflowExecutionOrigin.AUTOMATIC"))
        assertTrue(repository.contains("val workflows = getAllInternalWorkflows().getOrNull()"))
        assertTrue(repository.contains("cancelAndWait = scheduler::cancelWorkflowAndWait"))
        assertTrue(repository.contains("isTrustedScheduleExecutionAuthorized("))

        val aiTools = source("core/tools/defaultTool/standard/StandardWorkflowTools.kt")
        assertTrue(aiTools.contains("workflowRepository.triggerWorkflowFromPrivateStorage(workflowId)"))
        assertFalse(aiTools.contains("workflowRepository.triggerWorkflow(workflowId)"))
        assertTrue(aiTools.contains("workflowRepository.updateWorkflowFromPrivateStorage(updatedWorkflow)"))
        assertTrue(aiTools.contains("workflowRepository.setWorkflowEnabledFromPrivateStorage("))
        assertFalse(aiTools.contains("workflowRepository.updateWorkflow(updatedWorkflow)"))
        assertFalse(aiTools.contains("workflowRepository.setWorkflowEnabled(workflowId, enabled)"))
    }

    private fun source(relativePath: String): String {
        val file = File("src/main/java/com/ai/assistance/operit/$relativePath")
        assertTrue("Missing production source: ${file.absolutePath}", file.isFile)
        return file.readText()
    }
}

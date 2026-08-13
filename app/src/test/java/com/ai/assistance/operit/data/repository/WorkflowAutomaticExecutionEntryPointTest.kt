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
            worker.indexOf("inputData.getString(KEY_WORKFLOW_ID)") <
                worker.indexOf("if (workflowId.isNullOrBlank()")
        )
        assertTrue(
            worker.indexOf("if (workflowId.isNullOrBlank()") <
                worker.indexOf("OperitApplication.isMainDataAccessAllowed(applicationContext)")
        )
        assertTrue(
            worker.indexOf("WorkflowGateRetryStore(applicationContext).mark(id)") <
                worker.indexOf("return Result.retry()")
        )
        assertTrue(
            worker.indexOf("OperitApplication.isMainDataAccessAllowed(applicationContext)") <
                worker.indexOf("val repository = WorkflowRepository(applicationContext)")
        )
        assertTrue(worker.contains("repository.triggerScheduledWorkflow("))
        assertTrue(worker.contains("repository.triggerPreFingerprintScheduledWorkflow("))
        assertTrue(worker.contains("PreFingerprintScheduleReplacementPendingException"))
        assertTrue(worker.contains("Result.retry()"))
        assertTrue(worker.contains("WorkflowGateRetryStore(applicationContext).mark(id)"))
        assertTrue(worker.contains("WorkflowGateRetryStore(applicationContext).consume(id)"))
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
        assertTrue(repository.contains("rebuildOneInternalWorkflowSchedule(workflow.id)"))
        assertTrue(repository.contains("cancelAndWait = ::cancelLegacyOnlySchedule"))
        assertTrue(repository.contains("shouldDeferClaimedScheduleRebuild("))
        val rebuildOne = repository.substring(
            repository.indexOf("private fun rebuildOneInternalWorkflowSchedule"),
            repository.indexOf("internal suspend fun rebuildInternalWorkflowSchedules"),
        )
        val rebuildActiveCheckIndex =
            rebuildOne.indexOf("scheduler.hasActiveWorkflowScheduleAndWait(id)")
        val rebuildMatchingCheckIndex =
            rebuildOne.indexOf("scheduler.hasActiveMatchingWorkflowScheduleAndWait(latest)")
        val rebuildDeferredCheckIndex =
            rebuildOne.indexOf("scheduler.gateDeferredWorkflowScheduleIdsAndWait(id)")
        val rebuildSchedulableCheckIndex =
            rebuildOne.indexOf("scheduler.isSchedulableWorkflowDefinition(latest)")
        val rebuildRejectedCheckIndex =
            rebuildOne.indexOf("WorkflowScheduler.REJECTED_SCHEDULE_FINGERPRINT_GENERATION")
        val rebuildPreserveIndex =
            rebuildOne.indexOf("scheduler.shouldPreserveActivePreFingerprintScheduleRequest(latest)")
        val rebuildCompletedIndex =
            rebuildOne.indexOf("scheduler.shouldRetireCompletedPreFingerprintOneTimeAndWait(latest)")
        val rebuildScheduleIndex = rebuildOne.indexOf("scheduleWorkflowLocked(")
        assertTrue(rebuildRejectedCheckIndex in 0 until rebuildSchedulableCheckIndex)
        assertTrue(rebuildSchedulableCheckIndex in 0 until rebuildActiveCheckIndex)
        assertTrue(rebuildActiveCheckIndex in 0 until rebuildDeferredCheckIndex)
        assertTrue(rebuildDeferredCheckIndex in 0 until rebuildMatchingCheckIndex)
        assertTrue(rebuildMatchingCheckIndex in 0 until rebuildPreserveIndex)
        assertTrue(rebuildPreserveIndex in 0 until rebuildCompletedIndex)
        assertTrue(rebuildCompletedIndex in 0 until rebuildScheduleIndex)
        assertTrue(rebuildOne.contains("writeWorkflowContentAtomically(internal"))
        assertTrue(rebuildOne.contains("allowDuePreFingerprintOneTime = allowPastSpecificTime"))
        assertTrue(rebuildOne.contains("deferredScheduleRetry"))
        assertTrue(rebuildOne.contains("runDeferredCronImmediately ="))
        assertTrue(rebuildOne.contains("scheduler.consumeGateDeferredWorkflowSchedules(gateDeferredRequestIds)"))
        val legacyCancel = repository.substring(
            repository.indexOf("private fun cancelLegacyOnlySchedule"),
            repository.indexOf("private fun loadInternalWorkflowsForAutomaticTriggers"),
        )
        val legacyPrivateGuard = legacyCancel.indexOf("if (internal.isFile) return@synchronized")
        val legacyCancelIndex = legacyCancel.indexOf("scheduler.cancelWorkflowAndWait(id)")
        assertTrue(legacyPrivateGuard in 0 until legacyCancelIndex)
        assertTrue(repository.contains("markPreFingerprintReplacementPending("))
        assertTrue(repository.contains("isTrustedScheduleExecutionAuthorized("))
        val scheduleLocked = repository.substring(
            repository.indexOf("private fun scheduleWorkflowLocked"),
            repository.indexOf("private fun reconcileInternalWorkflowSchedule"),
        )
        assertTrue(scheduleLocked.contains("coordinateFingerprintScheduleRequest("))
        assertTrue(scheduleLocked.contains("scheduler.scheduleWorkflow("))
        assertTrue(scheduleLocked.contains("delayFirstIntervalRun = delayFirstIntervalRun"))
        assertTrue(scheduleLocked.contains("runDeferredCronImmediately = runDeferredCronImmediately"))
        assertTrue(scheduleLocked.contains("scheduler.cancelWorkflowAndWait(workflow.id)"))
        val fingerprintedTrigger = repository.substring(
            repository.indexOf("internal suspend fun triggerScheduledWorkflow"),
            repository.indexOf("internal suspend fun triggerPreFingerprintScheduledWorkflow"),
        )
        assertTrue(fingerprintedTrigger.contains("normalizeFingerprintGenerationForExecution("))
        assertTrue(fingerprintedTrigger.contains("PreFingerprintScheduleReplacementPendingException"))

        val aiTools = source("core/tools/defaultTool/standard/StandardWorkflowTools.kt")
        assertTrue(aiTools.contains("workflowRepository.triggerWorkflowFromPrivateStorage(workflowId)"))
        assertFalse(aiTools.contains("workflowRepository.triggerWorkflow(workflowId)"))
        assertTrue(aiTools.contains("workflowRepository.updateWorkflowFromPrivateStorage(updatedWorkflow)"))
        assertTrue(aiTools.contains("workflowRepository.setWorkflowEnabledFromPrivateStorage("))
        assertFalse(aiTools.contains("workflowRepository.updateWorkflow(updatedWorkflow)"))
        assertFalse(aiTools.contains("workflowRepository.setWorkflowEnabled(workflowId, enabled)"))
        assertTrue(repository.contains("inheritModelRedactedExternalTriggerToken = false"))
        assertTrue(repository.contains("inheritModelRedactedExternalTriggerToken = true"))
        assertTrue(
            repository.contains("inheritMissingToken = inheritModelRedactedExternalTriggerToken")
        )
        val createWorkflow = repository.substring(
            repository.indexOf("suspend fun createWorkflow"),
            repository.indexOf("suspend fun updateWorkflow"),
        )
        assertTrue(createWorkflow.contains("replaceExistingTokens = true"))
        assertTrue(repository.contains("recoverAtomicBackups = true"))
        val latestExecutionRecord = repository.substring(
            repository.indexOf("suspend fun getLatestExecutionRecord"),
            repository.indexOf("suspend fun createWorkflow"),
        )
        assertTrue(latestExecutionRecord.contains("recoverAtomicBackups = true"))
        assertTrue(latestExecutionRecord.contains("trustedAnchor = context.noBackupFilesDir"))
        assertTrue(latestExecutionRecord.contains("readWorkflowContentAtomically(latest)"))
        val replacement = repository.substring(
            repository.indexOf("private fun replaceClaimedPreFingerprintSchedule"),
            repository.indexOf("private fun scheduleWorkflowLocked"),
        )
        assertTrue(replacement.contains("delayFirstIntervalRun = true"))
        assertTrue(repository.contains("recoverAtomicWorkflowFile(file)"))
        assertTrue(repository.contains("withWorkflowAtomicFileLock(file)"))
    }

    private fun source(relativePath: String): String {
        val file = File("src/main/java/com/ai/assistance/operit/$relativePath")
        assertTrue("Missing production source: ${file.absolutePath}", file.isFile)
        return file.readText()
    }
}

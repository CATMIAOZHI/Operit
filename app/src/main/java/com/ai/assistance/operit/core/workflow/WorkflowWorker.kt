package com.ai.assistance.operit.core.workflow

import android.content.Context
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.util.AppLogger
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ai.assistance.operit.data.repository.WorkflowRepository

/**
 * WorkManager Worker for executing workflows in the background
 * 
 * This worker is scheduled by WorkflowScheduler to execute workflows
 * at specified times or intervals.
 */
class WorkflowWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "WorkflowWorker"
        const val KEY_WORKFLOW_ID = "workflow_id"
        const val KEY_TRIGGER_NODE_ID = "trigger_node_id"
    }

    override suspend fun doWork(): Result {
        if (!OperitApplication.isMainDataAccessAllowed(applicationContext)) {
            AppLogger.w(TAG, "Deferred workflow execution while migration or restore is in progress")
            return Result.retry()
        }
        val workflowId = inputData.getString(KEY_WORKFLOW_ID)
        val triggerNodeId = inputData.getString(KEY_TRIGGER_NODE_ID)
        val scheduleFingerprint = inputData.getString(WorkflowScheduler.KEY_SCHEDULE_FINGERPRINT)
        
        if (workflowId.isNullOrBlank() || triggerNodeId.isNullOrBlank() || scheduleFingerprint.isNullOrBlank()) {
            AppLogger.e(TAG, "Trusted workflow schedule metadata is missing from input data")
            return Result.failure()
        }

        AppLogger.d(TAG, "Executing scheduled workflow: $workflowId, trigger: $triggerNodeId")

        return try {
            val repository = WorkflowRepository(applicationContext)
            val result = repository.triggerScheduledWorkflow(
                workflowId,
                triggerNodeId,
                scheduleFingerprint,
            )
            
            if (result.isSuccess) {
                AppLogger.d(TAG, "Workflow execution succeeded: ${result.getOrNull()}")
                Result.success()
            } else {
                AppLogger.e(TAG, "Workflow execution failed: ${result.exceptionOrNull()?.message}")
                Result.failure()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error executing workflow", e)
            Result.failure()
        }
    }
}


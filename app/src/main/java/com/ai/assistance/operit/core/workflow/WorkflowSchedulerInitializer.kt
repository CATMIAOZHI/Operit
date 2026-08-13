package com.ai.assistance.operit.core.workflow

import android.content.Context
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.data.repository.WorkflowRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WorkflowSchedulerInitializer
 * 
 * Initializes workflow scheduling when the app starts.
 * Re-schedules all enabled workflows to ensure they continue running
 * even if the app was force-stopped or updated.
 */
object WorkflowSchedulerInitializer {
    
    private const val TAG = "WorkflowSchedulerInit"
    
    /**
     * Initialize workflow scheduling
     * Should be called from Application.onCreate()
     */
    fun initialize(context: Context) {
        AppLogger.d(TAG, "Initializing workflow scheduler...")
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!OperitApplication.isMainDataAccessAllowed(context)) {
                    AppLogger.w(TAG, "Skipped workflow scheduling while migration or restore is in progress")
                    return@launch
                }
                val repository = WorkflowRepository(context.applicationContext)
                repository.resetSchedulesForLegacyWorkflowIds()
                repository.rebuildInternalWorkflowSchedules().getOrNull()?.let { rebuild ->
                    AppLogger.d(
                        TAG,
                        "Workflow scheduler initialized. Scheduled ${rebuild.scheduledCount} " +
                            "workflows; cancellation failures=${rebuild.cancellationFailures}."
                    )
                } ?: run {
                    AppLogger.w(TAG, "Failed to rebuild workflows during initialization")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error initializing workflow scheduler", e)
            }
        }
    }
}


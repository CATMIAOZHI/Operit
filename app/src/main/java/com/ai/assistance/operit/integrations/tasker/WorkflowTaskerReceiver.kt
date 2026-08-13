package com.ai.assistance.operit.integrations.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.workflow.WorkflowIntentSecurity
import com.ai.assistance.operit.core.workflow.WorkflowAuthTokenManager
import com.ai.assistance.operit.data.repository.WorkflowRepository
import com.ai.assistance.operit.core.application.OperitApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for receiving workflow trigger requests from Tasker
 * 
 * This receiver allows Tasker to trigger Operit workflows via broadcasts.
 */
class WorkflowTaskerReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WorkflowTaskerReceiver"
        const val ACTION_TRIGGER_WORKFLOW = WorkflowIntentSecurity.ACTION_TRIGGER_WORKFLOW
        const val EXTRA_AUTH_TOKEN = WorkflowIntentSecurity.EXTRA_AUTH_TOKEN
        
        /**
         * Creates an authenticated intent to trigger workflows based on intent data.
         * External automation apps must use the token shown in the workflow's Intent trigger.
         */
        fun createTriggerIntent(
            context: Context,
            authToken: String,
            extras: Bundle? = null
        ): Intent {
            require(WorkflowIntentSecurity.isValidAuthToken(authToken)) { "Invalid workflow auth token" }
            return Intent(context, WorkflowTaskerReceiver::class.java).apply {
                action = ACTION_TRIGGER_WORKFLOW
                extras?.let { putExtras(it) }
                putExtra(EXTRA_AUTH_TOKEN, authToken)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action.isNullOrBlank()) {
            return
        }
        if (!OperitApplication.isMainDataAccessAllowed(context)) return
        val authToken = WorkflowIntentSecurity.readAuthTokenSafely(intent)
        if (!WorkflowIntentSecurity.isValidAuthToken(authToken)) {
            return
        }

        // Authentication may initialize the no-backup signing key. Keep that disk I/O, as well as
        // workflow lookup, off BroadcastReceiver.onReceive's main-thread deadline.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!WorkflowAuthTokenManager(context.applicationContext)
                        .isAuthenticAuthToken(authToken)
                ) {
                    return@launch
                }
                AppLogger.d(TAG, "Received workflow trigger broadcast for action: $action. Checking for matching workflows.")
                val repository = WorkflowRepository(context.applicationContext)
                // New method to find and trigger workflows based on the intent's content (action, extras, etc.)
                repository.triggerWorkflowsByIntentEvent(intent)
                AppLogger.d(TAG, "Finished processing intent trigger.")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error processing intent trigger for workflows", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * BroadcastReceiver for boot completed event
 * 
 * Re-schedules all enabled workflows after device reboot
 */
class WorkflowBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WorkflowBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }
        if (!OperitApplication.isMainDataAccessAllowed(context)) return

        AppLogger.d(TAG, "Device booted, rescheduling workflows")

        // Use goAsync to allow async work
        val pendingResult = goAsync()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = WorkflowRepository(context.applicationContext)
                repository.resetSchedulesForLegacyWorkflowIds()
                repository.rebuildInternalWorkflowSchedules().onFailure { error ->
                    AppLogger.e(TAG, "Failed to rebuild private schedules after boot", error)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error rescheduling workflows after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}


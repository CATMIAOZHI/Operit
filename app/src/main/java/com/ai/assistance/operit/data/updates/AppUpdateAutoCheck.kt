package com.ai.assistance.operit.data.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.features.orbit.OrbitAppUpdate
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Looks for a new version about once a day while "check for updates automatically" is on (the
 * About page). A new version shows like a manual check does, and the running Orbit hears of it at
 * most once a day so it can tell the user. Turned off, nothing is scheduled.
 */
object AppUpdateAutoCheck {
    private const val TAG = "AppUpdateAutoCheck"
    private const val WORK_NAME = "app_update_auto_check"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    /** Outlives the About page, so a switch flipped just before leaving is still carried out. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The start-up sync and the switch run one at a time, so the schedule always ends up matching the switch. */
    private val switching = Mutex()

    private suspend fun isOn(context: Context): Boolean =
        UserPreferencesManager.getInstance(context.applicationContext).autoCheckUpdates.first()

    /** At start: the daily check is scheduled while the switch is on and removed while it is off. */
    suspend fun sync(context: Context) = switching.withLock { applySchedule(context) }

    /**
     * A newly scheduled daily check runs soon after, so turning the switch on also looks right away;
     * a schedule already there is kept.
     */
    private suspend fun applySchedule(context: Context) {
        val work = WorkManager.getInstance(context.applicationContext)
        if (isOn(context)) {
            val request = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.DAYS).setConstraints(network).build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        } else {
            work.cancelUniqueWork(WORK_NAME)
        }
    }

    /** The switch on the About page. On: it looks right away too. Off: a note Orbit has not got yet is dropped. */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        scope.launch {
            try {
                switching.withLock {
                    UserPreferencesManager.getInstance(app).saveAutoCheckUpdates(enabled)
                    applySchedule(app)
                    if (!enabled) OrbitAppUpdate.withdrawAvailable(app)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Could not apply the update check switch: ${e.message}")
            }
        }
    }

    private suspend fun run(context: Context) {
        val app = context.applicationContext
        if (!isOn(app)) return
        val (version, url) = when (val status = UpdateManager.getInstance(app).checkForUpdates(BuildConfig.VERSION_NAME)) {
            is UpdateStatus.Available -> status.newVersion to status.updateUrl
            is UpdateStatus.PatchAvailable -> status.newVersion to status.updateUrl
            else -> return
        }
        // Turned off while it looked.
        if (!isOn(app)) return
        OrbitAppUpdate.tellAvailable(app, version, url, stillOn = { isOn(app) })
    }

    class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Like Orbit's other background work: no stored data is opened while the app migrates or
            // restores; the next daily run looks again.
            if (OperitApplication.initializeMainApplication(applicationContext) !=
                OperitApplication.MainApplicationInitResult.Initialized) return Result.success()
            return try {
                run(applicationContext)
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w(TAG, "Automatic update check failed: ${e.message}")
                Result.success()
            }
        }
    }
}

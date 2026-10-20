package com.ai.assistance.operit.features.orbit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.ui.main.MainActivity
import kotlinx.coroutines.delay

/** The ongoing notification that lets an Orbit task run past the background worker limit. */
internal object OrbitLongRunNotification {
    private const val CHANNEL = "orbit_long_task"
    private const val NOTIFICATION_ID = 0x0B17

    /**
     * Android 12+ lets a background app start a foreground service only in a few cases. The ones
     * that apply here: Operit is on screen, it holds the overlay permission, or the user exempted
     * it from battery optimization. Without one of them WorkManager swallows the refusal, so it
     * is checked up front.
     */
    fun mayStartFromBackground(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        if (ActivityLifecycleManager.getCurrentActivity() != null) return true
        if (android.provider.Settings.canDrawOverlays(context)) return true
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Whether the foreground service really started; WorkManager may fail silently. The posted
     * notification is the proof. When the user turned notifications (or this channel) off, the
     * service still runs but its notification never shows, so there is nothing to check and the
     * start is trusted.
     */
    suspend fun isShowing(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val hidden = !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        if (hidden) return true
        repeat(10) {
            if (manager.activeNotifications.any { it.id == NOTIFICATION_ID }) return true
            delay(200)
        }
        return false
    }

    /** [orbitName] is the name the user gave the Orbit; blank falls back to "Orbit". */
    fun foregroundInfo(context: Context, orbitName: String, title: String): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL,
            context.getString(R.string.orbit_long_task_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_simple_foreground)
            .setContentTitle(context.getString(R.string.orbit_long_task_running, orbitName.ifBlank { context.getString(R.string.nav_orbit) }))
            .setContentText(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}

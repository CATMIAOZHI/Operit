package com.ai.assistance.operit.core.application

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.pet.PetPreferences
import com.ai.assistance.operit.ui.main.MainActivity

/** All companion services share one foreground notification, with independent service lifetimes. */
object CompanionNotification {
    const val ID = 1
    private const val CHANNEL = "AI_SERVICE_CHANNEL"
    private val owners = mutableSetOf<Service>()
    private var primary: Service? = null
    private var primaryNotification: Notification? = null

    @Synchronized
    fun startForeground(service: Service, notificationId: Int, notification: Notification, types: Int) {
        val content = if (notificationId == ID) notification else primaryNotification ?: fallback(service)
        ForegroundServiceCompat.startForeground(service, ID, content, types)
        owners.add(service)
        if (notificationId == ID) {
            primary = service
            primaryNotification = notification
        }
    }

    @Synchronized
    fun update(service: Service, notification: Notification) {
        if (service !in owners || primary !== service) return
        primaryNotification = notification
        manager(service).notify(ID, notification)
    }

    @Synchronized
    fun refresh(context: Context) {
        if (owners.isNotEmpty() && primary == null) manager(context).notify(ID, fallback(context))
    }

    @Synchronized
    fun release(service: Service) {
        if (!owners.remove(service)) return
        // DETACH prevents destruction of one window service from cancelling the shared notification.
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        if (primary === service) {
            primary = null
            primaryNotification = null
        }
        if (owners.isEmpty()) manager(service).cancel(ID)
        else manager(service).notify(ID, primaryNotification ?: fallback(service))
    }

    fun petAction(context: Context): NotificationCompat.Action {
        val intent = Intent(context, AIForegroundService::class.java)
            .setAction(AIForegroundService.ACTION_TOGGLE_PET)
        return NotificationCompat.Action(
            R.drawable.ic_launcher_simple_foreground,
            context.getString(if (PetPreferences.get(context).enabled.value) R.string.pet_turn_off else R.string.pet_turn_on),
            PendingIntent.getForegroundService(context, 9005, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
    }

    fun fallback(context: Context): Notification {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.service_operit_running), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_simple_foreground)
            .setContentTitle(context.getString(R.string.service_operit_running))
            .setContentIntent(open)
            .addAction(petAction(context))
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)
}

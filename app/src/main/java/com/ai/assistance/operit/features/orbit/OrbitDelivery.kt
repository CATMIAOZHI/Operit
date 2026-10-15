package com.ai.assistance.operit.features.orbit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.pet.PetTasks
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.WaifuMessageProcessor

object OrbitDelivery {
    suspend fun sendPending(context: Context, orbitId: String) {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        for (item in dao.pendingDeliveries(orbitId)) {
            val saved = OrbitExecutionGate.serialized {
                val orbit = OrbitPreferences.getInstance(context).current().active
                if (orbit?.id != orbitId || orbit.deleting) return@serialized null
                ChatHistoryManager.getInstance(context).appendOrbitDelivery(item.id)?.also { delivery ->
                    val task = dao.task(delivery.taskId) ?: return@also
                    if (task.version != delivery.taskVersion || task.state !in setOf("ACTIVE", "DONE")) return@also
                    if (!delivery.notificationSent) notify(context, delivery)
                    dao.updateDelivery(delivery.copy(state = "SENT", notificationSent = true))
                }
            }
            if (saved != null) {
                val runtimes = ChatRuntimeHolder.getInstance(context)
                ChatRuntimeSlot.entries.forEach { slot ->
                    val core = runtimes.getCore(slot)
                    if (core.currentChatId.value == saved.chatId) {
                        core.getChatHistoryDelegate().reloadChatMessagesSmart(saved.chatId)
                    }
                }
            }
        }
    }

    private fun notify(context: Context, delivery: com.ai.assistance.operit.data.model.OrbitDeliveryEntity) {
        if (ActivityLifecycleManager.getCurrentActivity() != null ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = "orbit_check_in"
        manager.createNotificationChannel(NotificationChannel(channel,
            context.getString(R.string.orbit_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).apply {
            action = PetTasks.ACTION_OPEN_CHAT
            data = Uri.parse("operit://orbit-delivery/${Uri.encode(delivery.id)}")
            putExtra(PetTasks.EXTRA_CHAT_ID, delivery.chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = WaifuMessageProcessor.cleanContentForWaifu(delivery.body).ifBlank { delivery.body }
        manager.notify("orbit:${delivery.id}", 0, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_simple_foreground).setContentTitle(delivery.title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
}

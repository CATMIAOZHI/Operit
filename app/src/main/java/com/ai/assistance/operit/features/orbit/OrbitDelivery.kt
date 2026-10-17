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
import kotlinx.coroutines.flow.update

object OrbitDelivery {
    internal suspend fun dueAt(context: Context, item: com.ai.assistance.operit.data.model.OrbitDeliveryEntity, now: Long): Long {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val policy = dao.notificationPolicy(item.orbitId)
            ?: com.ai.assistance.operit.data.model.OrbitNotificationEntity(item.orbitId)
        return OrbitDeliveryPolicy.nextAllowed(if (item.messageId == null) policy else policy.copy(intervalMinutes = 0),
            item.createdAt, item.messageId == null && dao.task(item.taskId)?.notifyPolicy == "DIGEST", now)
    }

    /** Posts due reports, one message per chat (the main chat and each side chat). */
    suspend fun sendPending(context: Context, orbitId: String) {
        repeat(1 + com.ai.assistance.operit.data.preferences.OrbitSettings.MAX_SIDE_CHATS) {
            if (!sendOnce(context, orbitId)) return
        }
    }

    /**
     * Chats that got a report the user has not looked at yet, kept for this app session. The
     * Orbit page shows a dot for them and clears a chat once it is on screen.
     */
    private val unread = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val unreadChats: kotlinx.coroutines.flow.StateFlow<Set<String>> = unread

    fun markSeen(chatId: String) {
        unread.update { it - chatId }
    }

    private suspend fun sendOnce(context: Context, orbitId: String): Boolean {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val now = System.currentTimeMillis()
        val saved = OrbitExecutionGate.serialized {
                val orbit = OrbitPreferences.getInstance(context).current().active
                if (orbit?.id != orbitId || orbit.deleting) return@serialized null
                // A report for a side chat that is gone goes to the main chat instead.
                val chats = AppDatabase.getDatabase(context).chatDao()
                dao.pendingDeliveries(orbitId).map { it.chatId }.distinct()
                    .filter { it != orbit.chatId && chats.getChatById(it) == null }
                    .forEach { dao.movePendingDeliveries(orbitId, it, orbit.chatId) }
                val pending = dao.pendingDeliveries(orbitId).filter { dueAt(context, it, now) <= now }
                // One message goes to one chat; reports for other chats follow on the next pass.
                // A chat that was half posted goes first; a chat with nothing left to post is skipped.
                val groups = pending.groupBy { it.chatId }.values
                    .sortedByDescending { group -> group.any { it.messageId != null } }
                var batch = emptyList<com.ai.assistance.operit.data.model.OrbitDeliveryEntity>()
                for (due in groups) {
                    val existing = due.firstOrNull { it.messageId != null }?.messageId
                    val selected = if (existing == null) due.take(6) else due.filter { it.messageId == existing }
                    batch = ChatHistoryManager.getInstance(context).appendOrbitDeliveryBatch(selected.map { it.id })
                    if (batch.isNotEmpty()) break
                }
                val first = batch.firstOrNull() ?: return@serialized null
                if (batch.any { !it.notificationSent }) notify(context, first.copy(
                    id = "message-${first.messageId}",
                    body = batch.joinToString("\n\n") { if (batch.size == 1) it.body else "${it.title}\n${it.body}" }))
                dao.markBatchDelivered(orbitId, checkNotNull(first.messageId))
                first
            }
            if (saved != null) {
                unread.update { it + saved.chatId }
                val runtimes = ChatRuntimeHolder.getInstance(context)
                ChatRuntimeSlot.entries.forEach { slot ->
                    val core = runtimes.getCore(slot)
                    if (core.currentChatId.value == saved.chatId) {
                        core.getChatHistoryDelegate().reloadChatMessagesSmart(saved.chatId)
                    }
                }
            }
            return saved != null
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

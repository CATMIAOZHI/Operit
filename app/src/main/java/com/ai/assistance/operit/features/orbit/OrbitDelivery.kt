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
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.pet.PetTasks
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.WaifuMessageProcessor
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object OrbitDelivery {
    internal suspend fun dueAt(context: Context, item: com.ai.assistance.operit.data.model.OrbitDeliveryEntity, now: Long): Long {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val policy = dao.notificationPolicy(item.orbitId)
            ?: com.ai.assistance.operit.data.model.OrbitNotificationEntity(item.orbitId)
        return OrbitDeliveryPolicy.nextAllowed(if (item.messageId == null) policy else policy.copy(intervalMinutes = 0),
            item.createdAt, item.messageId == null && dao.task(item.taskId)?.notifyPolicy == "DIGEST", now)
    }

    /**
     * When Orbit may be woken for a due task, by the same notification settings its reports
     * follow (quiet hours, interval, digest time). "Run now" is the user asking, so it goes at once.
     */
    internal suspend fun wakeAllowedAt(context: Context, task: com.ai.assistance.operit.data.model.OrbitTaskEntity,
        event: com.ai.assistance.operit.data.model.OrbitEventEntity, now: Long): Long {
        if (event.id.contains(":manual:")) return now
        val policy = AppDatabase.getDatabase(context).orbitTaskDao().notificationPolicy(task.orbitId)
            ?: com.ai.assistance.operit.data.model.OrbitNotificationEntity(task.orbitId)
        return OrbitDeliveryPolicy.nextAllowed(policy, event.createdAt, task.notifyPolicy == "DIGEST", now,
            graceMs = DIGEST_WAKE_GRACE_MS)
    }

    /** A task queued up to this long after the digest time still wakes Orbit that morning. */
    private const val DIGEST_WAKE_GRACE_MS = 15 * 60_000L

    /** Both task lanes hand reports over; one at a time, so a report is never handed over twice. */
    private val sending = kotlinx.coroutines.sync.Mutex()

    /**
     * Hands due reports to Orbit, one hidden notice per chat (the main chat and each side chat).
     * Orbit then tells the user in its own words; a report is never posted as canned text.
     * It stops starting new hand-offs once [budgetMs] would be passed, so a background worker
     * ends in time; what is left goes on the next wake-up.
     */
    suspend fun sendPending(context: Context, orbitId: String, budgetMs: Long = 4 * 60_000L) {
        if (!sending.tryLock()) return
        try {
            val end = android.os.SystemClock.elapsedRealtime() + budgetMs
            // Each chat is tried once per pass: a busy chat waits for the next wake-up, and the
            // reports for the other chats still go now.
            val busy = HashSet<String>()
            repeat(1 + com.ai.assistance.operit.data.preferences.OrbitSettings.MAX_SIDE_CHATS) {
                val left = end - android.os.SystemClock.elapsedRealtime()
                if (left < IDLE_WAIT_MS + 30_000) return
                if (!sendOnce(context, orbitId, minOf(TOTAL_WAIT_MS, left), busy)) return
            }
        } finally {
            sending.unlock()
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

    /** How long a report waits for a busy chat before it is retried on a later wake-up. */
    private const val IDLE_WAIT_MS = 60_000L
    /** How long the whole hand-off may take, Orbit's reply included (to show it as a notification). */
    private const val TOTAL_WAIT_MS = 6 * 60_000L

    /**
     * False when there was nothing left to hand over. A chat that stayed busy is added to [busy]
     * and skipped on the following passes.
     */
    private suspend fun sendOnce(context: Context, orbitId: String, totalWaitMs: Long, busy: MutableSet<String>): Boolean {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val now = System.currentTimeMillis()
        val picked = OrbitExecutionGate.serialized {
                val orbit = OrbitPreferences.getInstance(context).current().active
                if (orbit?.id != orbitId || orbit.deleting) return@serialized null
                // A report for a side chat that is gone goes to the main chat instead.
                val chats = AppDatabase.getDatabase(context).chatDao()
                dao.pendingDeliveries(orbitId).map { it.chatId }.distinct()
                    .filter { it != orbit.chatId && chats.getChatById(it) == null }
                    .forEach { dao.movePendingDeliveries(orbitId, it, orbit.chatId) }
                // A draft that ran out while it waited is moot; Orbit never hears of it.
                dao.expirePending(orbitId, now)
                val pending = dao.pendingDeliveries(orbitId).filter { dueAt(context, it, now) <= now }
                // A report already posted as a message by an older build is done.
                pending.mapNotNull { it.messageId }.distinct().forEach { dao.markBatchDelivered(orbitId, it) }
                // One notice goes to one chat; reports for other chats follow on the next pass.
                for (due in pending.filter { it.messageId == null && it.chatId !in busy }.groupBy { it.chatId }.values) {
                    val batch = dao.validPending(due.take(6).map { it.id })
                    if (batch.isNotEmpty()) return@serialized orbit to batch
                }
                null
            }
        val (orbit, batch) = picked ?: return false
        val chatId = batch.first().chatId
        val ids = batch.map { it.id }
        val earlier = dao.toldSince(orbitId, now - TOLD_LOOKBACK_MS, TOLD_LIMIT)
        val preferences = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                OrbitFiles.readHead(OrbitFiles.of(context, orbitId).fileFor(OrbitFiles.PROACTIVE), 3000)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
        val notice = OrbitWake.resultNotice(com.ai.assistance.operit.util.LocaleUtils.usesEnglishPrompt(context), batch,
            earlier, preferences)
        // Marked as told the moment Orbit takes the notice, so a crash never tells it twice.
        val told = OrbitWake.tell(context, orbit, chatId, notice, IDLE_WAIT_MS, totalWaitMs,
            // A report cancelled or replaced while the chat was busy is not told.
            ready = { OrbitExecutionGate.serialized { dao.validPending(ids).size == ids.size } },
            // Tried twice: a report left pending after it went out would be told again later.
            onSent = {
                try { OrbitExecutionGate.serialized { dao.markHandedToOrbit(ids) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { OrbitExecutionGate.serialized { dao.markHandedToOrbit(ids) } }
            })
        if (told == OrbitWake.Told.SPOKE) dao.touchDelivered(orbitId, System.currentTimeMillis())
        // An item Orbit did not register a call for counts as not sent (Muse: undecided means no).
        // A call that comes later, from a reply still running, still replaces this.
        if (told.sent) runCatching { dao.markUnregistered(ids, System.currentTimeMillis()) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        busy += chatId
        return true
    }

    /** How far back the notice lists what Orbit already told, to catch the same matter twice. */
    private const val TOLD_LOOKBACK_MS = 14L * 24 * 60 * 60_000
    private const val TOLD_LIMIT = 15

    /**
     * Orbit said something on its own (a due task, a background result): mark the chat unread and,
     * when the app is not open, show what it said as a notification that opens the chat.
     */
    fun announce(context: Context, chatId: String, title: String, text: String) {
        unread.update { it + chatId }
        // Reading the picture may take a moment, so the notification is built off the caller's thread.
        val key = "said-$chatId-${System.currentTimeMillis()}"
        notifyScope.launch { runCatching { notify(context.applicationContext, key, chatId, title, text) } }
    }

    private val notifyScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Opens [chatId] in the Orbit page when tapped; [key] keeps each notification's intent apart. */
    fun openChatIntent(context: Context, chatId: String, key: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = PetTasks.ACTION_OPEN_CHAT
            data = Uri.parse("operit://orbit-delivery/${Uri.encode(key)}")
            putExtra(PetTasks.EXTRA_CHAT_ID, chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The notification channel for what Orbit says while the app is in the background. */
    const val CHANNEL = "orbit_check_in"

    /** False when the user turned off Orbit's own notification channel in the system settings. */
    fun channelOn(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return true
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notify(context: Context, key: String, chatId: String, title: String, body: String) {
        if (ActivityLifecycleManager.getCurrentActivity() != null ||
            !NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        // The Orbit's own switch: off keeps what it says in the chat only.
        val orbit = runCatching { OrbitPreferences.getInstance(context).loadedSettingsBlocking().orbitForChat(chatId) }.getOrNull()
        if (orbit?.messageNotifications == false) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel,
            context.getString(R.string.orbit_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val pending = openChatIntent(context, chatId, key)
        val text = WaifuMessageProcessor.cleanContentForWaifu(body).ifBlank { body }
        manager.notify("orbit:$key", 0, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_simple_foreground).setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .apply {
                orbit?.let { OrbitNotificationIcon.of(it) { uri ->
                    com.ai.assistance.operit.api.chat.AIForegroundService.loadBitmapFromUri(context, uri, OrbitNotificationIcon.SIZE)
                } }?.let(::setLargeIcon)
            }
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build())
    }
}

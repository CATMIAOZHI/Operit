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
    /**
     * The delivery rules every Orbit follows (no quiet hours, reports at least 15 minutes apart,
     * digests at 9:00); only when this Orbit last reported is read from its stored row.
     */
    private suspend fun policyOf(context: Context, orbitId: String) =
        com.ai.assistance.operit.data.model.OrbitNotificationEntity(
            orbitId,
            lastDeliveredAt = AppDatabase.getDatabase(context).orbitTaskDao().notificationPolicy(orbitId)?.lastDeliveredAt ?: 0,
        )

    internal suspend fun dueAt(context: Context, item: com.ai.assistance.operit.data.model.OrbitDeliveryEntity, now: Long): Long {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val policy = policyOf(context, item.orbitId)
        return maxOf(item.retryAt,
            OrbitDeliveryPolicy.nextAllowed(if (item.messageId == null) policy else policy.copy(intervalMinutes = 0),
                item.createdAt, item.messageId == null && dao.task(item.taskId)?.notifyPolicy == "DIGEST", now))
    }

    /**
     * When Orbit may be woken for a due task, by the same rules its reports follow (interval,
     * digest time). "Run now" is the user asking, so it goes at once.
     */
    internal suspend fun wakeAllowedAt(context: Context, task: com.ai.assistance.operit.data.model.OrbitTaskEntity,
        event: com.ai.assistance.operit.data.model.OrbitEventEntity, now: Long): Long {
        if (event.id.contains(":manual:")) return now
        val policy = policyOf(context, task.orbitId)
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
            postWaiting(context, orbitId)
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
     * A task stopped for the user's answer asks them itself: the request Orbit wrote for the user
     * when the task stopped goes into the chat as Orbit's message, under the task's name, with a
     * notification. It is not handed to Orbit as a draft to weigh, so it cannot be left out. Quiet
     * hours hold it like any report; a chat in the middle of a reply gets it a little later.
     */
    private suspend fun postWaiting(context: Context, orbitId: String) {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val holder = com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context)
        val slots = com.ai.assistance.operit.api.chat.ChatRuntimeSlot.entries
        dao.reopenUntoldWaitingRequestsIfAny(orbitId)
        // Read once; each is checked again right before it is posted. Only the ones really posted
        // count toward the limit, so ones that must wait never hold back one that is due.
        var left = MAX_WAITING_PER_PASS
        for (id in dao.pendingWaitingRequests(orbitId)) {
            if (left <= 0) break
            val replying = slots.flatMap { holder.getCore(it).activeStreamingChatIds.value }.toSet()
            val chatId = OrbitExecutionGate.serialized {
                val orbit = OrbitPreferences.getInstance(context).current().active
                if (orbit?.id != orbitId || orbit.deleting) return@serialized null
                val now = System.currentTimeMillis()
                var ask = dao.delivery(id)?.takeIf { it.state == "PENDING" && dao.waitingRequest(it.id) }
                    ?: return@serialized ""
                // Quiet hours hold even the notification of one already in the chat.
                if (dueAt(context, ask, now) > now) return@serialized ""
                // A side chat that is gone (also one deleted after the request was posted there): the
                // main chat gets it, as a new message.
                suspend fun chatGone(chatId: String) =
                    AppDatabase.getDatabase(context).chatDao().getChatById(chatId) == null
                if (ask.chatId != orbit.chatId && chatGone(ask.chatId)) {
                    dao.movePendingDeliveries(orbitId, ask.chatId, orbit.chatId)
                    ask = dao.delivery(id) ?: return@serialized ""
                }
                // Posted before the app stopped, but the message was deleted since: post it again.
                if (dao.forgetMissingMessage(id) > 0) ask = dao.delivery(id) ?: return@serialized ""
                // A request not (or no longer) in any chat goes to the chat its task is in now.
                if (ask.messageId == null && dao.followTaskChat(id, orbit.chatId) == 1) ask = dao.delivery(id) ?: return@serialized ""
                if (ask.messageId == null && ask.chatId in replying) {
                    dao.postponeDelivery(id, now + BUSY_RETRY_MS)
                    return@serialized ""
                }
                val text = waitingText(context, dao, ask)
                // Already a message when the app stopped before it was marked: only the notification is left.
                val item = if (ask.messageId != null) ask else
                    com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
                        .appendOrbitDeliveryBatch(listOf(id), orbit.name, text)
                        .firstOrNull()?.takeIf { it.messageId != null } ?: return@serialized ""
                // Posted, told and marked in one go, so deleting a side chat cannot come in between;
                // a stop before the mark tells it again rather than never.
                unread.update { it + item.chatId }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { notify(context.applicationContext, "said-${item.chatId}-$id", item.chatId, orbit.name, text) }
                }
                // The chat history screen deletes chats without this gate, so the mark itself checks the
                // message is still there; if not, the request is posted again on the next pass.
                if (dao.markWaitingPosted(id, checkNotNull(item.messageId)) == 0) return@serialized ""
                item.chatId
            } ?: return
            if (chatId.isEmpty()) continue
            left--
            slots.forEach { slot ->
                val core = holder.getCore(slot)
                if (core.currentChatId.value == chatId) core.reloadChatMessagesSmart(chatId)
            }
        }
    }

    /** What the user reads: the task's name, its request, and where to act when the chat is not the place. */
    private suspend fun waitingText(context: Context, dao: com.ai.assistance.operit.data.dao.OrbitTaskDao,
        ask: com.ai.assistance.operit.data.model.OrbitDeliveryEntity): String = buildString {
        append(context.getString(R.string.orbit_waiting_message, ask.title.trim(), ask.body.trim()))
        // Both can apply: check first, then the page.
        if (dao.task(ask.taskId)?.state == "VERIFY") append("\n\n").append(context.getString(R.string.orbit_waiting_verify_hint))
        if (!dao.input(ask.taskId)?.requestedUrl.isNullOrBlank()) append("\n\n").append(context.getString(R.string.orbit_waiting_page_hint))
    }

    private const val MAX_WAITING_PER_PASS = 10
    /** A chat in the middle of a reply is asked again after this. */
    private const val BUSY_RETRY_MS = 2 * 60_000L
    /**
     * Chats that got a report the user has not looked at yet, kept for this app session. The
     * Orbit page shows a dot for them and clears a chat once it is on screen.
     */
    private val unread = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val unreadChats: kotlinx.coroutines.flow.StateFlow<Set<String>> = unread

    fun markSeen(chatId: String) {
        unread.update { it - chatId }
    }

    /**
     * The user is looking at [chatId]: its notifications go, like a messenger's. Covers both
     * Orbit's replies and what it said on its own.
     */
    fun clearNotifications(context: Context, chatId: String) {
        com.ai.assistance.operit.api.chat.AIForegroundService.cancelReplyNotification(context, chatId)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val said = "orbit:said-$chatId-"
        runCatching { manager.activeNotifications }.getOrNull()?.forEach {
            if (it.tag?.startsWith(said) == true) manager.cancel(it.tag, it.id)
        }
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
                // No live listener means the process died or bookkeeping failed. Keep the draft
                // retryable; a hand-off alone is never evidence that the user received it.
                val abandoned = dao.handingDeliveries(orbitId).filterNot { OrbitWake.isWatching(it.handoffTurnId) }
                if (abandoned.isNotEmpty()) dao.retryDeliveries(abandoned.map { it.id }, now, interrupted = true)
                // A task moved to another chat takes its unposted results with it.
                dao.followTaskChats(orbitId, orbit.chatId)
                // A report for a side chat that is gone goes to the main chat instead.
                val chats = AppDatabase.getDatabase(context).chatDao()
                dao.pendingDeliveries(orbitId).map { it.chatId }.distinct()
                    .filter { it != orbit.chatId && chats.getChatById(it) == null }
                    .forEach { dao.movePendingDeliveries(orbitId, it, orbit.chatId) }
                // A draft that ran out while it waited is moot; Orbit never hears of it.
                dao.expirePending(orbitId, now)
                val pending = dao.pendingDeliveries(orbitId).filter { dueAt(context, it, now) <= now }
                // A report already posted as a message by an older build is done.
                // A waiting task's request is left to postWaiting, which still owes its notification.
                pending.filter { it.messageId != null && !dao.waitingRequest(it.id) }
                    .mapNotNull { it.messageId }.distinct().forEach { dao.markPostedReports(orbitId, it) }
                // One notice goes to one chat; reports for other chats follow on the next pass.
                // A waiting task's request is posted by postWaiting, never handed over as a draft.
                for (due in pending.filter { it.messageId == null && it.chatId !in busy && !dao.waitingRequest(it.id) }.groupBy { it.chatId }.values) {
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
        val turn = OrbitChatDecision.Turn()
        val told = OrbitWake.tell(context, orbit, chatId, notice, IDLE_WAIT_MS, totalWaitMs, decision = turn,
            // A report cancelled, replaced or moved to another chat while this one was busy is not
            // told here, and stops the wait for this chat; the next pass picks it up where it now belongs.
            ready = { OrbitExecutionGate.serialized { dao.validPending(ids).let { it.size == ids.size && it.all { d -> d.chatId == chatId } } } },
            stillWanted = { dao.countStillPending(ids, chatId, System.currentTimeMillis()) == ids.size },
            onSending = {
                OrbitExecutionGate.serialized {
                    if (!dao.validPending(ids).let { it.size == ids.size && it.all { d -> d.chatId == chatId } }) {
                        throw OrbitWake.Withdrawn()
                    }
                    dao.markHandedToOrbit(ids, turn.turnId)
                }
            },
            onFinished = { outcome ->
                OrbitExecutionGate.serialized {
                    val finishedAt = System.currentTimeMillis()
                    if (outcome == OrbitWake.Told.FAILED) dao.retryDeliveries(ids, finishedAt)
                    // Stopped before it finished: what Orbit had not settled is told again later,
                    // marked so it first checks whether the chat already says it.
                    else if (outcome == OrbitWake.Told.CANCELLED) dao.retryDeliveries(ids, finishedAt, interrupted = true)
                    else {
                        dao.finishDeliveries(ids, turn.turnId, finishedAt)
                        if (outcome == OrbitWake.Told.SPOKE) dao.touchDelivered(orbitId, finishedAt)
                    }
                }
                OrbitTaskScheduler.reschedule(context, fromWorker = true)
            })
        if (told == OrbitWake.Told.BUSY) {
            // Only the ones still meant for this chat wait for it; one moved elsewhere goes on at once.
            OrbitExecutionGate.serialized {
                val stillHere = dao.validPending(ids).filter { it.chatId == chatId }.map { it.id }
                if (stillHere.isNotEmpty()) dao.retryDeliveries(stillHere, System.currentTimeMillis())
            }
        }
        // A batch that changed while it waited may leave others for this chat that can go right away.
        if (told != OrbitWake.Told.DROPPED) busy += chatId
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

    const val EXTRA_OPEN_ORBIT = "orbit_open_page"

    /** Opens the Orbit page at the chat the user was on last, whichever it is. */
    fun openOrbitIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = PetTasks.ACTION_OPEN_CHAT
            data = Uri.parse("operit://orbit-delivery/ongoing-page")
            putExtra(EXTRA_OPEN_ORBIT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private const val NOTIFY_BLOCKED = "notify_blocked"

    private fun tellBlocked(context: Context, orbitId: String) = OrbitNotices.once(context, orbitId, NOTIFY_BLOCKED,
        "[orbit-notify] 通知发不出去\n这是 App 发给你的说明，不是用户说的，用户也看不到。刚才 App 在后台想把你说的话用通知告诉用户，" +
            "但系统把 Orbit 的通知（或它的消息通知渠道）关掉了，用户没收到。找合适的时机用一句话告诉用户，并调用 " +
            "orbit_permission_card permission=notify 放一张卡片让他打开。他以前说过不要通知的话，就调用 orbit_nothing_to_do 保持安静。",
        "[orbit-notify] Notifications cannot get through\nThis is a note from the app, not from the user; they cannot see it. " +
            "The app just tried to show what you said as a notification, but the system has Orbit's notifications (or its message " +
            "channel) turned off, so the user did not get it. When it fits, tell them in one line and call orbit_permission_card " +
            "permission=notify to put a card in the chat for turning them on. If they said before they don't want notifications, " +
            "call orbit_nothing_to_do to stay quiet.")

    /** The notification channel for what Orbit says while the app is in the background. */
    const val CHANNEL = "orbit_check_in"

    /** False when the user turned off Orbit's own notification channel in the system settings. */
    fun channelOn(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26) return true
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun notify(context: Context, key: String, chatId: String, title: String, body: String) {
        if (ActivityLifecycleManager.getCurrentActivity() != null) return
        // The Orbit's own switch: off keeps what it says in the chat only.
        val settings = runCatching { OrbitPreferences.getInstance(context).loadedSettingsBlocking() }.getOrNull()
        val orbit = settings?.orbitForChat(chatId)
        if (orbit?.messageNotifications == false) return
        // Blocked by the system (app notifications or Orbit's channel off): Orbit hears it once, so it can ask.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() || !channelOn(context)) {
            orbit?.let { tellBlocked(context, it.id) }
            return
        }
        orbit?.let { OrbitNotices.clear(context, it.id, NOTIFY_BLOCKED) }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel,
            context.getString(R.string.orbit_notification_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val pending = openChatIntent(context, chatId, key)
        // Hide the preview whenever the lock is enabled, including its background grace period.
        val privatePreview = settings == null || settings.appLock
        val text = if (privatePreview) context.getString(R.string.orbit_chats_unread)
            else WaifuMessageProcessor.cleanContentForWaifu(body).ifBlank { body }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_orbit).setContentTitle(title)
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .apply {
                orbit?.let { OrbitNotificationIcon.of(it) { uri ->
                    com.ai.assistance.operit.api.chat.AIForegroundService.loadBitmapFromUri(context, uri, OrbitNotificationIcon.SIZE)
                } }?.let(::setLargeIcon)
            }
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
        // Reading the picture took a moment; if the app came to the front meanwhile, it shows the chat.
        if (ActivityLifecycleManager.getCurrentActivity() != null) return
        manager.notify("orbit:$key", 0, notification)
    }
}

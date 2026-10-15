package com.ai.assistance.operit.features.orbit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitRunOutcome
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.pet.PetTasks
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatTurnDispatchRequest
import com.ai.assistance.operit.services.core.ChatTurnDispatchResult
import com.ai.assistance.operit.services.core.ChatTurnDispatcher
import com.ai.assistance.operit.services.core.ChatTurnSession
import com.ai.assistance.operit.services.core.ChatTurnTerminalSignal
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.ui.permissions.UnattendedChats
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.FileUtils
import com.ai.assistance.operit.util.stream.plugins.StreamXmlPlugin
import com.ai.assistance.operit.util.stream.splitBy
import com.ai.assistance.operit.util.stream.stream as charStream
import com.ai.assistance.operit.util.WaifuMessageProcessor
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Orbit: a long-term companion with its own chat, name, look, Soul.md and a daily check-in.
 *
 * A role card only seeds a new Orbit: its persona is copied into Soul.md once, and the Orbit chat
 * then runs under the default role card with Soul.md as its persona, so editing or deleting the
 * role card does not change the Orbit. The user can make one Orbit per role card, but only one runs
 * at a time; the others are paused and keep everything.
 *
 * A check-in runs one turn in the running Orbit's chat without storing the prompt. The model either
 * writes a short message, which lands in the chat and a notification, or answers [SILENT_MARKER]
 * and Orbit stays quiet.
 */
object OrbitCompanion {
    private const val TAG = "OrbitCompanion"
    const val SILENT_MARKER = "ORBIT_SILENT"

    private const val SCHEDULED_WORK_NAME = "orbit_daily_check_in"
    private const val MANUAL_WORK_NAME = "orbit_manual_check_in"
    private const val NOTIFICATION_CHANNEL_ID = "orbit_check_in"
    private const val NOTIFICATION_TAG = "orbit_check_in"
    private const val NOTIFICATION_ID = 0x0B17
    private const val OPEN_CHAT_REQUEST_CODE = 0x0B17
    private const val CHAT_CREATE_TIMEOUT_MS = 15_000L
    private const val RESPONSE_STREAM_ACQUIRE_TIMEOUT_MS = 30_000L
    // Tools may run during a check-in, so the whole turn gets more time than a plain reply.
    private const val RESPONSE_TIMEOUT_MS = 300_000L
    private const val TURN_END_TIMEOUT_MS = 30_000L
    private const val CHAT_IDLE_TIMEOUT_MS = 60_000L
    private const val MIN_SCHEDULE_DELAY_MS = 60_000L

    private val chatMutex = Mutex()
    private val runMutex = Mutex()
    private val runningState = MutableStateFlow(false)

    /** The check-in turn in flight and its Orbit, so pausing or deleting can stop it. */
    @Volatile
    private var inFlight: Pair<String, ChatTurnSession>? = null

    /** True while a check-in is running in this process. */
    val running: StateFlow<Boolean> = runningState.asStateFlow()

    private fun core(context: Context): ChatServiceCore =
        ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)

    /**
     * Starts the Orbit made from [roleCardId], making it the first time, and pauses the one that
     * was running.
     */
    suspend fun startFromRoleCard(context: Context, roleCardId: String): OrbitProfile {
        val appContext = context.applicationContext
        val profile = chatMutex.withLock {
            val preferences = OrbitPreferences.getInstance(appContext)
            val existing = preferences.current().orbitFromRoleCard(roleCardId)
            val profile =
                if (existing != null) ensureChatLocked(appContext, existing)
                else createLocked(appContext, roleCardId)
            preferences.setActive(profile.id)
            profile
        }
        applySchedule(appContext)
        return profile
    }

    /** Starts the paused Orbit [orbitId] and pauses the one that was running. */
    suspend fun resume(context: Context, orbitId: String): OrbitProfile? {
        val appContext = context.applicationContext
        val profile = chatMutex.withLock {
            val preferences = OrbitPreferences.getInstance(appContext)
            val existing = preferences.current().orbits.firstOrNull { it.id == orbitId }
                ?: return@withLock null
            ensureChatLocked(appContext, existing).also { preferences.setActive(it.id) }
        }
        stopInFlightUnlessActive(appContext)
        applySchedule(appContext)
        return profile
    }

    /** Pauses the running Orbit. It keeps everything and can be started again later. */
    suspend fun pause(context: Context) {
        OrbitPreferences.getInstance(context.applicationContext).setActive(null)
        stopInFlightUnlessActive(context)
        applySchedule(context)
    }

    /** Deletes the Orbit [orbitId] together with its chat and Soul.md. */
    suspend fun remove(context: Context, orbitId: String) {
        val appContext = context.applicationContext
        val preferences = OrbitPreferences.getInstance(appContext)
        val orbit = preferences.current().orbits.firstOrNull { it.id == orbitId } ?: return
        val chatIds = mutableSetOf(orbit.chatId).apply { addAll(orbit.ownedChatIds) }
        chatIds.addAll(preferences.current().chatOwners.filterValues { it == orbitId }.keys)
        val chats = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(appContext)
            .chatDao().getAllChatsDirectly()
        chatIds.addAll(orbitDescendants(chatIds, chats.map { it.id to it.parentChatId }))
        chatIds.forEach { preferences.rememberChatOwner(it, orbitId) }
        preferences.markDeleting(orbitId)
        stopInFlightUnlessActive(appContext)
        com.ai.assistance.operit.core.agent.SubagentCoordinator.getInstance(appContext)
            .withChatDeletionsPrepared(chatIds) {
                val runtimes = ChatRuntimeHolder.getInstance(appContext)
                ChatRuntimeSlot.entries.forEach { slot ->
                    chatIds.forEach { runtimes.getCore(slot).prepareChatDeletion(it) }
                }
                orbit.memorySpaceId?.let { UserPreferencesManager.getInstance(appContext).deleteMemorySpace(it) }
                preferences.removeOrbit(orbitId)
            }
        core(appContext).deleteChatHistory(orbit.chatId)
        applySchedule(appContext)
    }

    /** Stops a check-in whose Orbit is no longer the running one. */
    private suspend fun stopInFlightUnlessActive(context: Context) {
        val (orbitId, session) = inFlight ?: return
        val activeId = OrbitPreferences.getInstance(context.applicationContext).current().activeOrbitId
        if (activeId != orbitId) runCatching { session.cancelAndAwaitTermination() }
    }

    /** The running Orbit with a chat that exists, or null while every Orbit is paused. */
    suspend fun ensureActiveChat(context: Context): OrbitProfile? {
        val appContext = context.applicationContext
        return chatMutex.withLock {
            val active = OrbitPreferences.getInstance(appContext).current().active
                ?: return@withLock null
            ensureChatLocked(appContext, active)
        }
    }

    /** Makes a new Orbit from a role card: its persona becomes Soul.md, its avatar is copied. */
    private suspend fun createLocked(context: Context, roleCardId: String): OrbitProfile {
        val cardManager = CharacterCardManager.getInstance(context)
        require(roleCardId in cardManager.characterCardListFlow.first()) {
            "Role card not found: $roleCardId"
        }
        val card = cardManager.getCharacterCard(roleCardId)
        val orbitId = UUID.randomUUID().toString()
        val preferences = OrbitPreferences.getInstance(context)
        preferences.writeSoul(orbitId, cardManager.combinePrompts(roleCardId).trim())
        val avatarUri =
            runCatching {
                UserPreferencesManager.getInstance(context)
                    .getResolvedAiAvatarForCharacterCardFlow(roleCardId)
                    .first()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { FileUtils.copyFileToInternalStorage(context, Uri.parse(it), "orbit_avatar") }
                    ?.toString()
            }.getOrNull()
        val chatId = createChat(context, roleCardId, card.name)
        val profile =
            OrbitProfile(
                id = orbitId,
                sourceRoleCardId = roleCardId,
                chatId = chatId,
                // Orbit starts with its role's name and may pick its own later.
                name = card.name.take(OrbitSettings.MAX_NAME_LENGTH),
                avatarUri = avatarUri,
                createdAt = System.currentTimeMillis(),
            )
        preferences.putOrbit(profile)
        val memorySpaceId = OrbitMemoryScope.ensureInitialized(context, profile)
        return profile.copy(memorySpaceId = memorySpaceId)
    }

    /** Makes the Orbit's chat again when the user deleted it; everything else stays. */
    private suspend fun ensureChatLocked(context: Context, orbit: OrbitProfile): OrbitProfile {
        val spaceId = OrbitMemoryScope.ensureInitialized(context, orbit)
        if (core(context).chatExists(orbit.chatId)) return orbit.copy(memorySpaceId = spaceId)
        val recreated = orbit.copy(chatId = createChat(context, null, orbit.name), memorySpaceId = spaceId,
            ownedChatIds = orbit.ownedChatIds + orbit.chatId)
        OrbitPreferences.getInstance(context).putOrbit(recreated)
        return recreated
    }

    /**
     * Creates an Orbit chat. A new Orbit's chat starts with its role card's opening message; after
     * that the chat no longer follows the role card.
     */
    private suspend fun createChat(context: Context, roleCardId: String?, title: String): String {
        val core = core(context)
        val created = CompletableDeferred<String>()
        core.createNewChat(
            inheritGroupFromCurrent = false,
            setAsCurrentChat = false,
            characterCardId = roleCardId ?: CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
            onCreated = { chatId -> created.complete(chatId) },
        )
        val chatId = withTimeout(CHAT_CREATE_TIMEOUT_MS) { created.await() }
        core.getChatHistoryDelegate()
            .updateChatTitleAndWait(chatId, context.getString(R.string.orbit_chat_title, title))
        return chatId
    }

    /** Runs one check-in now. Concurrent calls wait for the running one instead of doubling up. */
    suspend fun checkIn(context: Context): OrbitRunOutcome = runMutex.withLock {
        val appContext = context.applicationContext
        val preferences = OrbitPreferences.getInstance(appContext)
        runningState.value = true
        try {
            val (outcome, detail) =
                try {
                    runCheckIn(appContext)
                } catch (cancelled: CancellationException) {
                    when {
                        cancelled is TimeoutCancellationException ->
                            OrbitRunOutcome.FAILED to appContext.getString(R.string.orbit_run_timeout)
                        // Only this coroutine being stopped ends the run silently; a cancel from the
                        // chat (the user pressing stop) is still a finished run.
                        currentCoroutineContext().isActive ->
                            OrbitRunOutcome.FAILED to appContext.getString(R.string.orbit_run_cancelled)
                        else -> throw cancelled
                    }
                } catch (error: Exception) {
                    AppLogger.e(TAG, "Orbit check-in failed", error)
                    OrbitRunOutcome.FAILED to
                        (error.message?.takeIf { it.isNotBlank() }
                            ?: appContext.getString(R.string.orbit_run_failed_unknown))
                }
            preferences.recordRun(outcome, detail)
            outcome
        } finally {
            runningState.value = false
        }
    }

    private suspend fun runCheckIn(context: Context): Pair<OrbitRunOutcome, String> {
        val orbit =
            ensureActiveChat(context)
                ?: return OrbitRunOutcome.FAILED to context.getString(R.string.orbit_run_paused)
        val settings = OrbitPreferences.getInstance(context).current()
        val chatId = orbit.chatId
        val core = core(context)

        // Wait for a turn the user may be running here, so it is not treated as unattended.
        withTimeout(CHAT_IDLE_TIMEOUT_MS) {
            core.activeStreamingChatIds.first { chatId !in it }
        }
        UnattendedChats.begin(chatId)
        try {
            val dispatch =
                ChatTurnDispatcher().dispatch(
                    core = core,
                    request =
                        ChatTurnDispatchRequest(
                            chatId = chatId,
                            message = buildCheckInPrompt(orbit.name, settings.focusNotes),
                            // Orbit chats run under the default card with Soul.md as the persona.
                            roleCardId = CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
                            proxySenderName = null,
                            turnOptions =
                                ChatTurnOptions(
                                    // The prompt is a trigger, so neither it nor a silent answer
                                    // belongs in the chat; a real message is added below.
                                    persistTurn = false,
                                    notifyReply = false,
                                    deliveredByTool = true,
                                    disableSummary = true,
                                    // Nobody is watching, so a tool that needs approval is refused
                                    // at once instead of waiting for the user (UnattendedChats).
                                    toolsEnabled = true,
                                ),
                            responseStreamAcquireTimeoutMs = RESPONSE_STREAM_ACQUIRE_TIMEOUT_MS,
                            responseTimeoutMs = RESPONSE_TIMEOUT_MS,
                        ),
                )
            val session =
                when (dispatch) {
                    is ChatTurnDispatchResult.Started -> dispatch.session
                    is ChatTurnDispatchResult.Failed -> return OrbitRunOutcome.FAILED to dispatch.error
                }
            inFlight = orbit.id to session
            // The user may have paused or deleted this Orbit while the turn was starting, before
            // there was a session to stop.
            if (OrbitPreferences.getInstance(context).current().activeOrbitId != orbit.id) {
                runCatching { session.cancelAndAwaitTermination() }
                return OrbitRunOutcome.FAILED to context.getString(R.string.orbit_run_cancelled)
            }

            val rawReply: String
            val signal: ChatTurnTerminalSignal
            try {
                rawReply =
                    withTimeout(session.responseTimeoutMs) {
                        val builder = StringBuilder()
                        session.responseStream.collect { chunk -> builder.append(chunk) }
                        builder.toString()
                    }
                // The stream ends before the delegate records the result, so wait for the turn's
                // end signal, with its own budget so a slow but finished reply is not thrown away.
                signal = withTimeout(TURN_END_TIMEOUT_MS) { session.awaitTerminalSignal() }
            } catch (timeout: TimeoutCancellationException) {
                runCatching { session.cancelAndAwaitTermination() }
                throw timeout
            }
            when (signal) {
                is ChatTurnTerminalSignal.Completed -> Unit
                is ChatTurnTerminalSignal.Cancelled ->
                    return OrbitRunOutcome.FAILED to context.getString(R.string.orbit_run_cancelled)
                is ChatTurnTerminalSignal.Failed -> {
                    val state = session.currentState()
                    val detail = (state as? InputProcessingState.Error)?.message ?: signal.error
                    return OrbitRunOutcome.FAILED to detail
                }
            }

            val reply =
                ChatUtils.removeThinkingContent(
                    ChatUtils.stripGeminiThoughtSignatureMeta(textAfterLastTool(rawReply))
                ).trim()
            if (reply.contains(SILENT_MARKER, ignoreCase = true)) {
                return OrbitRunOutcome.SILENT to ""
            }
            if (reply.isBlank()) {
                return OrbitRunOutcome.FAILED to context.getString(R.string.orbit_run_empty_reply)
            }
            // The user may have paused or deleted this Orbit while it was thinking.
            val latest = OrbitPreferences.getInstance(context).current()
            if (latest.activeOrbitId != orbit.id || latest.orbitForChat(chatId) == null) {
                return OrbitRunOutcome.FAILED to context.getString(R.string.orbit_run_cancelled)
            }
            val name = latest.orbitForChat(chatId)?.name?.takeIf { it.isNotBlank() } ?: orbit.name

            core.getChatHistoryDelegate()
                .addMessageToChat(
                    ChatMessage(
                        sender = "ai",
                        content = reply,
                        roleName = name,
                        provider = "",
                        modelName = "",
                    ),
                    chatIdOverride = chatId,
                )
            notifyMessage(context, chatId, name, reply)
            return OrbitRunOutcome.SPOKE to ""
        } finally {
            inFlight = null
            UnattendedChats.end(chatId)
        }
    }

    /**
     * The turn's raw text holds every step, tool calls and their results included. Orbit's message
     * is what it wrote after its last tool step; anything before was working out loud. When the
     * turn ends on a tool step, the last text written before it is the message.
     */
    private suspend fun textAfterLastTool(raw: String): String {
        val tail = StringBuilder()
        var earlierText = ""
        raw.charStream().splitBy(listOf(StreamXmlPlugin())).collect { group ->
            val piece = StringBuilder()
            group.stream.collect { piece.append(it) }
            val text = piece.toString()
            if (group.tag is StreamXmlPlugin) {
                val tagName =
                    ChatMarkupRegex.normalizeToolLikeTagName(ChatMarkupRegex.extractOpeningTagName(text))
                if (tagName == "status") return@collect
                if (tagName == "tool" || tagName == "tool_result") {
                    if (ChatUtils.removeThinkingContent(tail.toString()).isNotBlank()) {
                        earlierText = tail.toString()
                    }
                    tail.clear()
                    return@collect
                }
            }
            tail.append(text)
        }
        return tail.toString().takeIf { ChatUtils.removeThinkingContent(it).isNotBlank() } ?: earlierText
    }

    private fun buildCheckInPrompt(name: String, focusNotes: String): String {
        val now = SimpleDateFormat("yyyy-MM-dd EEEE HH:mm", Locale.getDefault()).format(Date())
        val notes = focusNotes.trim().ifBlank { "(none)" }
        return buildString {
            appendLine("[Orbit check-in · $now]")
            appendLine(
                "This is a scheduled check-in from Orbit, not a message the user just sent. " +
                    "The user will not see this text."
            )
            appendLine(
                "You are ${name.ifBlank { "the user's Orbit" }}, the user's long-term companion. " +
                    "Using what you remember about the user, your recent conversation, and the " +
                    "things the user asked you to keep an eye on, decide whether there is " +
                    "something worth saying now: a reminder that is due, care about something you " +
                    "talked about, or a natural greeting."
            )
            appendLine(
                "You may use your tools when they help, for example to look up memory or check " +
                    "something before you speak. Nobody is watching right now, so a tool that " +
                    "needs the user's approval will be refused; do without it."
            )
            appendLine()
            appendLine("Things the user asked you to keep an eye on:")
            appendLine(notes)
            appendLine()
            appendLine(
                "If there is something worth saying, write only the message itself, in your own " +
                    "voice and in the language you usually use with the user, at most three " +
                    "short sentences. Do not mention check-ins, schedules or background tasks."
            )
            append("If there is nothing worth saying, reply with exactly $SILENT_MARKER and nothing else.")
        }
    }

    private fun notifyMessage(context: Context, chatId: String, title: String, message: String) {
        // The message is already in the chat; a notification only helps when the app is closed.
        if (ActivityLifecycleManager.getCurrentActivity() != null) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val systemManager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            systemManager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.orbit_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.orbit_notification_channel_desc)
                }
            )
            val openChatIntent =
                Intent(context, MainActivity::class.java).apply {
                    action = PetTasks.ACTION_OPEN_CHAT
                    putExtra(PetTasks.EXTRA_CHAT_ID, chatId)
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            val pendingIntent =
                PendingIntent.getActivity(
                    context,
                    OPEN_CHAT_REQUEST_CODE,
                    openChatIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val plainText = WaifuMessageProcessor.cleanContentForWaifu(message).ifBlank { message }
            val notification =
                NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_simple_foreground)
                    .setContentTitle(title)
                    .setContentText(plainText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(plainText))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .build()
            systemManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            AppLogger.w(TAG, "Orbit notification was not allowed: ${error.message}")
        }
    }

    /** Milliseconds from [nowMillis] to the next [minuteOfDay], at least a minute ahead. */
    internal fun delayUntilNextCheckIn(nowMillis: Long, minuteOfDay: Int): Long {
        val target =
            Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                set(Calendar.MINUTE, minuteOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        if (target.timeInMillis - nowMillis < MIN_SCHEDULE_DELAY_MS) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - nowMillis
    }

    private fun enqueueScheduled(context: Context, minuteOfDay: Int, policy: ExistingWorkPolicy) {
        val delayMs = delayUntilNextCheckIn(System.currentTimeMillis(), minuteOfDay)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SCHEDULED_WORK_NAME,
            policy,
            OneTimeWorkRequestBuilder<OrbitCheckInWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build(),
        )
    }

    /** Daily check-ins run only while one Orbit is running and the user turned them on. */
    private fun OrbitSettings.wantsCheckIns(): Boolean = proactiveEnabled && activeOrbitId != null

    /** Applies changed settings: schedules the next check-in, or cancels it when turned off. */
    suspend fun applySchedule(context: Context) {
        val settings = OrbitPreferences.getInstance(context.applicationContext).current()
        if (settings.wantsCheckIns()) {
            enqueueScheduled(context, settings.checkInMinuteOfDay, ExistingWorkPolicy.REPLACE)
        } else {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(SCHEDULED_WORK_NAME)
        }
    }

    /** Restores the schedule at app start without moving a check-in that is already queued. */
    suspend fun restoreSchedule(context: Context) {
        val settings = OrbitPreferences.getInstance(context.applicationContext).current()
        if (settings.wantsCheckIns()) {
            enqueueScheduled(context, settings.checkInMinuteOfDay, ExistingWorkPolicy.KEEP)
        }
    }

    /** Queues the next day's check-in from inside the running one. */
    internal suspend fun scheduleFollowingCheckIn(context: Context) {
        val settings = OrbitPreferences.getInstance(context.applicationContext).current()
        if (settings.wantsCheckIns()) {
            enqueueScheduled(context, settings.checkInMinuteOfDay, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    internal suspend fun shouldRunScheduled(context: Context): Boolean =
        OrbitPreferences.getInstance(context.applicationContext).current().wantsCheckIns()

    /** Runs a check-in right away, for trying Orbit out. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<OrbitCheckInWorker>()
                .setInputData(workDataOf(OrbitCheckInWorker.KEY_MANUAL to true))
                .build(),
        )
    }
}

class OrbitCheckInWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        when (OperitApplication.initializeMainApplication(applicationContext)) {
            OperitApplication.MainApplicationInitResult.Initialized -> Unit
            OperitApplication.MainApplicationInitResult.MigrationInProgress -> return Result.retry()
            else -> {
                // A manual try is waiting on a result in the Orbit screen, so leave one.
                if (manual) {
                    runCatching {
                        OrbitPreferences.getInstance(applicationContext).recordRun(
                            OrbitRunOutcome.FAILED,
                            applicationContext.getString(R.string.orbit_run_failed_unknown),
                        )
                    }
                }
                return Result.failure()
            }
        }
        if (!manual && !OrbitCompanion.shouldRunScheduled(applicationContext)) return Result.success()
        try {
            OrbitCompanion.checkIn(applicationContext)
        } finally {
            // Queue the next day whatever this run did; restoreSchedule covers a stopped worker.
            if (!manual) {
                withContext(NonCancellable) {
                    runCatching { OrbitCompanion.scheduleFollowingCheckIn(applicationContext) }
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}

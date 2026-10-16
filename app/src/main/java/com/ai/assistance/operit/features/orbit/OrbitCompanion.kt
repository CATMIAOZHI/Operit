package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.data.preferences.CharacterCardManager
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitRunOutcome
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.util.FileUtils
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Orbit: a long-term companion with its own chat, name, look, Soul.md and a daily check-in.
 *
 * A role card only seeds a new Orbit: its persona is copied into Soul.md once, and the Orbit chat
 * then runs under the default role card with Soul.md as its persona, so editing or deleting the
 * role card does not change the Orbit. The user can make one Orbit per role card, but only one runs
 * at a time; the others are paused and keep everything.
 *
 * Background checks use durable tasks; only an explicit submitted message reaches the main chat.
 */
object OrbitCompanion {
    private const val CHAT_CREATE_TIMEOUT_MS = 15_000L
    private const val MIN_SCHEDULE_DELAY_MS = 60_000L
    private const val ANDROID_ASSET_URI = "file:///android_asset/"
    private val chatMutex = Mutex()

    /** True while a check-in is running in this process. */
    val running: StateFlow<Boolean> = OrbitTaskRunner.running

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
        OrbitTaskRunner.cancelOrbit(orbitId)
        com.ai.assistance.operit.data.db.AppDatabase.getDatabase(appContext).orbitTaskDao().removeOrbit(orbitId)
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
        // Model-only task chats are independent NORMAL nodes, not children in the archive graph.
        val history = com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(appContext)
        chats.filter { it.id in chatIds && it.hiddenReason == "ORBIT_TASK" }.forEach {
            if (history.deleteChatHistory(it.id)) preferences.forgetDeletedTaskChatOwner(it.id, orbitId)
        }
        applySchedule(appContext)
    }

    /** Stops a check-in whose Orbit is no longer the running one. */
    private suspend fun stopInFlightUnlessActive(context: Context) {
        OrbitTaskRunner.cancelUnlessActive(OrbitPreferences.getInstance(context).current().activeOrbitId)
    }

    /** The running Orbit with a chat that exists, or null while every Orbit is paused. */
    suspend fun ensureActiveChat(context: Context): OrbitProfile? {
        val appContext = context.applicationContext
        return chatMutex.withLock {
            val active = OrbitPreferences.getInstance(appContext).current().active?.takeUnless { it.deleting }
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
                    ?.let {
                        // Built-in role pictures ship with the app and never change, so use them directly.
                        if (it.startsWith(ANDROID_ASSET_URI)) it
                        else FileUtils.copyFileToInternalStorage(context, Uri.parse(it), "orbit_avatar")?.toString()
                    }
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
        OrbitExecutionGate.serialized {
            val dao = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).orbitTaskDao()
            dao.tasks(orbit.id).forEach { task ->
                dao.replaceDefinition(task.copy(chatId = recreated.chatId, updatedAt = System.currentTimeMillis()),
                    task.version)
            }
        }
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

    /** Applies changed settings: schedules the next check-in, or cancels it when turned off. */
    suspend fun applySchedule(context: Context) {
        OrbitTaskScheduler.migrateAndSchedule(context)
    }

    /** Restores the schedule at app start without moving a check-in that is already queued. */
    suspend fun restoreSchedule(context: Context) {
        OrbitTaskScheduler.migrateAndSchedule(context, restore = true)
    }

    /** Runs a check-in right away, for trying Orbit out. */
    suspend fun runNow(context: Context) = OrbitTaskScheduler.runNow(context)
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
        // Upgrade compatibility for already queued work. Only the durable runner executes tasks.
        OrbitTaskScheduler.migrateAndSchedule(applicationContext, restore = true)
        return Result.success()
    }

    companion object {
        const val KEY_MANUAL = "manual"
    }
}

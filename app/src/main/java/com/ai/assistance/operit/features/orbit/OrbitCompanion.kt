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
import com.ai.assistance.operit.data.preferences.canAddSideChat
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.util.FileUtils
import java.util.Calendar
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Orbit: a long-term companion with its own chat, name, look, Soul.md and a daily check-in.
 *
 * There is one Orbit. It starts as "Orbit" with the starting SOUL.md and runs under the default
 * role card with Soul.md as its persona. On the first talk it asks whether to take another name,
 * borrow a role card's character for SOUL.md, or bring memories over from another AI. To start
 * over, the user deletes it and makes a new one. Orbits made before this rule keep working.
 *
 * Background checks use durable tasks; only an explicit submitted message reaches the main chat.
 */
object OrbitCompanion {
    private const val CHAT_CREATE_TIMEOUT_MS = 15_000L
    /** A new Orbit's name, the same in every language; the user may give it another on the first talk. */
    const val DEFAULT_NAME = "Orbit"
    private const val MIN_SCHEDULE_DELAY_MS = 60_000L
    private const val ANDROID_ASSET_URI = "file:///android_asset/"
    private val chatMutex = Mutex()

    /** True while a check-in is running in this process. */
    val running: StateFlow<Boolean> = OrbitTaskRunner.running

    private fun core(context: Context): ChatServiceCore =
        ChatRuntimeHolder.getInstance(context.applicationContext).getCore(ChatRuntimeSlot.MAIN)

    /**
     * Starts the Orbit, making it the first time. When Orbits from before the one-Orbit rule are
     * still there, the newest one is started.
     */
    suspend fun start(
        context: Context,
        /** Told the id as soon as a new Orbit is saved, so a caller can undo a start that fails later. */
        onCreated: (String) -> Unit = {},
    ): OrbitProfile {
        val appContext = context.applicationContext
        val profile = chatMutex.withLock {
            val preferences = OrbitPreferences.getInstance(appContext)
            val existing = preferences.current().orbits.maxByOrNull { it.createdAt }
            val profile =
                if (existing != null) ensureChatLocked(appContext, existing)
                else createLocked(appContext, onCreated)
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
    suspend fun pause(context: Context, expectedOrbitId: String? = null) {
        OrbitPreferences.getInstance(context.applicationContext).setActive(null, expectedOrbitId)
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
        orbit.sideChats.forEach { core(appContext).deleteChatHistory(it.chatId) }
        OrbitReactions.drop(orbitId)
        // Its vault, forget plan and forgotten list are encrypted outside the Orbit's folder.
        runCatching { OrbitSecretFile.deleteAllForOrbit(appContext, orbitId) }
        OrbitVault.forgetCached(orbitId)
        runCatching { OrbitWeixin.forget(appContext, orbitId) }
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

    /**
     * Opens a side chat: a separate, lasting conversation with the same Orbit about one topic,
     * like Muse's side chats. It shares Orbit's memory and tools; its history stays its own.
     */
    suspend fun createSideChat(context: Context, orbitId: String, title: String): String {
        val appContext = context.applicationContext
        val clean = title.replace(Regex("\\s+"), " ").trim().take(OrbitSettings.MAX_SIDE_CHAT_TITLE)
        require(clean.isNotEmpty()) { "Missing title" }
        return chatMutex.withLock {
            val preferences = OrbitPreferences.getInstance(appContext)
            val orbit = preferences.current().orbits.firstOrNull { it.id == orbitId && !it.deleting }
                ?: error("Orbit no longer exists")
            check(orbit.canAddSideChat) { "Too many side chats" }
            val chatId = createChat(appContext, null, clean, titled = false)
            preferences.rememberChatOwner(chatId, orbit.id)
            preferences.addSideChat(orbit.id, chatId, clean)
            chatId
        }
    }

    suspend fun renameSideChat(context: Context, orbitId: String, chatId: String, title: String) {
        val clean = title.replace(Regex("\\s+"), " ").trim().take(OrbitSettings.MAX_SIDE_CHAT_TITLE)
        require(clean.isNotEmpty()) { "Missing title" }
        val appContext = context.applicationContext
        OrbitPreferences.getInstance(appContext).renameSideChat(orbitId, chatId, clean)
        runCatching { core(appContext).getChatHistoryDelegate().updateChatTitleAndWait(chatId, clean) }
    }

    /** Navigation only: starts no model turn and copies no source messages into the new history. */
    suspend fun forkSideChat(context: Context, orbitId: String, messageId: Long): OrbitChatFork.Created {
        val appContext = context.applicationContext
        return chatMutex.withLock {
            val preferences = OrbitPreferences.getInstance(appContext)
            val orbit = preferences.current().orbits.firstOrNull { it.id == orbitId && !it.deleting }
                ?: error("Orbit no longer exists")
            check(orbit.canAddSideChat) { "Too many side chats" }
            val origin = com.ai.assistance.operit.data.preferences.OrbitChatOrigin(orbit.chatId, messageId)
            require(!core(appContext).getMessageProcessingDelegate().isStreamingMessage(origin.chatId, messageId)) {
                "That reply is still being written; choose an earlier finished message or wait for it to finish"
            }
            val excerpts = OrbitChatFork.read(appContext, origin)
            require(excerpts.any { it.messageId == messageId }) { "Choose an existing, visible user or Orbit message" }
            // Keeping the standard placeholder enables the existing first-message title generator.
            val title = appContext.getString(R.string.new_conversation)
            val chatId = createChat(appContext, null, title, titled = false)
            preferences.rememberChatOwner(chatId, orbit.id)
            preferences.addSideChat(orbit.id, chatId, title, origin)
            OrbitChatFork.Created(chatId, title, origin, excerpts)
        }
    }

    suspend fun setSideChatPinned(context: Context, orbitId: String, chatId: String, pinned: Boolean) =
        OrbitPreferences.getInstance(context.applicationContext).setSideChatPinned(orbitId, chatId, pinned)

    /** Archived side chats leave the menu; their history, memory and reminders stay as they are. */
    suspend fun setSideChatArchived(context: Context, orbitId: String, chatId: String, archived: Boolean) =
        OrbitPreferences.getInstance(context.applicationContext).setSideChatArchived(orbitId, chatId, archived)

    /**
     * A side chat opened without a title takes the one generated from its first message. One
     * that already has a name (given by the user or by Orbit) keeps it.
     */
    suspend fun adoptGeneratedTitle(context: Context, chatId: String, title: String) {
        val appContext = context.applicationContext
        val clean = title.replace(Regex("\\s+"), " ").trim().take(OrbitSettings.MAX_SIDE_CHAT_TITLE)
        if (clean.isEmpty()) return
        val preferences = OrbitPreferences.getInstance(appContext)
        if (preferences.current().orbitForChat(chatId)?.sideChats?.none { it.chatId == chatId } != false) return
        preferences.renameSideChatIfTitled(chatId, appContext.getString(R.string.new_conversation), clean)
    }

    /**
     * Deletes a side chat and its history. Tasks made there report to the main chat from now on,
     * and reports still waiting to be posted go there too. It finishes even if the screen that
     * asked goes away, and the side chat stays listed until its history is really gone, so a
     * failed delete can be tried again.
     */
    suspend fun deleteSideChat(context: Context, orbitId: String, chatId: String) = withContext(NonCancellable) {
        val appContext = context.applicationContext
        val preferences = OrbitPreferences.getInstance(appContext)
        val orbit = preferences.current().orbits.firstOrNull { it.id == orbitId } ?: return@withContext
        if (orbit.sideChats.none { it.chatId == chatId }) return@withContext
        val dao = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(appContext).orbitTaskDao()
        OrbitExecutionGate.serialized {
            dao.moveTaskChat(orbitId, chatId, orbit.chatId)
            dao.movePendingDeliveries(orbitId, chatId, orbit.chatId)
        }
        // Deleting the chat on screen would make the chat screen jump to some unrelated chat, so
        // show Orbit's main chat first.
        val core = core(appContext)
        if (core.currentChatId.value == chatId) {
            withContext(kotlinx.coroutines.Dispatchers.Main) { core.switchChat(orbit.chatId, scrollToBottom = false) }
            kotlinx.coroutines.withTimeoutOrNull(3_000) { core.currentChatId.first { it != chatId } }
        }
        // Its history may already be gone (deleted from chat history settings, or an earlier try
        // that timed out after finishing); then only the list entry is left to remove.
        if (core.chatExists(chatId)) {
            val deleted = CompletableDeferred<Boolean>()
            core.getChatHistoryDelegate().deleteChatHistory(chatId) { deleted.complete(it) }
            check(withTimeout(CHAT_CREATE_TIMEOUT_MS) { deleted.await() } || !core.chatExists(chatId)) {
                "Could not delete the chat"
            }
        }
        preferences.removeSideChat(orbitId, chatId)
        OrbitDelivery.markSeen(chatId)
        runCatching { OrbitWeixin.onSideChatDeleted(appContext, orbitId, chatId) }
        runCatching { OrbitReactions.of(appContext, orbitId).removeChat(chatId) }
        runCatching { OrbitReactions.ofUser(appContext, orbitId).removeChat(chatId) }
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

    /** Makes the Orbit: named Orbit, the starting SOUL.md, the default role card's picture. */
    private suspend fun createLocked(context: Context, onCreated: (String) -> Unit = {}): OrbitProfile {
        val roleCardId = CharacterCardManager.DEFAULT_CHARACTER_CARD_ID
        val orbitId = UUID.randomUUID().toString()
        val preferences = OrbitPreferences.getInstance(context)
        preferences.writeSoul(orbitId, OrbitSoulTemplate.initial(com.ai.assistance.operit.util.LocaleUtils.usesEnglishPrompt(context)))
        val avatarUri = roleAvatarCopy(context, roleCardId)
        // No opening message: Orbit greets the user itself on the first talk.
        val chatId = createChat(context, null, DEFAULT_NAME)
        val profile =
            OrbitProfile(
                id = orbitId,
                sourceRoleCardId = roleCardId,
                chatId = chatId,
                // Orbit starts as Orbit and asks on the first talk whether the user wants another name.
                name = DEFAULT_NAME,
                avatarUri = avatarUri,
                createdAt = System.currentTimeMillis(),
            )
        preferences.putOrbit(profile)
        onCreated(orbitId)
        val memorySpaceId = OrbitMemoryScope.ensureInitialized(context, profile)
        return profile.copy(memorySpaceId = memorySpaceId)
    }

    /**
     * Orbit's own copy of the role card's current picture. Built-in role pictures ship with the
     * app and never change, so they are used directly. Null when the card has no picture.
     */
    private suspend fun roleAvatarCopy(context: Context, roleCardId: String): String? =
        runCatching {
            UserPreferencesManager.getInstance(context)
                .getResolvedAiAvatarForCharacterCardFlow(roleCardId)
                .first()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    if (it.startsWith(ANDROID_ASSET_URI)) it
                    else FileUtils.copyFileToInternalStorage(context, Uri.parse(it), "orbit_avatar")?.toString()
                }
        }.getOrNull()

    /**
     * Uses the picture of role card [roleCardId] (the Orbit's own default card when null), as the
     * card has it now. A card that no longer exists fails, except a built-in role's picture.
     */
    suspend fun useRoleCardAvatar(context: Context, orbitId: String, roleCardId: String? = null) {
        val preferences = OrbitPreferences.getInstance(context)
        val orbit = preferences.current().orbits.firstOrNull { it.id == orbitId } ?: return
        val cardId = roleCardId ?: orbit.sourceRoleCardId
        val cardExists = runCatching {
            cardId in CharacterCardManager.getInstance(context).characterCardListFlow.first()
        }.getOrDefault(false)
        val builtIn = UserPreferencesManager.getBuiltInCharacterAvatarUri(cardId)
        require(cardExists || builtIn != null) { "No role card with id $cardId; list them with orbit_soul action=list_role_cards" }
        val uri = if (cardExists) roleAvatarCopy(context, cardId) ?: builtIn else builtIn
        // A card without a picture changes nothing rather than clearing the current one.
        checkNotNull(uri) { "That role card has no picture; the avatar was not changed" }
        preferences.setAvatar(orbitId, uri)
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
            // Tasks made in a side chat keep reporting there.
            dao.tasks(orbit.id).filter { it.chatId == orbit.chatId }.forEach { task ->
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
    private suspend fun createChat(context: Context, roleCardId: String?, title: String, titled: Boolean = true): String {
        val core = core(context)
        val created = CompletableDeferred<String>()
        core.createNewChat(
            inheritGroupFromCurrent = false,
            setAsCurrentChat = false,
            characterCardId = roleCardId ?: CharacterCardManager.DEFAULT_CHARACTER_CARD_ID,
            // A side chat (no role card) starts empty: it is Orbit talking, not the default card.
            withOpeningMessage = roleCardId != null,
            onCreated = { chatId -> created.complete(chatId) },
        )
        val chatId = withTimeout(CHAT_CREATE_TIMEOUT_MS) { created.await() }
        core.getChatHistoryDelegate()
            .updateChatTitleAndWait(chatId, if (titled) context.getString(R.string.orbit_chat_title, title) else title)
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

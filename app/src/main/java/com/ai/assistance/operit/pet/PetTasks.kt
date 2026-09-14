package com.ai.assistance.operit.pet

import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.data.model.toSerializable
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatKind
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.ui.main.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class PetActivity { IDLE, THINKING, TOOL, SUMMARIZING, COMPLETE, ERROR, ENDED }

internal data class PetChatMetadata(val title: String, val hidden: Boolean, val subagent: Boolean) {
    /**
     * Hidden chats stay out of ordinary UI, and a subagent chat is another agent's read-only
     * transcript rather than a conversation the user drives. Neither is a pet task.
     */
    val petVisible: Boolean get() = !hidden && !subagent
}

internal fun petChatMetadataOf(chat: ChatHistory): PetChatMetadata =
    PetChatMetadata(chat.title, chat.isHidden, chat.chatKind == ChatKind.SUBAGENT.name)

data class PetTask(
    val key: String,
    val chatId: String,
    val slot: ChatRuntimeSlot,
    val title: String,
    val activity: PetActivity,
    val active: Boolean,
    val startedOrder: Long = 0,
)

internal fun petActivity(state: InputProcessingState?, active: Boolean): PetActivity = when {
    state is InputProcessingState.Error -> PetActivity.ERROR
    !active -> if (state is InputProcessingState.Completed) PetActivity.COMPLETE else PetActivity.ENDED
    state is InputProcessingState.ExecutingTool ||
        state is InputProcessingState.ToolProgress ||
        state is InputProcessingState.ProcessingToolResult -> PetActivity.TOOL
    state is InputProcessingState.Summarizing -> PetActivity.SUMMARIZING
    else -> PetActivity.THINKING
}

/**
 * Observe existing runs only. In particular, the global permission dialog has no chat identity
 * and must not be used to label an unrelated task as waiting for approval.
 */
class PetTasks private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableTasks = MutableStateFlow<List<PetTask>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    val appVisible = MutableStateFlow(false)
    val selectedKey = MutableStateFlow<String?>(null)
    /**
     * Completed runs the pet no longer has to show: the user confirmed the card on the panel, or
     * the main screen already displayed that conversation. The chat returns with its next run.
     */
    private val acknowledgedRuns = MutableStateFlow<Map<String, Long>>(emptyMap())
    val visibleTasks = combine(tasks, acknowledgedRuns) { current, acknowledged ->
        visiblePetTasks(current, acknowledged)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun acknowledgeCompletion(task: PetTask) {
        val current = tasks.value.firstOrNull { it.key == task.key && it.startedOrder == task.startedOrder }
            ?: return
        val acknowledged = acknowledgedRuns.value + (current.key to current.startedOrder)
        if (!current.isAcknowledged(acknowledged)) return
        acknowledgedRuns.value = acknowledged
        // The confirmed card is gone, so the pet moves on to a task that is still listed.
        selectedKey.value = visiblePetTasks(tasks.value, acknowledged).lastOrNull()?.key
    }

    fun acknowledgeViewedChat(chatId: String) {
        // Viewing one conversation must not select it over another active task.
        val viewed = tasks.value.filter { shouldAcknowledgeViewedPetTask(it, chatId) }
        if (viewed.isNotEmpty()) {
            acknowledgedRuns.value = acknowledgedRuns.value +
                viewed.associate { it.key to it.startedOrder }
        }
    }

    /** A confirmation only means something while the run it refers to is still known. */
    private fun pruneStaleConfirmations() {
        acknowledgedRuns.value = acknowledgedRuns.value.filter { (key, order) ->
            mutableTasks.value.any { it.key == key && it.startedOrder == order }
        }
    }

    init {
        scope.launch {
            combine(PetPreferences.get(context).settings, FloatingPetEntry.mode) { settings, entry ->
                (settings.inApp || settings.overlay || entry != FloatingPetEntryMode.NONE) && settings.isReady
            }.distinctUntilChanged().collectLatest { enabled ->
                    mutableTasks.value = emptyList()
                    acknowledgedRuns.value = emptyMap()
                    if (!enabled) return@collectLatest
                    coroutineScope {
                        val holder = ChatRuntimeHolder.getInstance(context)
                        val bySlot = mutableMapOf<ChatRuntimeSlot, List<PetTask>>()
                        var sequence = 0L
                        for (slot in ChatRuntimeSlot.values()) {
                            launch {
                                val core = holder.getCore(slot)
                                val runs = linkedMapOf<String, PetTask>()
                                val metadataCache = mutableMapOf<String, PetChatMetadata>()
                                var previousMetadata: Map<String, PetChatMetadata>? = null
                                fun publish() {
                                    bySlot[slot] = runs.values.toList()
                                    mutableTasks.value = bySlot.values.flatten().sortedBy { it.startedOrder }
                                    pruneStaleConfirmations()
                                }
                                val metadataFlow = core.chatHistories.map { histories ->
                                    histories.associate { it.id to petChatMetadataOf(it) }
                                }.distinctUntilChanged()
                                combine(core.activeStreamingChatIds, core.inputProcessingStateByChatId, metadataFlow) {
                                    active, states, metadata -> Triple(active, states, metadata)
                                }.collect { (active, states, metadataSnapshot) ->
                                    if (metadataSnapshot != previousMetadata) {
                                        metadataCache.clear()
                                        previousMetadata = metadataSnapshot
                                    }
                                    for (chatId in active) {
                                        val previous = runs[chatId]
                                        val metadata = metadataCache[chatId] ?: (
                                            metadataSnapshot[chatId] ?: core.getChatMetadata(chatId)?.let {
                                                petChatMetadataOf(it)
                                            }
                                        )?.also { metadataCache[chatId] = it }
                                        // Internal audit chats and subagent transcripts are absent from ordinary UI.
                                        if (metadata == null || !metadata.petVisible) {
                                            runs.remove(chatId)
                                            continue
                                        }
                                        if (previous == null || !previous.active) {
                                            // Keep one entry per conversation, ordered by its latest run.
                                            runs.remove(chatId)
                                            runs[chatId] = PetTask(
                                                "$slot:$chatId:${++sequence}", chatId, slot,
                                                metadata.title,
                                                petActivity(states[chatId], true), true,
                                                startedOrder = sequence,
                                            )
                                        } else {
                                            runs[chatId] = previous.copy(
                                                title = metadata.title,
                                                activity = petActivity(states[chatId], true),
                                            )
                                        }
                                    }
                                    runs.toMap().forEach { (chatId, previous) ->
                                        val metadata = metadataCache[chatId] ?: (
                                            metadataSnapshot[chatId] ?: core.getChatMetadata(chatId)?.let {
                                                petChatMetadataOf(it)
                                            }
                                        )?.also { metadataCache[chatId] = it }
                                        if (metadata == null || !metadata.petVisible) {
                                            runs.remove(chatId)
                                            return@forEach
                                        }
                                        val refreshed = previous.copy(title = metadata.title)
                                        runs[chatId] = refreshed
                                        if (previous.active && chatId !in active) {
                                            runs[chatId] = refreshed.copy(
                                                active = false, activity = petActivity(states[chatId], false)
                                            )
                                        } else if (!previous.active && (
                                            states[chatId] is InputProcessingState.Completed ||
                                                states[chatId] is InputProcessingState.Error
                                            )) {
                                            runs[chatId] = refreshed.copy(activity = petActivity(states[chatId], false))
                                        }
                                    }
                                    publish()
                                }
                            }
                        }
                    }
                }
        }
    }


    fun open(task: PetTask) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            putExtra(EXTRA_CHAT_ID, task.chatId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    fun openFloating(task: PetTask?) {
        if (FloatingPetEntry.mode.value == FloatingPetEntryMode.CHAT_WINDOW) {
            FloatingChatService.getInstance()?.let {
                it.minimizeToPet()
                return
            }
        }
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val slot = task?.slot ?: FloatingChatService.getInstance()?.currentChatSlot ?: ChatRuntimeSlot.MAIN
        if (task != null) {
            startFloatingChat(slot, task.chatId)
            return
        }
        scope.launch {
            val core = ChatRuntimeHolder.getInstance(context).getCore(slot)
            // Nothing is selected in that runtime: stay on the last conversation the pet knew, so
            // dismissing the only completed card still opens that conversation.
            val currentChatId = core.currentChatId.value ?: tasks.value.lastOrNull { it.slot == slot }?.chatId
            val current = currentChatId?.let { core.getChatMetadata(it) }
            // The pet never opens a subagent transcript; fall back to the parent conversation.
            startFloatingChat(
                slot,
                if (current?.chatKind == ChatKind.SUBAGENT.name) current.parentChatId else currentChatId,
            )
        }
    }

    private fun startFloatingChat(slot: ChatRuntimeSlot, chatId: String?) {
        ContextCompat.startForegroundService(context, Intent(context, FloatingChatService::class.java).apply {
            putExtra(FloatingChatService.EXTRA_CHAT_SLOT, slot.name)
            putExtra(FloatingChatService.EXTRA_CHAT_ID, chatId)
            putExtra("INITIAL_MODE", "WINDOW")
            putExtra("COLOR_SCHEME", PetTheme.colors.toSerializable())
        })
    }

    companion object {
        const val ACTION_OPEN_CHAT = "com.ai.assistance.operit.pet.OPEN_CHAT"
        const val EXTRA_CHAT_ID = "pet_chat_id"
        @Volatile private var instance: PetTasks? = null
        fun get(context: Context): PetTasks = instance ?: synchronized(this) {
            instance ?: PetTasks(context.applicationContext).also { instance = it }
        }
    }
}

/** A confirmed run leaves the pet task list; the conversation returns with its next run. */
internal fun PetTask.isAcknowledged(acknowledgedRuns: Map<String, Long>): Boolean =
    !active && activity == PetActivity.COMPLETE && startedOrder == acknowledgedRuns[key]

/** Only what still needs attention stays in the list the pet shows. */
internal fun visiblePetTasks(tasks: List<PetTask>, acknowledgedRuns: Map<String, Long>): List<PetTask> =
    tasks.filterNot { it.isAcknowledged(acknowledgedRuns) }

internal fun shouldAcknowledgeViewedPetTask(task: PetTask, chatId: String): Boolean =
    task.slot == ChatRuntimeSlot.MAIN && task.chatId == chatId &&
        !task.active && task.activity == PetActivity.COMPLETE

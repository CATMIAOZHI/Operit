package com.ai.assistance.operit.services.floating

import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.chat.TurnInputInbox
import com.ai.assistance.operit.data.model.AttachmentInfo
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.preferences.AppLaunchMode
import com.ai.assistance.operit.data.preferences.LaunchModePreferences
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.OrbitSettings
import com.ai.assistance.operit.pet.PetTasks
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** How the floating window treats the user's Orbit: its chats, where a window opens, sending. */
internal object FloatingOrbit {
    /**
     * A message sent from the window that is not a chat row yet: handed to Orbit's running turn, or
     * waiting for the chat to be free. [content] is what its row will show; [savedAt] is that row's
     * time once the turn saved it; [sentSince] is when it went out on its own (its row is the
     * chat's first user row from then on).
     */
    data class Sending(
        val id: Long, val chatId: String, val content: String,
        val savedAt: Long? = null, val sentSince: Long? = null,
        /** When the user sent it. */
        val at: Long = System.currentTimeMillis(),
    )

    private val ids = AtomicLong()

    /** Messages handed to a chat's running reply go in the order the user sent them (each chat on its own). */
    private val order = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** An earlier message to [chatId] still waits for the chat to be free; a later one goes after it. */
    fun isWaitingFree(chatId: String): Boolean = freeOrder[chatId]?.isLocked == true

    /** Messages waiting for a chat to be free, one at a time per chat. */
    private val freeOrder = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** Runs a hand-over to [chatId]'s running reply in turn with the others (the app's queue uses it too). */
    suspend fun <T> inOrder(chatId: String, block: suspend () -> T): T =
        order.getOrPut(chatId) { kotlinx.coroutines.sync.Mutex() }.withLock { block() }
    private val sendingState = MutableStateFlow<List<Sending>>(emptyList())
    val sending: StateFlow<List<Sending>> = sendingState

    /** The app's own messages handed to Orbit's running reply (its queue); the window shows them too. */
    private val appQueued = MutableStateFlow<List<Sending>>(emptyList())

    /** Saved ones (they leave the queue at once), kept for a window that shows the chat from another runtime. */
    private val appLingering = MutableStateFlow<List<Sending>>(emptyList())
    private val appSendingState = MutableStateFlow<List<Sending>>(emptyList())
    val appSending: StateFlow<List<Sending>> = appSendingState

    private fun publishApp() {
        val queued = appQueued.value
        val queuedIds = queued.mapTo(hashSetOf()) { it.id }
        appSendingState.value = queued + appLingering.value.filter { it.id !in queuedIds }
    }

    /** What the app's queue hands to running replies now. */
    fun publishAppSending(list: List<Sending>) {
        appQueued.value = list
        publishApp()
    }

    /**
     * The app's message [saved] has its row. The window may show the chat from another runtime that
     * reloads only when the turn ends, so it stays shown there until then (each view hides it once
     * that row is in).
     */
    fun appSaved(context: Context, saved: Sending) {
        // Called where the turn saves rows (off the main thread); the app's queue is published on it.
        pending.launch {
            appLingering.update { list -> list.filterNot { it.id == saved.id } + saved }
            publishApp()
            withTimeoutOrNull(30 * 60_000L) { while (busyRuntime(context, saved.chatId) != null) delay(500) }
            delay(10_000)
            appLingering.update { list -> list.filterNot { it.id == saved.id && it.savedAt == saved.savedAt } }
            publishApp()
        }
    }

    /**
     * Sends outlive the window: closing it must not drop a message the user already sent (its text
     * and attachments have left the composer). The chat runtimes live as long as the process.
     */
    private val pending = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun show(id: Long, chatId: String, content: String) = sendingState.update { list ->
        val at = list.firstOrNull { it.id == id }?.at ?: System.currentTimeMillis()
        list.filterNot { it.id == id } + Sending(id, chatId, content, at = at)
    }

    private fun hide(id: Long) = sendingState.update { list -> list.filterNot { it.id == id } }

    private fun mark(id: Long, change: (Sending) -> Sending) =
        sendingState.update { list -> list.map { if (it.id == id) change(it) else it } }

    /**
     * The row is in; each view hides the bubble as soon as it shows that row. Another runtime's view
     * of the chat only reloads when the turn ends, so the bubble stays until then (and a little after).
     */
    private fun saved(context: Context, id: Long, chatId: String, rowTimestamp: Long) {
        mark(id) { it.copy(savedAt = rowTimestamp) }
        pending.launch { dropAfterTurn(context, id, chatId) }
    }

    private suspend fun dropAfterTurn(context: Context, id: Long, chatId: String) {
        withTimeoutOrNull(30 * 60_000L) { while (busyRuntime(context, chatId) != null) delay(500) }
        delay(10_000)
        hide(id)
    }

    private fun runtimes(context: Context): List<ChatServiceCore> {
        val holder = ChatRuntimeHolder.getInstance(context.applicationContext)
        return ChatRuntimeSlot.entries.map(holder::getCore)
    }

    /**
     * The runtime working on [chatId] right now, whichever it is: a background task runs Orbit's
     * chat in the app's runtime even when the window shows it from its own.
     */
    fun busyRuntime(context: Context, chatId: String): ChatServiceCore? =
        runtimes(context).firstOrNull { it.isChatBusy(chatId) }

    private fun waitingToSend(chatId: String): Boolean = sendingState.value.any { it.chatId == chatId && it.savedAt == null }

    /** A message to [chatId] is still on its way through here (not a chat row yet). */
    fun isSending(chatId: String): Boolean = waitingToSend(chatId)

    /**
     * Runs [block] apart from any window: a send the user already made (its text has left the
     * composer) must finish even if the window closes right away.
     */
    fun launch(block: suspend CoroutineScope.() -> Unit) {
        pending.launch(block = block)
    }

    /** Settings already read (no suspension), or read now when a widget or the assistant starts a cold process. */
    suspend fun settings(context: Context): OrbitSettings? {
        val preferences = OrbitPreferences.getInstance(context.applicationContext)
        return preferences.loadedSnapshot()
            ?: withTimeoutOrNull(1_500) { withContext(Dispatchers.IO) { preferences.current() } }
    }

    /** The active Orbit when [chatId] is one of its chats. */
    fun owner(settings: OrbitSettings?, chatId: String?): OrbitProfile? =
        settings?.active?.takeIf { !it.deleting && chatId != null && chatId in it.talkChatIds }

    /**
     * Where a window opened without a chosen chat shows. Opened from the app, Orbit's chat on screen
     * is shared live (MAIN) and a normal chat stays a normal window (null). Opened from outside in
     * Orbit mode, the window goes to Orbit's main chat, sharing the app's runtime when that chat is
     * the one open there.
     */
    suspend fun target(context: Context, mainChatId: String?, openedFromApp: Boolean): Pair<ChatRuntimeSlot, String>? {
        val orbit = settings(context)?.active?.takeUnless { it.deleting } ?: return null
        if (openedFromApp) {
            return if (mainChatId != null && mainChatId in orbit.talkChatIds) ChatRuntimeSlot.MAIN to mainChatId else null
        }
        if (LaunchModePreferences.getInstance(context).mode != AppLaunchMode.ORBIT) return null
        if (mainChatId == orbit.chatId) return ChatRuntimeSlot.MAIN to orbit.chatId
        return ChatRuntimeSlot.FLOATING to orbit.chatId
    }

    /**
     * A message sent while Orbit is replying goes into that reply, as in the app. If the turn hands
     * it back, it is sent on its own once the chat is free. False when no turn can take it now;
     * [attachments] are the window's, taken with the message.
     */
    suspend fun steer(
        context: Context, core: ChatServiceCore, chatId: String, text: String, attachments: List<AttachmentInfo>,
        replyToMessage: ChatMessage? = null, id: Long = ids.incrementAndGet(), onLost: () -> Unit,
    ): Boolean = inOrder(chatId) {
        // An earlier message still waits for the chat to be free: this one goes after it.
        if (freeOrder[chatId]?.isLocked == true) return@inOrder false
        val processing = (busyRuntime(context, chatId) ?: return@inOrder false).getMessageProcessingDelegate()
        val turnId = processing.steeringTurnId(chatId) ?: return@inOrder false
        val prepared = try {
            processing.prepareSteeringInput(chatId, turnId, text, attachments, replyToMessage)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@inOrder false
        show(id, chatId, prepared)
        val later = {
            sendWhenFree(context, core, chatId, text, attachments, replyToMessage, shownAs = id to prepared, onLost = onLost)
        }
        val accepted = processing.trySteerMessage(
            chatId, turnId,
            TurnInputInbox.Input(
                text = prepared,
                saved = { saved(context, id, chatId, it) },
                returned = later,
                returnedForSummary = later,
            ),
        )
        accepted
    }

    /** What a message shows as while its row is not in: its text, or its files' names. */
    private fun placeholder(text: String, attachments: List<AttachmentInfo>): String =
        text.ifBlank { attachments.joinToString("、") { it.fileName } }

    /**
     * The window's message while Orbit is busy: into the running reply, or on its own once the chat
     * is free. Runs apart from the window, so closing it right after sending loses nothing.
     */
    fun sendWhileBusy(
        context: Context, core: ChatServiceCore, chatId: String, text: String, attachments: List<AttachmentInfo>,
        replyToMessage: ChatMessage? = null, onLost: () -> Unit,
    ) {
        // Shown the moment it is sent, in the order sent; handed over in that order too.
        val id = ids.incrementAndGet()
        val shown = placeholder(text, attachments)
        if (shown.isNotBlank()) show(id, chatId, shown)
        pending.launch {
            if (!steer(context, core, chatId, text, attachments, replyToMessage, id, onLost)) {
                sendWhenFree(context, core, chatId, text, attachments, replyToMessage, shownAs = id to shown, onLost = onLost)
            }
        }
    }

    /**
     * Sends [text] through [core] as its own message once [chatId] is free in every runtime (waits at
     * most 30 minutes). Another message may take the free moment first, so a refused send waits and
     * tries again; [onLost] runs when it never goes out. While it waits it shows as sent when its row
     * will look the same ([shownAs]: the text a running turn handed back; or plain text); a message
     * with attachments not prepared yet shows once sent. The bubble stays until the row is in.
     */
    fun sendWhenFree(
        context: Context, core: ChatServiceCore, chatId: String, text: String, attachments: List<AttachmentInfo>,
        replyToMessage: ChatMessage? = null, shownAs: Pair<Long, String>? = null, onLost: () -> Unit,
    ) {
        val id = shownAs?.first ?: ids.incrementAndGet()
        val content = shownAs?.second ?: placeholder(text, attachments)
        if (!content.isNullOrBlank()) show(id, chatId, content) else hide(id)
        pending.launch {
            var since = 0L
            // Messages waiting for the same chat go out one by one, in the order they started waiting.
            val sent = withTimeoutOrNull(30 * 60_000L) {
                freeOrder.getOrPut(chatId) { kotlinx.coroutines.sync.Mutex() }.withLock {
                    while (true) {
                        while (busyRuntime(context, chatId) != null) delay(300)
                        since = System.currentTimeMillis()
                        if (core.trySendUserMessageToExistingChat(
                                chatId, text, ChatTurnOptions(useComposerReply = false), attachments, replyToMessage,
                            )) break
                        delay(300)
                    }
                }
                true
            }
            if (sent != true) {
                hide(id)
                onLost()
                return@launch
            }
            // Taken, but the row is added once the model is ready: the window drops the bubble when a
            // user row from then on shows. A failed preparation leaves no running turn behind.
            mark(id) { it.copy(sentSince = since) }
            while (core.isPreparingModel(chatId)) delay(200)
            // Taking it only started the model's preparation; a failed one (say the model is gone)
            // saves no row and starts no turn: the message did not go out after all.
            // Its turn runs (its row may still be on the way, e.g. with large attachments), or its row is
            // saved: read from the chat itself, since the user may be looking at another chat by now.
            val isOwnRow = { m: ChatMessage -> m.sender == "user" && m.timestamp >= since && (text.isBlank() || m.content.contains(text)) }
            val wentOut = core.getMessageProcessingDelegate().isChatLoading(chatId) ||
                (core.currentChatId.value == chatId && core.chatHistory.value.any(isOwnRow)) ||
                com.ai.assistance.operit.data.repository.ChatHistoryManager.getInstance(context)
                    .loadChatMessagesAscAfter(chatId, since - 1, 50).any(isOwnRow)
            if (!wentOut) {
                hide(id)
                onLost()
                return@launch
            }
            dropAfterTurn(context, id, chatId)
        }
    }

    /**
     * A reply option tapped in the window: sent at once, or into the reply Orbit is writing.
     * [onLost] runs on the main thread when it was dropped on the way: like the app, the option is
     * watched while [waiting] (not saved yet), and lost after the chat sat idle with nothing to send.
     */
    fun sendOption(
        context: Context, core: ChatServiceCore, chatId: String, text: String,
        waiting: () -> Boolean, onLost: () -> Unit,
    ) {
        pending.launch {
            if (busyRuntime(context, chatId) != null) {
                val id = ids.incrementAndGet()
                show(id, chatId, text)
                if (!steer(context, core, chatId, text, emptyList(), id = id, onLost = onLost)) {
                    sendWhenFree(context, core, chatId, text, emptyList(), shownAs = id to text, onLost = onLost)
                }
            } else if (!core.trySendUserMessageToExistingChat(chatId, text, ChatTurnOptions(useComposerReply = false))) {
                sendWhenFree(context, core, chatId, text, emptyList(), onLost = onLost)
            }
            withTimeoutOrNull(30 * 60_000L) {
                var idleSince = 0L
                while (waiting()) {
                    val now = System.currentTimeMillis()
                    if (waitingToSend(chatId) || busyRuntime(context, chatId) != null) idleSince = 0L
                    else if (idleSince == 0L) idleSince = now
                    else if (now - idleSince >= 3_000) { onLost(); break }
                    delay(500)
                }
            }
        }
    }

    /** Opens Orbit's page in the app on [chatId], for what the window cannot do itself. */
    fun openInApp(context: Context, chatId: String) {
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            action = PetTasks.ACTION_OPEN_CHAT
            putExtra(PetTasks.EXTRA_CHAT_ID, chatId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }
}

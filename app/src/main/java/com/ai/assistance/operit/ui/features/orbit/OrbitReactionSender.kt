package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.features.orbit.OrbitReactionNotice
import com.ai.assistance.operit.features.orbit.OrbitReactions
import com.ai.assistance.operit.features.orbit.OrbitWake
import com.ai.assistance.operit.ui.features.chat.components.OrbitSpeech
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells Orbit when the user puts an emoji on one of its messages, in a hidden turn the chat does
 * not show (see [OrbitReactionNotice]). Taking an emoji off says nothing; a quick change of mind
 * sends only the last one; the same emoji on the same message is told once.
 */
internal object OrbitReactionSender {
    private const val TAG = "OrbitReactionSender"
    /** Lets a quick change of emoji settle before Orbit hears about it. */
    private const val SETTLE_MS = 1_500L
    /** How long a notice waits for a reply that is still being written. */
    private const val WAIT_FOR_IDLE_MS = 10 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ConcurrentHashMap<String, Job>()
    private val told = ConcurrentHashMap.newKeySet<String>()

    /**
     * [key] is the bubble: a message time for its words, or a card's key. [said] is what Orbit hears
     * the reaction was on, for a card; null reads the message's words.
     */
    fun onReacted(context: Context, orbitId: String, chatId: String, key: String, emoji: String, said: String? = null) {
        val app = context.applicationContext
        val pendingKey = "$chatId:$key"
        pending.remove(pendingKey)?.cancel()
        val job = scope.launch {
            delay(SETTLE_MS)
            // The user may have taken it off or picked another one meanwhile.
            suspend fun stillThere() = OrbitReactions.ofUser(app, orbitId).observe(chatId).first()[key] == emoji
            if (!stillThere() || !told.add("$pendingKey:$emoji")) return@launch
            try {
                val words = said ?: run {
                    val timestamp = key.toLongOrNull() ?: return@launch
                    val message = withContext(Dispatchers.IO) {
                        ChatHistoryManager.getInstance(app).loadChatMessagesByTimestamps(chatId, listOf(timestamp)).firstOrNull()
                    } ?: return@launch
                    OrbitSpeech.words(message.content, app.getString(com.ai.assistance.operit.R.string.orbit_sent_card))
                }
                if (words.isBlank()) return@launch
                // Never cut into a reply or a summary, here or in the floating window showing the same
                // chat; Orbit hears about it once the chat is quiet. Sending holds the same per-chat
                // lock as scheduled notices, so two hidden turns never start together.
                val taken = OrbitWake.whenQuiet(app, chatId, WAIT_FOR_IDLE_MS) { core ->
                    // Taken off while waiting: say nothing, and let a later tap of the same emoji count.
                    if (!stillThere()) return@whenQuiet Sent.GONE
                    // Refused without touching anything when the chat just got busy.
                    if (core.trySendUserMessageToExistingChat(
                    chatId = chatId,
                    messageText = OrbitReactionNotice.build(LocaleUtils.usesEnglishPrompt(app), emoji, words),
                    // Hidden in the chat but kept in the conversation. It is not typed by the user,
                    // so it leaves the composer, its reply selection and attachments alone. A quiet
                    // ending must not pop a "reply finished" notification either.
                    turnOptions = ChatTurnOptions(
                        hideUserMessage = true, useComposerReply = false, deliveredByTool = true, notifyReply = false,
                        // A short tapback turn never starts a summary of its own; the next real
                        // message does that, so this turn starts at once instead of waiting behind it.
                        disableSummary = true,
                    ),
                    )) Sent.YES else Sent.BUSY
                } ?: Sent.BUSY
                when (taken) {
                    Sent.YES -> Unit
                    Sent.GONE -> told.remove("$pendingKey:$emoji")
                    Sent.BUSY -> error("The chat stayed busy")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                told.remove("$pendingKey:$emoji")
                throw e
            } catch (e: Exception) {
                told.remove("$pendingKey:$emoji")
                AppLogger.w(TAG, "Could not tell Orbit about a reaction: ${e.message}")
            }
        }
        pending[pendingKey] = job
        job.invokeOnCompletion { pending.remove(pendingKey, job) }
    }

    private enum class Sent { YES, GONE, BUSY }
}

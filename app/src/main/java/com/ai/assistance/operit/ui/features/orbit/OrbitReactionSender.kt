package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.model.ChatTurnOptions
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.features.orbit.OrbitReactionNotice
import com.ai.assistance.operit.features.orbit.OrbitReactions
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
import kotlinx.coroutines.withTimeoutOrNull

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
                val core = ChatRuntimeHolder.getInstance(app).getCore(ChatRuntimeSlot.MAIN)
                // Never cut into a reply being written; Orbit hears about it once that reply ends.
                // A running summary is waited out too: a send during it is refused, and the wait for
                // the refused turn would end by cancelling that summary.
                withTimeoutOrNull(WAIT_FOR_IDLE_MS) {
                    // All three at the same moment: no reply on this chat and no summary of any kind.
                    kotlinx.coroutines.flow.combine(
                        core.activeStreamingChatIds, core.isSummarizing, core.isSendTriggeredSummarizing,
                    ) { active, summarizing, sendSummarizing -> chatId !in active && !summarizing && !sendSummarizing }
                        .first { it }
                }
                    ?: error("The chat stayed busy")
                // Taken off while waiting: say nothing, and let a later tap of the same emoji count.
                if (!stillThere()) {
                    told.remove("$pendingKey:$emoji")
                    return@launch
                }
                val words = said ?: run {
                    val timestamp = key.toLongOrNull() ?: return@launch
                    val message = withContext(Dispatchers.IO) {
                        ChatHistoryManager.getInstance(app).loadChatMessagesByTimestamps(chatId, listOf(timestamp)).firstOrNull()
                    } ?: return@launch
                    OrbitSpeech.words(message.content, app.getString(com.ai.assistance.operit.R.string.orbit_sent_card))
                }
                if (words.isBlank()) return@launch
                // Says whether the send was taken. If the chat just got busy, it is refused without
                // touching anything (no summary or message is cancelled), and the catch below lets
                // the same emoji be told later.
                val taken = core.trySendUserMessageToExistingChat(
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
                )
                if (!taken) error("The chat was busy")
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
}

package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One pending "open that conversation and land on the message holding this text" request.
 *
 * A reader who asks to see one item of a conversation that was judged in another chat knows which
 * chat and which exchange they mean, but only the transcript can scroll to it, and the transcript is
 * composed after the chat switch. The request therefore travels through this host: the caller files it
 * on the way to the switch, and the transcript of that chat takes it once it holds the messages, finds
 * the message the marker belongs to, and lands there.
 *
 * [marker] is text the target message carries and no other message in that transcript does, which is
 * what makes the match exact rather than a guess.
 */
internal data class TranscriptJumpRequest(
    val chatId: String,
    val marker: String,
    val token: Long,
)

internal object TranscriptJumpHost {
    private var request by mutableStateOf<TranscriptJumpRequest?>(null)
    private var issued = 0L

    /** Files the request a chat switch is about to need. A null or blank chat has nowhere to land. */
    fun request(chatId: String?, marker: String) {
        val id = chatId?.takeIf { it.isNotBlank() } ?: return
        request = TranscriptJumpRequest(chatId = id, marker = marker, token = ++issued)
    }

    /** The request waiting for [chatId], or null. Reading it does not take it. */
    fun pendingFor(chatId: String): TranscriptJumpRequest? = request?.takeIf { it.chatId == chatId }

    /** Drops the request once its chat's transcript has landed on it. Only the same request may. */
    fun consume(token: Long) {
        if (request?.token == token) request = null
    }
}

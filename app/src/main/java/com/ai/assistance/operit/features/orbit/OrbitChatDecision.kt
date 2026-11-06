package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.OrbitRunEntity
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A due task's reason belongs only to the hidden turn currently being awaited in this chat. */
object OrbitChatDecision {
    class Turn(val turnId: String = java.util.UUID.randomUUID().toString()) {
        @Volatile var reason: String = ""
        @Volatile var silenceRequested = false
        @Volatile var confirmed = false
        @Volatile var accepting = true
        /** When the hidden notice was saved in the chat, so the run can find its turn later. */
        @Volatile var noticeAt: Long = 0L
    }

    private val turns = ConcurrentHashMap<String, Turn>()
    fun begin(chatId: String, turn: Turn) { turns[chatId] = turn }
    fun end(chatId: String, turn: Turn) { turns.remove(chatId, turn) }

    /** A new turn can start before the old turn finishes its post-processing and terminal signal. */
    fun onTurnStarted(chatId: String, turnId: String?) {
        turns[chatId]?.takeIf { it.turnId != turnId }?.accepting = false
    }

    fun record(chatId: String?, reason: String, silent: Boolean = false): Boolean {
        val turn = turns[chatId ?: return false] ?: return false
        if (!turn.accepting) return false
        require(reason.length <= 300) { "reason must be at most 300 characters" }
        if (reason.isNotBlank()) turn.reason = reason.trim()
        if (silent) turn.silenceRequested = true
        return true
    }

    fun encode(outcome: String, reason: String, chatId: String = "", noticeAt: Long = 0L): String =
        OrbitRunEntity.IN_CHAT + ":" + JSONObject().put("outcome", outcome).put("reason", reason.take(300)).apply {
            if (chatId.isNotBlank() && noticeAt > 0) put("chat", chatId).put("at", noticeAt)
        }

    /** The chat and saved time of the notice that woke this run; null for runs recorded without them. */
    fun noticeOf(decision: String): Pair<String, Long>? = runCatching {
        val value = JSONObject(decision.substringAfter(':'))
        val chat = value.optString("chat")
        val at = value.optLong("at")
        if (chat.isNotBlank() && at > 0) chat to at else null
    }.getOrNull()

    fun isChat(decision: String) = decision == OrbitRunEntity.IN_CHAT || decision.startsWith(OrbitRunEntity.IN_CHAT + ":")

    /** A task handed to Orbit in the chat whose reply then failed (the model was down). */
    fun failed(decision: String) = decision.startsWith(OrbitRunEntity.IN_CHAT + ":") &&
        runCatching { JSONObject(decision.substringAfter(':')).optString("outcome") == "failed" }.getOrDefault(false)

    fun describe(context: Context, decision: String, name: String): String {
        val label = label(context, decision, name)
        return reason(decision).takeIf { it.isNotBlank() }?.let { "$label\n$it" } ?: label
    }

    /** How the woken turn ended, in a line: spoke, stayed quiet, failed or not confirmed yet. */
    fun label(context: Context, decision: String, name: String): String {
        val value = runCatching { JSONObject(decision.substringAfter(':')) }.getOrNull()
            ?: return context.getString(R.string.orbit_decision_in_chat, name)
        return context.getString(when (value.optString("outcome")) {
            "send" -> R.string.orbit_decision_chat_spoke
            "skip" -> R.string.orbit_decision_chat_quiet
            "failed" -> R.string.orbit_decision_chat_failed
            else -> R.string.orbit_decision_chat_pending
        }, name)
    }

    /** The reason Orbit gave for speaking or staying quiet; empty when it gave none. */
    fun reason(decision: String): String =
        runCatching { JSONObject(decision.substringAfter(':')).optString("reason").trim() }.getOrDefault("")
}

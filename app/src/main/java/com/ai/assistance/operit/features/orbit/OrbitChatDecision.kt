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

    fun encode(outcome: String, reason: String): String =
        OrbitRunEntity.IN_CHAT + ":" + JSONObject().put("outcome", outcome).put("reason", reason.take(300))

    fun isChat(decision: String) = decision == OrbitRunEntity.IN_CHAT || decision.startsWith(OrbitRunEntity.IN_CHAT + ":")

    fun describe(context: Context, decision: String, name: String): String {
        val value = runCatching { JSONObject(decision.substringAfter(':')) }.getOrNull()
        if (value == null) return context.getString(R.string.orbit_decision_in_chat, name)
        val label = context.getString(when (value.optString("outcome")) {
            "send" -> R.string.orbit_decision_chat_spoke
            "skip" -> R.string.orbit_decision_chat_quiet
            "failed" -> R.string.orbit_decision_chat_failed
            else -> R.string.orbit_decision_chat_pending
        }, name)
        return value.optString("reason").takeIf { it.isNotBlank() }?.let { "$label\n$it" } ?: label
    }
}

package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.core.agent.collaboration.CollaborationPromptHistory
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * In an Orbit's own chats, every message from the user reaches the model starting with when it was
 * sent and the time zone then, e.g. `[2026-11-03 Tue 19:42 UTC+08:00 Asia/Taipei]`. The label is
 * built from the chat row's own time and the zone recorded for that moment, so an old message reads
 * the same in every request, also after the phone moves to another zone, and the provider can keep
 * reusing the conversation it already has.
 */
object OrbitMessageTime {
    /** Prompt-turn metadata: when the chat row of a user turn was sent (epoch ms). */
    const val SENT_AT = "orbit_sent_at"
    private const val STAMPED = "orbit_time_stamped"
    private const val PREFS = "orbit_message_zones"
    private const val KEY_LOG = "log"
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.ENGLISH)
    private val lock = Any()
    /** Each zone the phone used, from when it was first seen: (since epoch ms, zone id), oldest first. */
    private var zoneLog: List<Pair<Long, String>>? = null

    fun label(at: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val offset = time.offset.id.let { if (it == "Z") "+00:00" else it }
        return "[${FORMAT.format(time)} UTC$offset ${zone.id}]"
    }

    /**
     * The zone log with [current] at its end. A new zone counts from [since]; it never reaches back
     * before the previous entry. Times before the first entry use the first zone.
     */
    private fun zones(context: Context, since: Long, current: String = ZoneId.systemDefault().id):
        List<Pair<Long, String>> = synchronized(lock) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val known = zoneLog ?: prefs.getString(KEY_LOG, null).orEmpty().split(';').mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            val at = parts.firstOrNull()?.toLongOrNull()
            if (parts.size == 2 && at != null && parts[1].isNotBlank()) at to parts[1] else null
        }
        val last = known.lastOrNull()
        val updated = if (last?.second == current) known else {
            // Kept whole: dropping the oldest zones would relabel the oldest messages. A few dozen
            // bytes per change of zone.
            known + (maxOf(since, (last?.first ?: Long.MIN_VALUE) + 1) to current)
        }
        if (updated !== known) {
            prefs.edit().putString(KEY_LOG, updated.joinToString(";") { "${it.first}|${it.second}" }).apply()
        }
        zoneLog = updated
        updated
    }

    /**
     * The system switched to [zoneId] at [at]. Recorded right away, so a message sent in the new
     * zone carries it even when it is the first one after the switch.
     */
    fun recordZoneChange(context: Context, zoneId: String?, at: Long) {
        val id = zoneId?.takeIf { runCatching { ZoneId.of(it) }.isSuccess } ?: ZoneId.systemDefault().id
        zones(context, at, id)
    }

    private fun zoneAt(log: List<Pair<Long, String>>, at: Long): ZoneId {
        val id = (log.lastOrNull { it.first <= at } ?: log.firstOrNull())?.second
        return id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    }

    /** True in an Orbit's main chat and side chats, where the user talks with it. */
    suspend fun appliesTo(context: Context, chatId: String?): Boolean {
        if (chatId.isNullOrBlank()) return false
        val prefs = OrbitPreferences.getInstance(context)
        val orbit = (prefs.loadedSnapshot() ?: prefs.current()).orbitForChat(chatId)
        return orbit?.talkChatIds?.contains(chatId) == true
    }

    /**
     * Puts the send time before each user message. A message without its row's time can only be the
     * one being sent now, so the last one takes [now]; any other is left as it is rather than given
     * a time that would change from request to request. A zone change the system did not report is
     * noticed here and counts from [now], never from a message already in the chat.
     */
    fun stamp(context: Context, history: List<PromptTurn>, now: Long): List<PromptTurn> {
        val last = history.indexOfLast { it.kind == PromptTurnKind.USER }
        val log = zones(context, now)
        return history.mapIndexed { index, turn ->
            val at = (turn.metadata[SENT_AT] as? Long) ?: if (index == last) now else null
            if (at == null) turn else stamp(turn, at, log)
        }
    }

    /** One message sent during a turn, at its chat row's time [at]. */
    fun stamp(context: Context, turn: PromptTurn, at: Long): PromptTurn =
        stamp(turn, at, zones(context, System.currentTimeMillis()))

    private fun stamp(turn: PromptTurn, at: Long, log: List<Pair<Long, String>>): PromptTurn {
        if (turn.kind != PromptTurnKind.USER || turn.metadata[STAMPED] == true ||
            turn.metadata[CollaborationPromptHistory.EVENT_METADATA] == true ||
            turn.metadata[CollaborationPromptHistory.INTERMEDIATE_METADATA] == true ||
            turn.content.isBlank()) return turn
        return turn.copy(content = label(at, zoneAt(log, at)) + "\n" + turn.content,
            metadata = turn.metadata + (STAMPED to true))
    }
}

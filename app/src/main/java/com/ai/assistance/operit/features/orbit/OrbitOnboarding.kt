package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The first talk with a new Orbit. The steps (terminal, name, role card, memories, avatar,
 * permissions) live in docs/first-meeting.md; the runtime info only carries a short, unchanging
 * pointer to it, so the prompt stays the same turn after turn until the first talk ends with
 * orbit_soul first_talk_done, or after [USER_MESSAGES] user messages at most.
 */
internal object OrbitOnboarding {
    private const val MARKER = ".onboarded"
    /** After this many user messages the first talk is over, whatever was settled. */
    private const val USER_MESSAGES = 30

    private fun marker(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), MARKER)

    suspend fun markDone(context: Context, orbitId: String) = withContext(Dispatchers.IO) {
        runCatching { marker(context, orbitId).apply { parentFile?.mkdirs() }.createNewFile() }
        Unit
    }

    /** The pointer for the main chat of a new Orbit, or null once the first talk is over. */
    suspend fun hint(context: Context, orbit: OrbitProfile, chatId: String?, english: Boolean): String? {
        if (chatId != orbit.chatId) return null
        if (withContext(Dispatchers.IO) { marker(context, orbit.id).exists() }) return null
        val users = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).orbitTaskDao().userMessageCount(orbit.chatId)
        if (users >= USER_MESSAGES) {
            markDone(context, orbit.id)
            return null
        }
        return if (english) HINT_EN else HINT_ZH
    }

    /**
     * Whether the first talk is still on, for the prompt revision: the system prompt is kept
     * between turns, so it is rebuilt once when the pointer goes away.
     */
    suspend fun revision(context: Context, orbit: OrbitProfile): String =
        if (hint(context, orbit, orbit.chatId, false) != null) "first-meeting" else "started"

    private const val HINT_ZH =
        "<orbit_first_talk>初次见面还没走完。先用 orbit_file 读 docs/first-meeting.md，按里面的步骤带用户走；" +
            "都问过了就调用 orbit_soul action=first_talk_done。</orbit_first_talk>"

    private const val HINT_EN =
        "<orbit_first_talk>The first meeting is not finished. Read docs/first-meeting.md with orbit_file and walk the user " +
            "through its steps; once every step was asked, call orbit_soul action=first_talk_done.</orbit_first_talk>"
}

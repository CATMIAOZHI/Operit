package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The first talk with a new Orbit, like Muse's onboarding: Orbit offers a card to pick its name
 * and vibe. The hint stays in the prompt only until Orbit settles its name or the chat is under way.
 */
internal object OrbitOnboarding {
    private const val MARKER = ".onboarded"
    /** After this many user messages the first talk is over, card or not. */
    private const val USER_MESSAGES = 4

    private fun marker(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), MARKER)

    suspend fun markDone(context: Context, orbitId: String) = withContext(Dispatchers.IO) {
        runCatching { marker(context, orbitId).apply { parentFile?.mkdirs() }.createNewFile() }
        Unit
    }

    /** The hint for the main chat of a new Orbit, or null. */
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

    private val HINT_ZH = """
        <orbit_first_talk>
        你和用户刚开始相处。合适的时候（最好在头一两次回复里，先回应他说的话），用 dynamic_ui 工具包做一张起名卡片：
        给 3 到 4 个适合你的名字（第一个是你现在的名字），再给几种说话气质（比如温柔、干脆、俏皮、安静），每个选项一个按钮，按钮用 ai_submit 把选择交回给你。
        用户选好后，用 orbit_set_name 改名，用 orbit_soul 把气质写进 IDENTITY.md。用户想保持现在的名字，也用 orbit_set_name 确认一次。用户不想选就别再提。
        </orbit_first_talk>
    """.trimIndent()

    private val HINT_EN = """
        <orbit_first_talk>
        You and the user are just getting started. When it fits (ideally in your first reply or two, after answering what
        they said), make a naming card with the dynamic_ui package: offer 3 or 4 names that suit you (your current name
        first) and a few vibes for how you talk (for example warm, direct, playful, calm), one button per choice, each
        button sending the choice back to you with ai_submit. When the user picks, rename yourself with orbit_set_name and
        write the vibe into IDENTITY.md with orbit_soul. If they keep your current name, confirm it once with
        orbit_set_name. If they would rather not choose, drop it.
        </orbit_first_talk>
    """.trimIndent()
}

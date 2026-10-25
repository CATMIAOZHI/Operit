package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The first talk with a new Orbit: it asks whether the user wants another name for it, whether to
 * shape SOUL.md after a role card, and whether to bring memories over from another AI. The hint
 * stays in the prompt for the first few user messages only.
 */
internal object OrbitOnboarding {
    private const val MARKER = ".onboarded"
    private const val KEEP_ALIVE_MARKER = ".keepalive_offered"
    /** After this many user messages the first talk is over, whatever was settled. */
    private const val USER_MESSAGES = 8
    /** After this many user messages the keep-alive offer is past too; long-standing Orbits never get it. */
    private const val KEEP_ALIVE_USER_MESSAGES = 20
    /** Orbits made before the keep-alive offer existed (2026-10-23 00:00, UTC+8) are not offered it. */
    private const val KEEP_ALIVE_SINCE = 1_792_684_800_000L

    private fun marker(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), MARKER)
    private fun keepAliveMarker(context: Context, orbitId: String) =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), KEEP_ALIVE_MARKER)

    suspend fun markDone(context: Context, orbitId: String) = withContext(Dispatchers.IO) {
        runCatching { marker(context, orbitId).apply { parentFile?.mkdirs() }.createNewFile() }
        Unit
    }

    /** The keep-alive card was offered (or is no longer due): the hint about it stops. */
    suspend fun markKeepAliveOffered(context: Context, orbitId: String) = withContext(Dispatchers.IO) {
        runCatching { keepAliveMarker(context, orbitId).apply { parentFile?.mkdirs() }.createNewFile() }
        Unit
    }

    /** The hint for the main chat of a new Orbit, or null: first the naming card, then once the keep-alive card. */
    suspend fun hint(context: Context, orbit: OrbitProfile, chatId: String?, english: Boolean): String? {
        if (chatId != orbit.chatId) return null
        if (withContext(Dispatchers.IO) { marker(context, orbit.id).exists() }) return keepAliveHint(context, orbit, english)
        val users = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).orbitTaskDao().userMessageCount(orbit.chatId)
        if (users >= USER_MESSAGES) {
            markDone(context, orbit.id)
            return null
        }
        val importPrompt = context.getString(com.ai.assistance.operit.R.string.orbit_import_prompt)
        return (if (english) HINT_EN else HINT_ZH).replace("{import_prompt}", importPrompt)
    }

    /**
     * Once the name is settled, a new Orbit asks once, in its own words, to keep Operit awake in the
     * background, and offers the keep-alive card. Nothing is said when both are already on.
     */
    private suspend fun keepAliveHint(context: Context, orbit: OrbitProfile, english: Boolean): String? {
        if (orbit.createdAt < KEEP_ALIVE_SINCE) return null
        if (withContext(Dispatchers.IO) { keepAliveMarker(context, orbit.id).exists() }) return null
        val users = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).orbitTaskDao().userMessageCount(orbit.chatId)
        if (users >= KEEP_ALIVE_USER_MESSAGES) {
            markKeepAliveOffered(context, orbit.id)
            return null
        }
        if (OrbitTaskBlock.status(context).allOn) return null
        return if (english) KEEP_ALIVE_EN else KEEP_ALIVE_ZH
    }

    private val KEEP_ALIVE_ZH = """
        <orbit_keep_alive>
        初次见面已经过去了。找个自然的时机（别打断用户正在说的事），用你自己的口吻提一句：你有些事要在后台按时帮他做（提醒、盯着网页、夜里整理），得让 Operit 在后台醒着才行。
        然后调用 orbit_permission_card（permission=keep_alive）放一张卡，让他一键打开。只提这一次；他不想开就算了。
        </orbit_keep_alive>
    """.trimIndent()

    private val KEEP_ALIVE_EN = """
        <orbit_keep_alive>
        The first talk is behind you. When it fits naturally (without cutting into what the user is saying), mention in your own
        words that some things you do for them run in the background on time (reminders, watching pages, tidying up at
        night), so Operit needs to stay awake in the background. Then call orbit_permission_card with
        permission=keep_alive to put a card in the chat they can turn it on from. Bring it up only this once; if they
        would rather not, drop it.
        </orbit_keep_alive>
    """.trimIndent()

    private val HINT_ZH = """
        <orbit_first_talk>
        你和用户刚开始相处。你最初的名字是 Orbit，SOUL.md 一开始是默认的起点；名字和 SOUL.md 以当前实际为准。先回应用户说的话，再在头几次回复里自然地问他三件事。一次问一件，别堆成清单；已经问过、办完或他说不用的，不再提。
        1. 要不要给你换个名字。用 orbit_create_options 给选项：「就叫 Orbit」、两个你觉得合适的名字、「我自己起一个」（最多 4 个选项）。定下来后用 orbit_set_name 改名；保持 Orbit 也调用一次确认。
        2. 要不要参考某张角色卡来定你的性格。他想参考时，用 orbit_soul action=list_role_cards 列出角色卡让他选，再用 action=read_role_card 读那一张。把它的性格和说话方式融进 SOUL.md（先 read 再 update，起点里仍然适用的几条留着），改完告诉他改了什么。不想参考就保留现在的 SOUL.md。
        3. 要不要把其他 AI（比如 ChatGPT、豆包、Kimi）记得的关于他的事搬过来。想搬的话，把下面这段提示词原样发给他，让他交给那个 AI，再把回答贴回来：
        「{import_prompt}」
        收到回答后，按 memory 文档里导入记忆的规则，提炼长期有用的事实和偏好，写进 user.md 和 memory.md，再告诉他记下了哪些。
        </orbit_first_talk>
    """.trimIndent()

    private val HINT_EN = """
        <orbit_first_talk>
        You and the user are just getting started. Your first name was Orbit and SOUL.md began as the default starting
        point; your current name and SOUL.md are whatever they are now.
        Answer what they said first, then over your first few replies ask them three things, naturally and one at a time,
        never as a list. Do not bring up again anything already asked, done, or declined.
        1. Whether they want another name for you. Offer choices with orbit_create_options: "Keep Orbit", two names you
           think suit you, and "I'll pick one myself" (at most 4 options). Once settled, rename yourself with orbit_set_name; if they
           keep Orbit, confirm it once with orbit_set_name too.
        2. Whether to shape your character after one of their role cards. If they want to, list the cards with orbit_soul
           action=list_role_cards for them to pick, then read that one with action=read_role_card. Work its character and
           way of talking into SOUL.md (read first, then update, keeping the starting truths that still fit) and tell them
           what changed. If not, keep the current SOUL.md.
        3. Whether to bring over what another AI (ChatGPT, Gemini, Claude and so on) remembers about them. If they want to,
           send them this prompt exactly as it is to give to that AI, and ask them to paste its answer back:
           "{import_prompt}"
           When the answer comes, follow the memory guide on importing: keep the facts and preferences that stay useful,
           write them into user.md and memory.md, then tell them what you saved.
        </orbit_first_talk>
    """.trimIndent()
}

package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.core.config.DistributionConfig
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.CrashRecoveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * After the app crashed, the running Orbit hears of it once when the app starts again: what it was
 * doing in the background was cut off, and whether to go on or tell the user is its own call.
 */
internal object OrbitCrashNotice {
    private const val KEY = "app_crashed@"
    /** A note queued by a process that died is told again after this (OrbitNotices' stale time). */
    private const val RETRY_MS = 16 * 60_000L
    /** A crash this old is no longer worth bringing up. */
    private const val TOO_OLD_MS = 24 * 60 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called once per start of the app's main process. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { tell(app) }
            delay(RETRY_MS)
            runCatching { tell(app) }
        }
    }

    private suspend fun tell(app: Context) {
        val crash = CrashRecoveryState.lastCrash(app) ?: return
        if (tooOld(crash.at)) {
            CrashRecoveryState.forgetCrash(app, crash.at)
            return
        }
        val orbits = OrbitPreferences.getInstance(app)
        val orbit = orbits.current().active?.takeUnless { it.deleting } ?: return
        // An Orbit made after the crash was not running when it happened.
        if (orbit.createdAt > crash.at) {
            CrashRecoveryState.forgetCrash(app, crash.at)
            return
        }
        val key = KEY + crash.at
        if (OrbitNotices.delivered(app, orbit.id, key)) {
            told(app, orbit.id, crash.at)
            return
        }
        val time = DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .format(Instant.ofEpochMilli(crash.at).atZone(ZoneId.systemDefault()))
        val issues = "https://github.com/${DistributionConfig.OWNER}/${DistributionConfig.SOURCE_REPOSITORY}/issues"
        val what = crash.summary.ifBlank { "-" }
        OrbitNotices.once(app, orbit.id, key,
            "[orbit-app-crashed] 应用刚才崩溃了\n这是 App 发给你的说明，不是用户说的，用户也看不到。Orbit 在 $time 崩溃，" +
                "现在重新打开了。出错的位置（只有异常类型和代码位置）：\n$what\n" +
                "崩溃时你开的子代理和其他后台任务都已经中断；终端里在跑的命令（比如编译）跟你断了联系，可能已经停了，" +
                "也可能还在后台跑。请自己检查一下：子代理还在不在、终端里的命令还在不在跑、结果对不对，再决定要不要重新跑。" +
                "之前在做的事要不要接着做、要不要告诉用户，" +
                "按刚才的对话自己决定。如果看起来是 App 本身的问题，可以建议用户在「Orbit 设置 → 帮助与关于 → 导出应用日志」" +
                "导出日志（尽快导出，可能还能找到崩溃前的记录），再到 $issues 提一个 issue；提醒用户日志里可能有聊天片段和" +
                "设备信息，公开发出前先看一遍、遮掉不想公开的内容。没什么要做的，就调用 " +
                "orbit_nothing_to_do 保持安静。",
            "[orbit-app-crashed] The app just crashed\nThis is a note from the app, not from the user; they cannot see " +
                "it. Orbit crashed at $time and has been opened again. Where it failed (exception types and code " +
                "locations only):\n$what\n" +
                "Sub-agents you started and other background work were cut off; commands running in the terminal (such " +
                "as a build) lost their link to you and may have stopped or may still be running. Check for yourself " +
                "whether the sub-agents are still there, whether the terminal commands are still running and whether " +
                "their results are right, then decide whether to run them again. Whether to go on with what you were doing, and whether to " +
                "tell the user, is your call from the conversation so far. If it looks like a fault in the app itself, " +
                "you can suggest the user export the log under Orbit settings → Help and about → Export app log (soon, " +
                "while it may still hold the records from before the crash) and open an issue at $issues; " +
                "remind them the log may hold bits of chats and device details, so they should read it and hide " +
                "anything private before posting it. When there is nothing to do, call orbit_nothing_to_do to stay quiet.",
            // Asked again right before it goes out: still this crash, still recent, and this Orbit still the running one.
            wanted = {
                CrashRecoveryState.lastCrash(app)?.at == crash.at && !tooOld(crash.at) &&
                    orbits.current().active?.let { it.id == orbit.id && !it.deleting } == true
            },
            onSent = { told(app, orbit.id, crash.at) })
    }

    private fun tooOld(at: Long): Boolean = System.currentTimeMillis() - at !in 0..TOO_OLD_MS

    /** Orbit got the note: the crash is forgotten at once, so no other Orbit hears of it, and older marks go. */
    private fun told(app: Context, orbitId: String, at: Long) {
        CrashRecoveryState.forgetCrash(app, at)
        OrbitNotices.clear(app, orbitId, KEY, keep = listOf(KEY + at))
    }
}

package com.ai.assistance.operit.features.orbit

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.ai.assistance.operit.api.chat.AIForegroundService
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Permission cards Orbit puts in the chat, so the user turns on what it needs with one tap. Only
 * keep-alive for now (Operit's background keep-alive and the battery-optimization exemption); more
 * kinds (notifications, SMS, calendar) can follow the same card.
 *
 * "Don't remind me" is not kept as a switch the app obeys: it is told to Orbit, which writes it into
 * PROACTIVE_PREFERENCES.md and decides from there. The card only remembers that the note reached
 * Orbit; while it waits for a free chat the card says so, and a note that never went out (the chat
 * stayed busy, the app closed) leaves the button there to press again.
 */
internal object OrbitPermissionCards {
    const val KEEP_ALIVE = "keep_alive"
    val kinds = setOf(KEEP_ALIVE)

    const val MARKER = "[orbit-permission]"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")
    private const val IDLE_WAIT_MS = 10 * 60_000L
    private const val PREFS = "orbit_permission_cards"
    private const val KEY_DISMISSED = "dismissed"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** The saved list is read and written one at a time. */
    private val fileLock = kotlinx.coroutines.sync.Mutex()
    private val loading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Card ids whose "don't remind me" reached Orbit, so every copy of the card shows it at once. */
    private val dismissedFlow = MutableStateFlow<Set<String>?>(null)
    /** Card ids whose note is waiting for the chat; kept in memory only, so a closed app allows a retry. */
    private val sendingFlow = MutableStateFlow<Set<String>>(emptySet())
    val sending: StateFlow<Set<String>> = sendingFlow

    /** The last settings read, so a card scrolled back into view starts at its real height. */
    @Volatile
    var lastStatus: OrbitTaskBlock.Status? = null

    fun titleOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Null until read; the first call reads it off the main thread, so a card never waits on the disk. */
    fun dismissed(context: Context): StateFlow<Set<String>?> {
        if (loading.compareAndSet(false, true)) {
            val app = context.applicationContext
            io.launch {
                val saved = fileLock.withLock { readDismissed(app) }
                // A card dismissed while this was read is kept.
                dismissedFlow.update { saved + it.orEmpty() }
            }
        }
        return dismissedFlow
    }

    private fun readDismissed(app: Context): Set<String> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet()

    /** Turns on Operit's background keep-alive, the same as the switch in the display settings. */
    suspend fun enableKeepAlive(context: Context) {
        val app = context.applicationContext
        DisplayPreferencesManager.getInstance(app).saveDisplaySettings(enableBackgroundKeepAlive = true)
        AIForegroundService.refreshBackgroundKeepAlive(app)
    }

    /** Opens the system's battery-optimization prompt for Operit; false when no screen could open. */
    fun openBatteryExemption(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pkg = context.packageName
        val tries = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (intent in tries) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        return false
    }

    /** The user pressed "don't remind me": Orbit hears it once its chat is free, and then the card shows it. */
    fun dismiss(context: Context, orbit: OrbitProfile, chatId: String, permission: String, cardId: String) {
        val app = context.applicationContext
        if (cardId in sendingFlow.value || cardId in dismissed(app).value.orEmpty()) return
        val target = chatId.takeIf { it in orbit.talkChatIds } ?: orbit.chatId
        if (target.isBlank()) return
        val notice = dismissNotice(LocaleUtils.usesEnglishPrompt(app), permission)
        sendingFlow.value = sendingFlow.value + cardId
        scope.launch {
            try {
                val deadline = android.os.SystemClock.elapsedRealtime() + IDLE_WAIT_MS
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    val told = OrbitWake.tell(app, orbit, target, notice, left, left,
                        onSent = { markDismissed(app, cardId) }, announce = false)
                    if (told != OrbitWake.Told.BUSY) break
                    kotlinx.coroutines.delay(1_500)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitPermissionCards", "Could not tell Orbit about a dismissed card: ${e.message}")
            } finally {
                sendingFlow.value = sendingFlow.value - cardId
            }
        }
    }

    private fun markDismissed(app: Context, cardId: String) {
        dismissed(app)
        dismissedFlow.update { it.orEmpty() + cardId }
        // Saved off the main thread, merged with the file so earlier dismissals are not dropped.
        io.launch {
            fileLock.withLock {
                val now = (readDismissed(app) + cardId).toList().takeLast(200).toSet()
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_DISMISSED, now).apply()
            }
        }
    }

    fun dismissNotice(english: Boolean, permission: String): String {
        val what = if (permission == KEEP_ALIVE) {
            if (english) "background keep-alive" else "后台保活"
        } else permission
        return if (english) {
            "$MARKER No more $what reminders\n" +
                "The user tapped \"Don't remind me\" on the $what card. This is not a message from the user; they typed " +
                "nothing here and cannot see this note.\n" +
                "Write into the \"Never tell me about\" section of PROACTIVE_PREFERENCES.md that they do not want reminders " +
                "about $what or about tasks held back for it, changing only that section. Do not offer the card again unless they ask. " +
                "Then reply with one short, light line, or call ${OrbitReactionNotice.NOTHING_TO_DO} if nothing needs saying. " +
                "Do not mention hidden notes."
        } else {
            "$MARKER 不再提醒$what\n" +
                "用户在「$what」卡片上点了「以后别提醒」。这不是用户发的消息，用户没有在这里打字，也看不到这段话。\n" +
                "把“不想再收到关于$what、以及因此被延后的任务的提醒”写进 PROACTIVE_PREFERENCES.md 的「别跟我说」，只改这一节。" +
                "除非用户自己问，不要再放这张卡。然后轻松地回一句短话，没什么要说就调用 ${OrbitReactionNotice.NOTHING_TO_DO}。" +
                "不要提“隐藏消息”这些内部说法。"
        }
    }
}

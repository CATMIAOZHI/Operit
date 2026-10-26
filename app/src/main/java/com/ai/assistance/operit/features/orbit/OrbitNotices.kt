package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hidden notes the app sends Orbit about something going wrong in the background (a model that
 * fails, notifications that cannot get through). Each problem is told once until [clear] says it
 * works again, so a problem that lasts does not fill the chat.
 *
 * A mark is the time a note was queued, or [DELIVERED] once Orbit got it. A queued note that never
 * went out (the app was closed while it waited for a quiet chat) goes stale and is told again.
 */
internal object OrbitNotices {
    private const val PREFS = "orbit_notices"
    private const val DELIVERED = -1L
    /** Longer than a note may wait for a quiet chat (ten minutes). */
    private const val QUEUED_STALE_MS = 15 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Tells Orbit once about [key]; nothing more until [clear] for it. */
    fun once(context: Context, orbitId: String, key: String, zh: String, en: String) {
        val name = "$orbitId|$key"
        val token: Long
        synchronized(this) {
            val p = prefs(context)
            val mark = p.all[name] as? Long
            val now = System.currentTimeMillis()
            if (mark == DELIVERED || (mark != null && now - mark in 0 until QUEUED_STALE_MS)) return
            p.edit().putLong(name, now).commit()
            token = now
        }
        tell(context, orbitId, zh, en, name, token)
    }

    /** The note queued as [token] is still wanted: not cleared and not replaced by a newer one. */
    private fun stillQueued(context: Context, name: String, token: Long): Boolean =
        synchronized(this) { prefs(context).all[name] as? Long == token }

    /** The problems under [prefix] work again; a later failure is told afresh. */
    fun clear(context: Context, orbitId: String, prefix: String) {
        synchronized(this) {
            val p = prefs(context)
            val names = p.all.keys.filter { it.startsWith("$orbitId|$prefix") }
            if (names.isNotEmpty()) p.edit().apply { names.forEach { remove(it) } }.apply()
        }
    }

    /**
     * A hidden note in Orbit's main chat, sent once the chat is quiet (waiting up to ten minutes).
     * Sent: the mark [name] becomes delivered. Not sent: it is dropped, so the next time the problem shows it is told.
     */
    private fun tell(context: Context, orbitId: String, zh: String, en: String, name: String, token: Long) {
        val app = context.applicationContext
        scope.launch {
            var sent = false
            try {
                val orbit = OrbitPreferences.getInstance(app).current().orbits
                    .firstOrNull { it.id == orbitId && !it.deleting && it.chatId.isNotBlank() } ?: return@launch
                val note = if (LocaleUtils.usesEnglishPrompt(app)) en else zh
                val deadline = android.os.SystemClock.elapsedRealtime() + 10 * 60_000L
                while (true) {
                    val left = deadline - android.os.SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    // The problem went away while the note waited: do not send the old warning.
                    if (!stillQueued(app, name, token)) break
                    val told = OrbitWake.tell(
                        app, orbit, orbit.chatId, note, left, left,
                        ready = { stillQueued(app, name, token) },
                        announce = false,
                    )
                    sent = told.sent
                    if (told != OrbitWake.Told.BUSY) break
                    delay(1_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLogger.w("OrbitNotices", "Could not tell Orbit: ${e.message}")
            } finally {
                synchronized(this@OrbitNotices) {
                    val p = prefs(app)
                    // Cleared or queued again meanwhile: leave that mark alone.
                    if (p.all[name] as? Long == token) {
                        if (sent) p.edit().putLong(name, DELIVERED).apply() else p.edit().remove(name).apply()
                    }
                }
            }
        }
    }
}

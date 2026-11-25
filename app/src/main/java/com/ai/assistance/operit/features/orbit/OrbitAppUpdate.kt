package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.BuildConfig
import com.ai.assistance.operit.core.config.DistributionConfig
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * After the app is updated, the running Orbit hears once that the version changed and where the
 * release page is. What changed is left on that page; Orbit reads it only when needed.
 */
internal object OrbitAppUpdate {
    private const val PREFS = "orbit_app_version"
    private const val SEEN = "seen"
    /** The update not told yet: its version, the one before it and when it was noticed. */
    private const val PENDING = "pending"
    private const val PENDING_FROM = "pending_from"
    private const val PENDING_AT = "pending_at"
    private const val KEY = "app_updated:"
    /** A note queued by a process that died is told again after this (OrbitNotices' stale time). */
    private const val RETRY_MS = 16 * 60_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** At start: notices a new version and tells the running Orbit, retrying once a queued note goes stale. */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { notice(app); tell(app) }
            delay(RETRY_MS)
            runCatching { tell(app) }
        }
    }

    /** An Orbit was started again: a version change from while it was paused is told now. */
    fun onOrbitResumed(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { tell(app) } }
    }

    /** Records an update once per change of version; a fresh install or a reinstall of the same version is none. */
    @Synchronized
    private fun notice(app: Context) {
        val now = BuildConfig.VERSION_NAME
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(SEEN, null)
        if (seen == now) return
        val edit = prefs.edit().putString(SEEN, now)
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        // Nothing seen before: an update only if the app was installed earlier (the first version with this check).
        val updated = seen != null || info.firstInstallTime != info.lastUpdateTime
        if (updated) edit.putString(PENDING, now).putString(PENDING_FROM, seen).putLong(PENDING_AT, System.currentTimeMillis())
        edit.commit()
    }

    private suspend fun tell(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(PENDING, null) ?: return
        val at = prefs.getLong(PENDING_AT, 0L)
        val previous = prefs.getString(PENDING_FROM, null)?.takeIf { it != version }
        val orbit = OrbitPreferences.getInstance(app).current().active?.takeUnless { it.deleting } ?: return
        // An Orbit made after the update started on this version; there is nothing new for it.
        if (orbit.createdAt > at) return
        val repo = if (BuildConfig.PERSONAL_DEV_UPDATE_CHANNEL) DistributionConfig.NIGHTLY_REPOSITORY
            else DistributionConfig.SOURCE_REPOSITORY
        val releases = "https://github.com/${DistributionConfig.OWNER}/$repo/releases"
        val fromZh = previous?.let { "从 $it " }.orEmpty()
        val fromEn = previous?.let { " from $it" }.orEmpty()
        // One key per update, so going back to a version told before is told again.
        OrbitNotices.once(app, orbit.id, "$KEY$version@$at",
            "[orbit-app-updated] 应用已更新\n这是 App 发给你的说明，不是用户说的，用户也看不到。Orbit ${fromZh}更新到了 $version。" +
                "这个版本的发布记录在 $releases ，用户问起这次更新时再去看，页面上没写的就如实说不清楚。" +
                "不用主动告诉用户，现在调用 orbit_nothing_to_do 保持安静即可。",
            "[orbit-app-updated] The app was updated\nThis is a note from the app, not from the user; they cannot see it. " +
                "Orbit was updated$fromEn to $version. This version's release record is at $releases — look at it when " +
                "the user asks about the update, and say plainly when the page does not tell. No need to tell the user; " +
                "call orbit_nothing_to_do now to stay quiet.")
    }
}

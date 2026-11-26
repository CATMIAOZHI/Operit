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
    /** Whether the version last seen had the built-in browser engine. */
    private const val ENGINE = "browser_engine"
    /** When the move from the phone's WebView to the built-in engine was noticed: the note's key. */
    private const val ENGINE_SINCE = "browser_engine_since"
    /** When that version was installed: Orbits made before it had used the old browser. */
    private const val ENGINE_INSTALLED = "browser_engine_installed"
    /** The version that brought the engine. */
    private const val ENGINE_VERSION = "browser_engine_version"
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
        scope.launch { runCatching { notice(app); tell(app) } }
    }

    /**
     * Records an update once per change of version; a fresh install or a reinstall of the same
     * version is none. The move to the built-in browser engine is recorded on its own, also when
     * the version stayed the same.
     */
    @Synchronized
    private fun notice(app: Context) {
        val now = BuildConfig.VERSION_NAME
        val engine = BuildConfig.BROWSER_ENGINE_BUILT_IN
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(SEEN, null)
        // Versions before this check all ran on the phone's WebView.
        val hadEngine = prefs.getBoolean(ENGINE, false)
        val engineKnown = prefs.contains(ENGINE)
        if (seen == now && engineKnown && hadEngine == engine) return
        val edit = prefs.edit().putString(SEEN, now).putBoolean(ENGINE, engine)
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        // Nothing seen before: an update only if the app was installed earlier (the first version with this check).
        val updated = seen != null || info.firstInstallTime != info.lastUpdateTime
        val at = System.currentTimeMillis()
        if (updated && seen != now) edit.putString(PENDING, now).putString(PENDING_FROM, seen).putLong(PENDING_AT, at)
        // Moving to the engine leaves the websites' logins behind, which the user hears about once.
        // The same version installed again counts only when this check had seen it without the engine.
        val movedToEngine = engine && !hadEngine && updated && (seen != now || engineKnown)
        if (movedToEngine) {
            edit.putLong(ENGINE_SINCE, at).putLong(ENGINE_INSTALLED, info.lastUpdateTime).putString(ENGINE_VERSION, now)
        } else if (!engine) {
            // Back on the WebView: an engine note not yet told is no longer true.
            edit.remove(ENGINE_SINCE).remove(ENGINE_INSTALLED).remove(ENGINE_VERSION)
        }
        edit.commit()
    }

    /** What [tell] reads, read at once under [notice]'s lock so it never mixes two updates. */
    private class Snapshot(
        val version: String?,
        val at: Long,
        val previous: String?,
        val engineSince: Long,
        val engineInstalled: Long,
        val engineVersion: String?,
    )

    @Synchronized
    private fun snapshot(app: Context): Snapshot {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(PENDING, null)
        return Snapshot(
            version = version,
            at = prefs.getLong(PENDING_AT, 0L),
            previous = prefs.getString(PENDING_FROM, null)?.takeIf { it != version },
            engineSince = prefs.getLong(ENGINE_SINCE, 0L),
            engineInstalled = prefs.getLong(ENGINE_INSTALLED, 0L),
            engineVersion = prefs.getString(ENGINE_VERSION, null),
        )
    }

    private suspend fun tell(app: Context) {
        val s = snapshot(app)
        if (s.version == null && s.engineSince <= 0L) return
        val orbit = OrbitPreferences.getInstance(app).current().active?.takeUnless { it.deleting } ?: return
        // Read again after the wait: an update noticed meanwhile is told by the next call.
        if (snapshot(app).let { it.at != s.at || it.engineSince != s.engineSince }) return
        val repo = if (BuildConfig.PERSONAL_DEV_UPDATE_CHANNEL) DistributionConfig.NIGHTLY_REPOSITORY
            else DistributionConfig.SOURCE_REPOSITORY
        val releases = "https://github.com/${DistributionConfig.OWNER}/$repo/releases"
        val current = BuildConfig.VERSION_NAME
        // Moved to the built-in browser: an Orbit made before that version was installed had used
        // the old one, and tells the user, once.
        if (BuildConfig.BROWSER_ENGINE_BUILT_IN && s.engineSince > 0L && orbit.createdAt <= s.engineInstalled) {
            val engineKey = "${KEY}browser@${s.engineSince}"
            if (!OrbitNotices.delivered(app, orbit.id, engineKey)) {
                val since = s.engineVersion ?: current
                // Once it is told, the update pending now is told by it too: its own quiet note is not sent.
                OrbitNotices.once(app, orbit.id, engineKey,
                    "[orbit-app-updated] 应用已更新\n这是 App 发给你的说明，不是用户说的，用户也看不到。Orbit 现在是 $current。" +
                        "从 $since 起 Orbit 自带浏览器内核，不再用手机系统的网页组件，所以之前在 Orbit 浏览器里登录过的网站，" +
                        "包括你替用户登录的，都要重新登录一次；聊天、记忆和设置都还在。用一两句自然的话告诉用户这件事，" +
                        "不用讲技术细节。发布记录在 $releases ，用户问起这次更新时再去看。",
                    "[orbit-app-updated] The app was updated\nThis is a note from the app, not from the user; they cannot see it. " +
                        "Orbit is now $current. Since $since Orbit has its own browser engine instead of the " +
                        "phone's web component, so websites logged in before in Orbit's browser, including ones you logged in for " +
                        "the user, need logging in once more; chats, memory and settings are all still there. Tell the user this in " +
                        "a sentence or two, without technical detail. The release records are at $releases — look at them " +
                        "when the user asks about the update.",
                    alsoTells = s.version?.let { "$KEY$it@${s.at}" })
                return
            }
        }
        val version = s.version ?: return
        val at = s.at
        val fromZh = s.previous?.let { "从 $it " }.orEmpty()
        val fromEn = s.previous?.let { " from $it" }.orEmpty()
        // An Orbit made after the update started on this version; there is nothing new for it.
        if (orbit.createdAt > at) return
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

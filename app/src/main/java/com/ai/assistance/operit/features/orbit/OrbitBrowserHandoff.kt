package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.provider.Settings
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.*
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.OrbitTaskEntity
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Human-only browser actions. Background agents cannot open the browser or confirm completion. */
internal object OrbitBrowserHandoff {
    suspend fun open(context: Context, expected: OrbitTaskEntity) = OrbitExecutionGate.serialized {
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        check(dao.task(expected.id)?.let { it.version == expected.version && it.state == "WAITING_USER" } == true)
        val input = checkNotNull(dao.input(expected.id))
        check(input.taskVersion == expected.version)
        OrbitTaskRepository.validateSource(input.requestedUrl)
        check(Settings.canDrawOverlays(context)) { context.getString(com.ai.assistance.operit.R.string.orbit_browser_overlay_required) }
        val alreadyOwned = OrbitBrowserControl.isOwner(expected.id)
        OrbitBrowserControl.acquire(expected.id)
        try {
            val browser = StandardBrowserSessionTools(context.applicationContext)
            // The user signs in where the Orbit browses, so the login lands in the Orbit's own set.
            val profile = BrowserProfiles.forOrbit(context, expected.orbitId)
            val sessionId = withContext(Dispatchers.Main) {
                BrowserProfiles.withScope(profile) {
                browser.ensureOverlayOnMain(context.applicationContext, initialExpanded = true)
                // Never hand the user a document that an earlier AI script may still mutate.
                val session = input.sessionId.takeIf { alreadyOwned }?.let(StandardBrowserSessionTools.sessions::get)
                    ?.takeIf { it.profileName == profile }
                    ?: browser.createSessionTabOnMain(context.applicationContext, input.requestedUrl, expected.title)
                browser.ensureSessionAttachedOnMain(session.id)
                browser.setExpandedOnMain(true)
                session.id
                }
            }
            dao.saveInput(input.copy(sessionId = sessionId))
        } catch (error: Exception) {
            OrbitBrowserControl.release(expected.id)
            throw error
        }
    }

    suspend fun resume(context: Context, expected: OrbitTaskEntity, note: String) {
        require(note.length <= 4000)
        val dao = AppDatabase.getDatabase(context).orbitTaskDao()
        val input = checkNotNull(dao.input(expected.id))
        check(input.taskVersion == expected.version)
        val humanEpoch = OrbitBrowserControl.humanEpoch(expected.id)
        val observation = if (input.requestedUrl.isNotBlank()) {
            check(humanEpoch != null) { context.getString(com.ai.assistance.operit.R.string.orbit_browser_reopen) }
            val session = checkNotNull(StandardBrowserSessionTools.sessions[input.sessionId]) {
                context.getString(com.ai.assistance.operit.R.string.orbit_browser_reopen)
            }
            check(session.pageLoaded && !session.isLoading && !session.hasSslError && session.pendingDialog == null) {
                context.getString(com.ai.assistance.operit.R.string.orbit_browser_not_ready)
            }
            val browser = StandardBrowserSessionTools(context.applicationContext)
            withContext(Dispatchers.IO) { browser.captureSnapshotText(session).take(20_000) }
        } else ""
        OrbitExecutionGate.serialized {
            check(OrbitPreferences.getInstance(context).current().activeOrbitId == expected.orbitId) { "Orbit is paused" }
            check(dao.input(expected.id) == input) { "The request changed" }
            if (input.requestedUrl.isNotBlank()) check(OrbitBrowserControl.humanEpoch(expected.id) == humanEpoch) {
                context.getString(com.ai.assistance.operit.R.string.orbit_browser_reopen)
            }
            check(dao.resumeWithInput(expected.id, expected.version, note.trim(), observation, System.currentTimeMillis()))
            OrbitBrowserControl.release(expected.id)
        }
        OrbitTaskScheduler.reschedule(context)
    }
}

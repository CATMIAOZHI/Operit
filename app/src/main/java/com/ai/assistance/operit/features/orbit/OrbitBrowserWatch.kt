package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.widget.Toast
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserProfiles
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserTaskScope
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.closeSession
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.ensureOverlayOnMain
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.ensureSessionAttachedOnMain
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.setExpandedOnMain
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orbit's browser work as the user sees it: while an Orbit (or a subagent it sent) uses the
 * browser, its chat shows a live card, and the browser opens on a watch screen where the user can
 * only look until they take control. Taking control makes the AI's browser actions wait; handing it
 * back lets them go on, and the AI is told the page may have changed.
 */
internal object OrbitBrowserWatch {
    enum class Control { NONE, WAITING, USER }

    /** One executor's browser work. Several independent executors may report to [talkChatId]. */
    data class Work(
        val talkChatId: String,
        val orbitId: String,
        val orbitName: String,
        /** The chat whose agent does the browsing: the talk chat itself, a subagent or a task run. */
        val executorChatId: String,
        val createdAt: Long = System.currentTimeMillis(),
        val sessionId: String? = null,
        val url: String = "",
        val title: String = "",
        val lastActionAt: Long = System.currentTimeMillis(),
        val acting: Int = 0,
        val control: Control = Control.NONE,
        val stopping: Boolean = false,
    )

    data class Caller(val talkChatId: String, val executorChatId: String, val orbitId: String, val orbitName: String)

    /** Work counts as finished this long after its last action once its agent stopped running. */
    private const val GRACE_MS = 8_000L
    /** For an agent this cannot see running (a task run), the card stays this long after an action. */
    private const val UNKNOWN_GRACE_MS = 60_000L
    private const val PRUNE_MS = 3_000L

    private val _works = MutableStateFlow<Map<String, Work>>(emptyMap())
    val works: StateFlow<Map<String, Work>> = _works
    /** The executor whose page the open watch screen shows, until it is closed. */
    private val _watching = MutableStateFlow<String?>(null)
    val watching: StateFlow<String?> = _watching
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pruner: Job? = null

    private val app: Context get() = OperitApplication.instance.applicationContext

    fun ownerId(talkChatId: String) = OrbitBrowserControl.WATCH + talkChatId

    /** The Orbit chat the current browser tool call works for; null outside Orbit. */
    fun caller(chatId: String? = null): Caller? = runCatching {
        val chat = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId?.takeIf { it.isNotBlank() }
            ?: chatId?.takeIf { it.isNotBlank() } ?: return null
        val settings = OrbitPreferences.getInstance(app).loadedSettingsBlocking()
        val root = CollaborationCoordinator.getInstance(app).rootChatOf(chat)
        val orbit = settings.orbitForChat(chat) ?: settings.ownerForChat(chat)
            ?: settings.orbitForChat(root) ?: settings.ownerForChat(root) ?: return null
        val talk = listOf(chat, root).firstOrNull { it in orbit.talkChatIds } ?: orbit.chatId
        Caller(talk, chat, orbit.id, orbit.name.ifBlank { app.getString(R.string.nav_orbit) })
    }.getOrNull()

    /** An AI browser call for [caller] starts. */
    fun begin(caller: Caller?) {
        caller ?: return
        val now = System.currentTimeMillis()
        _works.update { all ->
            val old = all[caller.executorChatId]
            check(old?.stopping != true) { "The user stopped this browser task. Do not continue it." }
            check(old != null || all.size < BrowserTaskScope.MAX_TASKS) {
                "All 3 background browser tasks are occupied. No browser page was changed. Wait for a task to finish, then retry; do not start more browser agents."
            }
            all + (caller.executorChatId to (old?.copy(
                executorChatId = caller.executorChatId, orbitName = caller.orbitName,
                lastActionAt = now, acting = old.acting + 1,
            ) ?: Work(caller.talkChatId, caller.orbitId, caller.orbitName, caller.executorChatId, lastActionAt = now, acting = 1)))
        }
        ensurePruner()
    }

    /** An AI browser call for [caller] ended; the card follows the tab it left in front. */
    fun end(caller: Caller?) {
        caller ?: return
        val session = StandardBrowserSessionTools.lastActiveByTask[caller.executorChatId]?.let(StandardBrowserSessionTools.sessions::get)
        _works.update { all ->
            val work = all[caller.executorChatId] ?: return@update all
            all + (caller.executorChatId to work.copy(
                acting = (work.acting - 1).coerceAtLeast(0),
                lastActionAt = System.currentTimeMillis(),
                sessionId = session?.id ?: work.sessionId,
                url = session?.currentUrl ?: work.url,
                title = session?.pageTitle ?: work.title,
            ))
        }
    }

    private fun change(talkChatId: String, transform: (Work) -> Work?) {
        _works.update { all ->
            val work = all[talkChatId] ?: return@update all
            val next = transform(work)
            if (next == null) all - talkChatId else all + (talkChatId to next)
        }
    }

    /** The watched work moved to tab [sessionId] (a page opened a new one, or the user switched). */
    fun follow(talkChatId: String, sessionId: String) {
        val session = StandardBrowserSessionTools.sessions[sessionId] ?: return
        if (session.browserTaskId != talkChatId) return
        change(talkChatId) {
            if (it.sessionId == sessionId) it
            else it.copy(sessionId = sessionId, url = session.currentUrl ?: it.url, title = session.pageTitle ?: it.title)
        }
    }

    private fun ensurePruner() = synchronized(this) {
        if (pruner != null) return@synchronized
        pruner = scope.launch {
            while (true) {
                delay(PRUNE_MS)
                prune()
                synchronized(this@OrbitBrowserWatch) {
                    if (_works.value.isEmpty()) {
                        pruner = null
                        return@launch
                    }
                }
            }
        }
    }

    /** True while the agent doing [work] still runs. */
    private fun running(work: Work): Boolean {
        if (OrbitTaskRunner.isChatRunningHere(work.executorChatId)) return true
        val coordinator = CollaborationCoordinator.getInstance(app)
        if (coordinator.isAgent(work.executorChatId)) return coordinator.isAgentRunning(work.executorChatId)
        val holder = ChatRuntimeHolder.getInstance(app)
        return listOf(ChatRuntimeSlot.MAIN, ChatRuntimeSlot.FLOATING).any {
            work.executorChatId in holder.getCore(it).activeStreamingChatIds.value
        }
    }

    private fun known(work: Work): Boolean =
        work.executorChatId == work.talkChatId || CollaborationCoordinator.getInstance(app).isAgent(work.executorChatId)

    private fun prune() {
        val now = System.currentTimeMillis()
        val done = _works.value.values.filter { work ->
            val grace = if (known(work)) GRACE_MS else UNKNOWN_GRACE_MS
            !work.stopping && work.control == Control.NONE && work.acting == 0 && now - work.lastActionAt > grace &&
                !runCatching { running(work) }.getOrDefault(false)
        }
        if (done.isEmpty()) return
        // Destroy on the UI thread before freeing the slot; this bounds the number of WebViews
        // even when another agent starts immediately after a task finishes.
        scope.launch(Dispatchers.Main) {
            done.forEach { work ->
                OrbitBrowserControl.retire(work.executorChatId) {
                    if (_works.value[work.executorChatId] == work) {
                        closePages(work.executorChatId)
                        _works.update { all -> if (all[work.executorChatId] == work) all - work.executorChatId else all }
                    }
                }
            }
        }
    }

    private fun closePages(task: String) {
        BrowserProfiles.withScope(null) { BrowserTaskScope.withScope(null) {
            val browser = StandardBrowserSessionTools(app)
            StandardBrowserSessionTools.sessions.values.filter { it.browserTaskId == task }
                .forEach { browser.closeSession(it.id) }
            StandardBrowserSessionTools.lastActiveByTask.remove(task)
        } }
    }

    /** A small picture of the page [sessionId] shows, for the chat card. Main thread only. */
    fun preview(sessionId: String?, width: Int = 320): Bitmap? {
        val webView = sessionId?.let(StandardBrowserSessionTools.sessions::get)?.webView ?: return null
        if (webView.width <= 0 || webView.height <= 0) return null
        return runCatching {
            val scale = width.toFloat() / webView.width
            val height = (webView.height * scale).toInt().coerceIn(1, width * 2)
            Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).also { bitmap ->
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.scale(scale, scale)
                webView.draw(canvas)
            }
        }.getOrNull()
    }

    /** Opens the browser full screen on the work's page; it shows as the watch screen. */
    suspend fun open(context: Context, talkChatId: String) {
        val work = _works.value[talkChatId] ?: return
        if (work.stopping) return
        val sessionId = work.sessionId?.takeIf {
            StandardBrowserSessionTools.sessions[it]?.browserTaskId == work.executorChatId
        } ?: return
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.orbit_browser_overlay_required, Toast.LENGTH_SHORT).show()
            return
        }
        _works.value.values.firstOrNull { it.executorChatId != talkChatId && it.control != Control.NONE }
            ?.let { handBack(it.executorChatId) }
        _watching.value = talkChatId
        withContext(Dispatchers.Main) {
            val browser = StandardBrowserSessionTools(context.applicationContext)
            BrowserProfiles.withScope(BrowserProfiles.forOrbit(context, work.orbitId)) { BrowserTaskScope.withScope(work.executorChatId) {
                browser.ensureOverlayOnMain(context.applicationContext, initialExpanded = true)
                browser.ensureSessionAttachedOnMain(sessionId)
                browser.setExpandedOnMain(true)
            } }
        }
        // Again after opening, in case putting the old view away on the way cleared it.
        _watching.value = talkChatId
    }

    /** Leaves the watch screen; a user still in control hands the browser back first. */
    fun close(talkChatId: String) {
        if (_works.value[talkChatId]?.control != Control.NONE) handBack(talkChatId)
        unwatch()
        scope.launch(Dispatchers.Main) {
            runCatching { StandardBrowserSessionTools(app).setExpandedOnMain(false) }
        }
    }

    /** The browser was put away; it is no longer the watch screen. */
    fun unwatch() {
        _watching.value = null
    }

    /** Opening from the floating browser button pins the same task as opening its chat card. */
    fun watchSession(sessionId: String?) {
        val task = sessionId?.let(StandardBrowserSessionTools.sessions::get)?.browserTaskId ?: return
        if (task in _works.value) _watching.value = task
    }

    /** The user takes control. False when another hand-off already holds the browser. */
    fun takeOver(talkChatId: String): Boolean {
        val work = _works.value[talkChatId] ?: return false
        if (work.stopping) return false
        if (work.control != Control.NONE) return true
        if (!OrbitBrowserControl.takeOver(ownerId(talkChatId))) {
            Toast.makeText(app, R.string.orbit_browser_watch_busy, Toast.LENGTH_SHORT).show()
            return false
        }
        change(talkChatId) { it.copy(control = Control.WAITING) }
        // Input opens once the action in flight is over, so the user and the AI never touch at once.
        scope.launch {
            // Do not unlock after a timeout while native input may still be running.
            while (_works.value[talkChatId]?.control == Control.WAITING && !OrbitBrowserControl.idle(talkChatId)) delay(150)
            change(talkChatId) { if (it.control == Control.WAITING) it.copy(control = Control.USER) else it }
        }
        return true
    }

    /** The user gives the browser back; the AI looks at the page again before going on. */
    fun handBack(talkChatId: String) {
        val work = _works.value[talkChatId] ?: return
        if (work.control == Control.NONE) return
        val url = work.sessionId?.let(StandardBrowserSessionTools.sessions::get)?.currentUrl ?: work.url
        val english = LocaleUtils.usesEnglishPrompt(app)
        val busy = runCatching { running(work) }.getOrDefault(false)
        // An agent still at work hears it on its next browser call; one that already stopped gets a turn.
        OrbitBrowserControl.handBack(ownerId(talkChatId), talkChatId, if (busy) OrbitBrowserNotice.callNote(english, url) else null)
        change(talkChatId) { it.copy(control = Control.NONE, lastActionAt = System.currentTimeMillis()) }
        if (!busy) {
            val notice = OrbitBrowserNotice.handedBack(english, url)
            scope.launch {
                val coordinator = CollaborationCoordinator.getInstance(app)
                val agent = coordinator.contextSnapshot(work.executorChatId)
                if (agent == null) wake(work, notice)
                else runCatching {
                    // The page belongs to this executor; resuming the parent would give it a
                    // different tab. The existing collaboration path returns the result upstream.
                    coordinator.send(agent.rootChatId, agent.path, notice, followup = true)
                }.onFailure {
                    wake(work, notice + "\nBrowser task ${agent.path} could not resume: ${it.message}. Use followup_task for that task if its work remains.")
                }
            }
        }
    }

    /** The user stops the browser work: its subagent stops, or Orbit's own reply when it browsed itself. */
    fun stop(talkChatId: String) {
        val work = _works.value[talkChatId] ?: return
        if (work.stopping) return
        val english = LocaleUtils.usesEnglishPrompt(app)
        // Released with a note, so a call that waited for the user ends instead of acting.
        OrbitBrowserControl.cancelTask(talkChatId, OrbitBrowserNotice.stoppedCall(english))
        change(talkChatId) { it.copy(stopping = true, control = Control.NONE) }
        if (_watching.value == talkChatId) unwatch()
        scope.launch {
            runCatching { withContext(Dispatchers.Main) {
                if (_watching.value == null) StandardBrowserSessionTools(app).setExpandedOnMain(false)
            } }
            val coordinator = CollaborationCoordinator.getInstance(app)
            if (coordinator.isAgent(work.executorChatId)) {
                if (coordinator.stopAgentForUser(work.executorChatId)) wake(work, OrbitBrowserNotice.stopped(english))
            } else {
                val holder = ChatRuntimeHolder.getInstance(app)
                listOf(ChatRuntimeSlot.MAIN, ChatRuntimeSlot.FLOATING).map(holder::getCore)
                    .filter { work.executorChatId in it.activeStreamingChatIds.value }
                    .forEach { it.cancelMessage(work.executorChatId) }
            }
            while (!OrbitBrowserControl.idle(work.executorChatId)) delay(150)
            withContext(Dispatchers.Main) {
                closePages(work.executorChatId)
                change(work.executorChatId) { null }
            }
        }
    }

    private fun wake(work: Work, notice: String) {
        scope.launch {
            runCatching {
                val orbit = OrbitPreferences.getInstance(app).current().orbits.firstOrNull { it.id == work.orbitId }
                    ?: return@runCatching
                OrbitWake.tell(app, orbit, work.talkChatId, notice, idleWaitMs = 30 * 60_000L, totalWaitMs = 30 * 60_000L)
            }.onFailure { AppLogger.w("OrbitBrowserWatch", "Could not tell Orbit: ${it.message}") }
        }
    }
}

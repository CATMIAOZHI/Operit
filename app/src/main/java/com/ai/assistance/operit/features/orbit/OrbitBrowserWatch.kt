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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        /** The card shown for this page has this time (see [show]); it changes when a new card goes in. */
        val createdAt: Long = System.currentTimeMillis(),
        val sessionId: String? = null,
        val url: String = "",
        val title: String = "",
        val lastActionAt: Long = System.currentTimeMillis(),
        val acting: Int = 0,
        val control: Control = Control.NONE,
        val stopping: Boolean = false,
        /**
         * Its agent stopped. The card and the page stay, so the user can still open it and take
         * over; they go when the user closes them, after [IDLE_KEEP_MS], or when a new task needs the room.
         */
        val finished: Boolean = false,
        /** When the AI asked the user to take over this page (see [askUser]); null when not asked. */
        val askAt: Long? = null,
        /** When this browser work began; unlike [createdAt] it never changes. */
        val startedAt: Long = System.currentTimeMillis(),
    )

    data class Caller(val talkChatId: String, val executorChatId: String, val orbitId: String, val orbitName: String)

    /**
     * A browser card as it sits in the chat. It stays where it first showed; once its page is gone,
     * or the same task shows a newer card, it reads as expired. Kept across restarts.
     */
    data class Card(
        val talkChatId: String,
        val executorChatId: String,
        val createdAt: Long,
        val title: String = "",
        val url: String = "",
        val orbitName: String = "",
        /** The time of the message it first showed after ([Long.MIN_VALUE]: above the first); null until shown. */
        val anchorAt: Long? = null,
        /** [BROWSER]: the live browser card; [HANDOFF]: the AI asks the user to take over, for [reason]. */
        val kind: String = BROWSER,
        val reason: String = "",
        /** A hand-off the user took and handed back. */
        val handled: Boolean = false,
    )

    const val BROWSER = "browser"
    const val HANDOFF = "handoff"

    /** Work counts as finished this long after its last action once its agent stopped running. */
    private const val GRACE_MS = 8_000L
    /** For an agent this cannot see running (a task run), the card stays this long after an action. */
    private const val UNKNOWN_GRACE_MS = 60_000L
    private const val PRUNE_MS = 3_000L
    /** A finished task's page stays this long after its last use. */
    private const val IDLE_KEEP_MS = 30 * 60_000L
    /** Old cards beyond this many are forgotten, oldest first. */
    private const val MAX_CARDS = 200
    private const val CARDS_FILE = "orbit_browser_cards.json"

    private val _works = MutableStateFlow<Map<String, Work>>(emptyMap())
    val works: StateFlow<Map<String, Work>> = _works
    private val _cards = MutableStateFlow<List<Card>>(emptyList())
    /** Every card shown in Orbit chats, live or expired. */
    val cards: StateFlow<List<Card>> = _cards
    @Volatile private var cardsLoaded = false
    /** True once the saved cards are in memory; saving earlier would overwrite them. */
    @Volatile private var cardsRead = false
    private var cardsSave: Job? = null
    /** The executor whose page the open watch screen shows, until it is closed. */
    private val _watching = MutableStateFlow<String?>(null)
    val watching: StateFlow<String?> = _watching
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pruner: Job? = null

    private val app: Context get() = OperitApplication.instance.applicationContext

    /** Loads the saved cards once; cards added before loading finishes are kept. */
    fun loadCards() {
        if (cardsLoaded) return
        synchronized(this) {
            if (cardsLoaded) return
            cardsLoaded = true
        }
        scope.launch(Dispatchers.IO) {
            val saved = runCatching {
                val file = java.io.File(app.filesDir, CARDS_FILE)
                if (!file.exists()) return@runCatching emptyList()
                val array = org.json.JSONArray(file.readText())
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    Card(
                        o.optString("talk"), o.optString("executor"), o.optLong("at"),
                        o.optString("title"), o.optString("url"), o.optString("orbit"),
                        if (o.has("anchor")) o.optLong("anchor") else null,
                        o.optString("kind").ifBlank { BROWSER }, o.optString("reason"), o.optBoolean("handled"),
                    ).takeIf { it.talkChatId.isNotBlank() && it.executorChatId.isNotBlank() && it.createdAt > 0 }
                }
            }.getOrElse {
                AppLogger.w("OrbitBrowserWatch", "Could not read browser cards: ${it.message}")
                emptyList()
            }
            val addedMeanwhile = _cards.value.isNotEmpty()
            if (saved.isNotEmpty()) _cards.update { now ->
                val known = now.map { it.executorChatId to it.createdAt }.toSet()
                (saved.filter { (it.executorChatId to it.createdAt) !in known } + now).sortedBy { it.createdAt }.takeLast(MAX_CARDS)
            }
            cardsRead = true
            if (addedMeanwhile) saveCardsSoon()
        }
    }

    private fun saveCardsSoon() {
        synchronized(this) {
            cardsSave?.cancel()
            cardsSave = scope.launch(Dispatchers.IO) {
                delay(1_000)
                if (!cardsRead) return@launch
                runCatching {
                    val array = org.json.JSONArray()
                    _cards.value.forEach { card ->
                        array.put(org.json.JSONObject()
                            .put("talk", card.talkChatId).put("executor", card.executorChatId).put("at", card.createdAt)
                            .put("title", card.title).put("url", card.url).put("orbit", card.orbitName)
                            .put("kind", card.kind).put("reason", card.reason).put("handled", card.handled)
                            .apply { card.anchorAt?.let { put("anchor", it) } })
                    }
                    val file = java.io.File(app.filesDir, CARDS_FILE)
                    val temp = java.io.File(app.filesDir, "$CARDS_FILE.tmp")
                    temp.writeText(array.toString())
                    if (!temp.renameTo(file)) {
                        file.writeText(array.toString())
                        temp.delete()
                    }
                }.onFailure { AppLogger.w("OrbitBrowserWatch", "Could not save browser cards: ${it.message}") }
            }
        }
    }

    /** [card] first showed after the message at [anchorAt]; it stays there from now on. */
    fun place(card: Card, anchorAt: Long) {
        var changed = false
        _cards.update { all ->
            all.map {
                if (it.executorChatId == card.executorChatId && it.createdAt == card.createdAt && it.anchorAt == null) {
                    changed = true
                    it.copy(anchorAt = anchorAt)
                } else it
            }
        }
        if (changed) saveCardsSoon()
    }

    /** The live card's last page, so the card keeps showing it after it expires. */
    private fun noteCard(work: Work) {
        var changed = false
        _cards.update { all ->
            all.map { card ->
                if (card.kind == BROWSER && card.executorChatId == work.executorChatId && card.createdAt == work.createdAt &&
                    (card.title != work.title || card.url != work.url)) {
                    changed = true
                    card.copy(title = work.title, url = work.url)
                } else card
            }
        }
        if (changed) saveCardsSoon()
    }

    /**
     * The AI asks the user to take over [caller]'s page (a password, a human check): a card saying so
     * goes in the chat. Taking over and handing back works as from the browser card.
     */
    fun askUser(caller: Caller, reason: String) {
        loadCards()
        val now = System.currentTimeMillis()
        var asked: Work? = null
        _works.update { all ->
            val work = all[caller.executorChatId]
            checkNotNull(work) { "No browser page of yours is open. Open the page with the browser tools first, then ask." }
            check(!work.stopping) { "The user stopped this browser task. Do not continue it." }
            check(work.sessionId?.let(StandardBrowserSessionTools.sessions::containsKey) == true) {
                "Your browser page is no longer open. Open it again with the browser tools first, then ask."
            }
            work.copy(askAt = now, lastActionAt = now).also { asked = it }.let { all + (caller.executorChatId to it) }
        }
        val work = asked ?: return
        _cards.update { all ->
            (all + Card(work.talkChatId, work.executorChatId, now, work.title, work.url, work.orbitName,
                kind = HANDOFF, reason = reason)).takeLast(MAX_CARDS)
        }
        saveCardsSoon()
    }

    /** From a hand-off card: opens the page and gives the user control at once. */
    suspend fun openAndTakeOver(context: Context, executorChatId: String) {
        open(context, executorChatId)
        if (_watching.value == executorChatId) withContext(Dispatchers.Main) { takeOver(executorChatId) }
    }

    /** The hand-off for [work] is over: the user handed the page back. */
    private fun settleAsk(work: Work) {
        val at = work.askAt ?: return
        change(work.executorChatId) { if (it.askAt == at) it.copy(askAt = null) else it }
        _cards.update { all ->
            all.map { if (it.kind == HANDOFF && it.executorChatId == work.executorChatId && it.createdAt == at) it.copy(handled = true) else it }
        }
        saveCardsSoon()
    }

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
        loadCards()
        val now = System.currentTimeMillis()
        _works.update { all ->
            val old = all[caller.executorChatId]
            check(old?.stopping != true) { "The user stopped this browser task. Do not continue it." }
            // Finished tasks only keep their page for the user; a new task takes the room of the oldest.
            // A page waiting for the user to take over still holds its place.
            check((old != null && !old.finished) || all.values.count { !it.finished || it.askAt != null } < BrowserTaskScope.MAX_TASKS) {
                "All 3 background browser tasks are occupied. No browser page was changed. Wait for a task to finish, then retry; do not start more browser agents."
            }
            // Browsing puts no card in the chat; the AI shows one with [show]. A card already shown
            // keeps following this page, also when a stopped task is used again.
            val next = old?.copy(
                executorChatId = caller.executorChatId, orbitName = caller.orbitName,
                lastActionAt = now, acting = old.acting + 1, finished = false,
            ) ?: Work(caller.talkChatId, caller.orbitId, caller.orbitName, caller.executorChatId, createdAt = now, lastActionAt = now, acting = 1)
            all + (caller.executorChatId to next)
        }
        ensurePruner()
        if (_works.value.size > BrowserTaskScope.MAX_TASKS) prune()
    }

    /**
     * The AI shows [caller]'s page in the chat as a browser card. Until the user writes again, showing
     * it again keeps that card (it follows the page anyway); after that a new card goes in where the
     * chat is now and the old one reads as expired. Returns true when a new card went in.
     */
    suspend fun show(caller: Caller): Boolean = showLock.withLock {
        loadCards()
        val work = checkNotNull(_works.value[caller.executorChatId]) {
            "No browser page of yours is open. Open the page with the browser tools first, then show it."
        }
        check(!work.stopping) { "The user stopped this browser task. Do not continue it." }
        check(work.sessionId?.let(StandardBrowserSessionTools.sessions::containsKey) == true) {
            "Your browser page is no longer open. Open it again with the browser tools first, then show it."
        }
        val shown = _cards.value.lastOrNull {
            it.kind == BROWSER && it.executorChatId == work.executorChatId && it.talkChatId == work.talkChatId &&
                it.createdAt == work.createdAt
        }
        if (shown != null) {
            val userWrote = withContext(Dispatchers.IO) {
                com.ai.assistance.operit.data.db.AppDatabase.getDatabase(app).messageDao()
                    .existsUserMessageAfter(work.talkChatId, shown.createdAt)
            }
            if (!userWrote) return false
        }
        val now = System.currentTimeMillis()
        var next: Work? = null
        change(work.executorChatId) { current ->
            next = null
            if (current.createdAt == work.createdAt && !current.stopping) current.copy(createdAt = now).also { next = it } else current
        }
        val showing = checkNotNull(next) { "The browser task changed meanwhile. Try again." }
        _cards.update { all ->
            (all + Card(showing.talkChatId, showing.executorChatId, now, showing.title, showing.url, showing.orbitName))
                .takeLast(MAX_CARDS)
        }
        saveCardsSoon()
        true
    }

    /** Checking for a card and putting a new one in happen as one step, so two shows never both add one. */
    private val showLock = Mutex()

    /** The page of [work] is still open in the browser. */
    fun pageAlive(work: Work): Boolean =
        work.sessionId?.let(StandardBrowserSessionTools.sessions::get)?.browserTaskId == work.executorChatId

    /** An AI browser call for [caller] ended; the card follows the tab it left in front. */
    fun end(caller: Caller?) {
        caller ?: return
        val session = StandardBrowserSessionTools.lastActiveByTask[caller.executorChatId]?.let(StandardBrowserSessionTools.sessions::get)
        _works.update { all ->
            val work = all[caller.executorChatId] ?: return@update all
            all + (caller.executorChatId to work.copy(
                acting = (work.acting - 1).coerceAtLeast(0),
                lastActionAt = System.currentTimeMillis(),
                // With its last tab closed the page is gone; the work no longer points at it.
                sessionId = session?.id ?: work.sessionId?.takeIf { pageAlive(work) },
                url = session?.currentUrl ?: work.url,
                title = session?.pageTitle ?: work.title,
            ))
        }
        _works.value[caller.executorChatId]?.let(::noteCard)
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
        _works.value[talkChatId]?.let(::noteCard)
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
        val stopped = _works.value.values.filter { work ->
            val grace = if (known(work)) GRACE_MS else UNKNOWN_GRACE_MS
            !work.finished && !work.stopping && work.control == Control.NONE && work.acting == 0 && now - work.lastActionAt > grace &&
                !runCatching { running(work) }.getOrDefault(false)
        }
        stopped.forEach { work ->
            _works.update { all -> if (all[work.executorChatId] == work) all + (work.executorChatId to work.copy(finished = true)) else all }
        }
        // A stopped page rests until someone opens it or the AI uses it again; both resume it.
        if (stopped.isNotEmpty()) scope.launch(Dispatchers.Main) {
            stopped.forEach { work ->
                val now = _works.value[work.executorChatId]
                if (now?.finished == true && now.control == Control.NONE && _watching.value != work.executorChatId) {
                    StandardBrowserSessionTools.sessions.values.filter { it.browserTaskId == work.executorChatId }
                        .forEach { rest(it.webView) }
                }
            }
        }
        // A finished page goes after a long rest, or the oldest ones when new tasks need the room.
        // The one the user watches or drives stays.
        val idle = _works.value.values.filter {
            it.finished && !it.stopping && it.control == Control.NONE && it.acting == 0 && _watching.value != it.executorChatId
        }.sortedBy { it.lastActionAt }
        val extra = (_works.value.size - BrowserTaskScope.MAX_TASKS).coerceAtLeast(0)
        // A page waiting for the user to take over is not given up for a new task.
        val done = (idle.filter { it.askAt == null }.take(extra) + idle.filter { now - it.lastActionAt > IDLE_KEEP_MS }).distinct()
        if (done.isEmpty()) return
        // Destroy on the UI thread before freeing the slot; this bounds the number of WebViews
        // even when another agent starts immediately after a task finishes.
        scope.launch(Dispatchers.Main) {
            done.forEach { work ->
                OrbitBrowserControl.retire(work.executorChatId) {
                    // Checked again here: the user may have opened it since it was picked.
                    if (_works.value[work.executorChatId] == work && _watching.value != work.executorChatId) {
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
            // A stopped task's page rests again once nobody looks at it.
            val work = _works.value[talkChatId]
            if (work?.finished == true && work.control == Control.NONE && _watching.value != talkChatId) {
                StandardBrowserSessionTools.sessions.values.filter { it.browserTaskId == talkChatId }
                    .forEach { rest(it.webView) }
            }
        }
    }

    /** The browser was put away; it is no longer the watch screen. */
    fun unwatch() {
        // Looking counts as using it: however the watch screen ends, the 30 minutes start again here.
        val task = _watching.value
        _watching.value = null
        task?.let { change(it) { work -> work.copy(lastActionAt = System.currentTimeMillis()) } }
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
        // After asking, the AI ended its turn; if it is still saying its last words, the news waits
        // for that turn to end and then wakes it, so it is told exactly once.
        val asked = work.askAt != null
        // An agent still at work hears it on its next browser call; one that already stopped gets a turn.
        OrbitBrowserControl.handBack(ownerId(talkChatId), talkChatId, if (busy && !asked) OrbitBrowserNotice.callNote(english, url) else null)
        change(talkChatId) { it.copy(control = Control.NONE, lastActionAt = System.currentTimeMillis()) }
        settleAsk(work)
        if (!busy || asked) {
            val notice = OrbitBrowserNotice.handedBack(english, url)
            scope.launch {
                if (busy) {
                    val until = System.currentTimeMillis() + 10 * 60_000L
                    while (runCatching { running(work) }.getOrDefault(false) && System.currentTimeMillis() < until) delay(500)
                    // Not if the user stopped or closed the task, it was started anew, or they took over again
                    // (that hand-back tells it then).
                    val current = _works.value[work.executorChatId]
                    if (current == null || current.stopping || current.startedAt != work.startedAt ||
                        current.control != Control.NONE) return@launch
                }
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
        // Orbit already stopped here: only the page goes. Its chat may be on something else by now.
        if (work.finished) {
            closeFinished(work)
            return
        }
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

    /** Hidden and paused, so Chromium slows the page down in the background; opening or the next AI call shows it again. */
    /** True while [task]'s page should rest: Orbit stopped there and nobody watches or drives it. */
    fun resting(task: String?): Boolean {
        val work = task?.let { _works.value[it] } ?: return false
        return work.finished && !work.stopping && work.control == Control.NONE && _watching.value != task
    }

    fun rest(webView: android.webkit.WebView) {
        runCatching {
            webView.onPause()
            webView.visibility = android.view.View.GONE
        }
    }

    /** Closes a finished task's page: control goes back without telling anyone, and no reply is stopped. */
    private fun closeFinished(work: Work) {
        if (work.control != Control.NONE) OrbitBrowserControl.handBack(ownerId(work.executorChatId), work.executorChatId, null)
        change(work.executorChatId) { it.copy(stopping = true, control = Control.NONE) }
        if (_watching.value == work.executorChatId) unwatch()
        scope.launch {
            // Same end as stop(): wait out any call in flight, then close on the UI thread.
            while (!OrbitBrowserControl.idle(work.executorChatId)) delay(150)
            withContext(Dispatchers.Main) {
                if (_watching.value == null) runCatching { StandardBrowserSessionTools(app).setExpandedOnMain(false) }
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

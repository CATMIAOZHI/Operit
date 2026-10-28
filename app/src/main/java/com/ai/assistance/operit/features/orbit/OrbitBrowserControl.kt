package com.ai.assistance.operit.features.orbit

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.BrowserTaskScope

/**
 * Guards AI browser calls while a user handles the page: a task's hand-off page, or the browser the
 * user took from Orbit's watch screen. UI callbacks stay usable.
 */
internal object OrbitBrowserControl {
    /** How long an AI call waits for another AI's browser action before giving up. */
    private const val QUEUE_WAIT_SECONDS = 90L
    /** How long an AI call waits while the user drives the browser from Orbit's watch screen. */
    private const val USER_WAIT_MS = 30 * 60_000L
    /** How long the "the user changed the page" note waits for the AI's next browser call. */
    private const val NOTICE_MS = 10 * 60_000L
    /** Owners that start with this are a user who took the browser from Orbit's watch screen. */
    const val WATCH = "watch:"
    private var owner: String? = null
    private var epoch = 0L
    private var calls = 0
    private var tokenSeed = 0L
    private data class Call(val task: String?, val epoch: Long)
    private val activeCalls = mutableMapOf<Long, Call>()
    private val taskEpochs = mutableMapOf<String, Long>()

    /** Told once to the next AI call for the same Orbit chat after the user hands the browser back. */
    private class Notice(val talkChatId: String, val text: String, val until: Long)
    private val notices = mutableMapOf<String, Notice>()
    private val invocation = ThreadLocal<Long?>()
    /** Actions in one task are serialized; independent pages can load and act concurrently. */
    private class TaskQueue(val lock: ReentrantLock = ReentrantLock(true), var users: Int = 0)
    private val aiQueues = mutableMapOf<String?, TaskQueue>()
    @Synchronized private fun retainQueue(task: String?): TaskQueue =
        aiQueues.getOrPut(task) { TaskQueue() }.also { it.users++ }
    @Synchronized private fun releaseQueue(task: String?, queue: TaskQueue) {
        // Waiting callers also retain the queue, so a replacement lock cannot overtake them.
        if (--queue.users == 0) aiQueues.remove(task, queue)
    }
    @Synchronized fun isOwner(taskId: String): Boolean = owner == taskId
    @Synchronized fun humanEpoch(taskId: String): Long? = epoch.takeIf { owner == taskId }

    @Synchronized fun acquire(taskId: String) {
        check(owner == null || owner == taskId) { "Another task is waiting for browser input" }
        check(calls == 0) { "The browser is finishing an AI operation. Try opening it again shortly." }
        owner = taskId
        epoch++
    }
    @Synchronized fun release(taskId: String) {
        if (owner == taskId) { owner = null; epoch++ }
    }

    /**
     * The user takes the browser from the watch screen. Unlike [acquire] it does not wait for the
     * AI's current action: new actions wait from now on, and the one in flight is told control changed.
     */
    @Synchronized fun takeOver(id: String): Boolean {
        if (owner != null && owner != id) return false
        if (owner != id) {
            owner = id
            val task = id.removePrefix(WATCH)
            taskEpochs[task] = (taskEpochs[task] ?: 0) + 1
        }
        return true
    }

    /** No AI browser action is running. */
    @Synchronized fun idle(task: String? = null): Boolean =
        if (task == null) calls == 0 else activeCalls.values.none { it.task == task }

    /**
     * Ends [takeOver]. [note] reaches the next AI browser call made for [talkChatId] in the next few
     * minutes instead of its result, so the AI looks at the page again before acting on it.
     */
    @Synchronized fun handBack(id: String, talkChatId: String, note: String?) {
        if (owner != id) return
        owner = null
        taskEpochs[talkChatId] = (taskEpochs[talkChatId] ?: 0) + 1
        note?.let { notices[talkChatId] = Notice(talkChatId, it, System.currentTimeMillis() + NOTICE_MS) }
        (this as Object).notifyAll()
    }

    @Synchronized fun cancelTask(task: String, note: String) {
        if (owner == WATCH + task) owner = null
        taskEpochs[task] = (taskEpochs[task] ?: 0) + 1
        notices[task] = Notice(task, note, System.currentTimeMillis() + NOTICE_MS)
        (this as Object).notifyAll()
    }

    /** Retire a finished task only when no action or queued callback can use its pages. */
    fun retire(task: String, block: () -> Unit) {
        val queue = retainQueue(task)
        try {
            if (!queue.lock.tryLock()) return
            try {
                if (idle(task)) {
                    block()
                    synchronized(this) { taskEpochs.remove(task) }
                }
            } finally { queue.lock.unlock() }
        } finally { releaseQueue(task, queue) }
    }

    @Synchronized private fun watching(task: String?): Boolean =
        owner?.startsWith(WATCH) == true && (task == null || owner == WATCH + task)

    /** While the user drives the browser from the watch screen, AI calls wait for it back. */
    @Synchronized private fun awaitUser(task: String?) {
        val until = System.currentTimeMillis() + USER_WAIT_MS
        while (watching(task)) {
            val left = until - System.currentTimeMillis()
            check(left > 0) {
                "The user is still using the browser. Stop browsing for now and tell them you will continue when they hand it back."
            }
            (this as Object).wait(left)
        }
    }

    @Synchronized fun beginAi(talkChatId: String? = null): Long {
        check(owner == null || (owner!!.startsWith(WATCH) && !watching(talkChatId))) {
            "The browser is being controlled by the user. Try again after they finish."
        }
        notices.entries.removeAll { System.currentTimeMillis() > it.value.until }
        notices.remove(talkChatId)?.let {
            throw IllegalStateException(it.text)
        }
        calls++
        val token = ++tokenSeed
        activeCalls[token] = Call(talkChatId, epoch + (talkChatId?.let(taskEpochs::get) ?: 0))
        return token
    }
    @Synchronized private fun endAi(token: Long) { activeCalls.remove(token); calls-- }
    @Synchronized fun check(token: Long?) {
        if (token == null) return
        val call = activeCalls[token]
        check(call != null && !watching(call.task) &&
            (owner == null || owner!!.startsWith(WATCH)) &&
            call.epoch == epoch + (call.task?.let(taskEpochs::get) ?: 0)) {
            "Browser control changed; this action was cancelled"
        }
    }
    fun token(): Long? = invocation.get()
    /** [callerChatId] is the chat the call comes from when the tool runtime does not carry it (package calls). */
    fun <T> aiCall(callerChatId: String? = null, block: () -> T): T {
        val caller = OrbitBrowserWatch.caller(callerChatId)
        return callForTask(caller?.executorChatId, { OrbitBrowserWatch.begin(caller) },
            { OrbitBrowserWatch.end(caller) }, block)
    }

    /** Kept separate from caller discovery so isolation can be tested without Android. */
    internal fun <T> callForTask(task: String?, begin: () -> Unit = {}, end: () -> Unit = {}, block: () -> T): T {
        val queue = retainQueue(task)
        val aiQueue = queue.lock
        try {
            while (true) {
                awaitUser(task)
                if (!aiQueue.tryLock(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    throw IllegalStateException(
                        "The browser is busy with another action in this task. Try again in a moment."
                    )
                }
                // The user took the browser while this call queued: give the queue back and wait for them.
                if (!watching(task)) break
                aiQueue.unlock()
            }
            try {
                val old = invocation.get()
                val token = beginAi(task)
                invocation.set(token)
                return try {
                    BrowserTaskScope.withScope(task) {
                        begin()
                        try { block().also { check(token) } } finally { end() }
                    }
                } finally {
                    invocation.set(old)
                    endAi(token)
                }
            } finally { aiQueue.unlock() }
        } finally { releaseQueue(task, queue) }
    }
}

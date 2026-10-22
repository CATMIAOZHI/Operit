package com.ai.assistance.operit.features.orbit

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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

    /** Told once to the next AI call for the same Orbit chat after the user hands the browser back. */
    private class Notice(val talkChatId: String, val text: String, val until: Long)
    private var notice: Notice? = null
    private val invocation = ThreadLocal<Long?>()
    /**
     * One AI browser action at a time. The browser has one front tab, so two agents acting at once
     * would switch pages under each other. Fair, so callers run in the order they arrived.
     */
    private val aiQueue = ReentrantLock(true)
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
        if (owner != id) { owner = id; epoch++ }
        return true
    }

    /** No AI browser action is running. */
    @Synchronized fun idle(): Boolean = calls == 0

    /**
     * Ends [takeOver]. [note] reaches the next AI browser call made for [talkChatId] in the next few
     * minutes instead of its result, so the AI looks at the page again before acting on it.
     */
    @Synchronized fun handBack(id: String, talkChatId: String, note: String?) {
        if (owner != id) return
        owner = null
        epoch++
        notice = note?.let { Notice(talkChatId, it, System.currentTimeMillis() + NOTICE_MS) }
        (this as Object).notifyAll()
    }

    @Synchronized private fun watching(): Boolean = owner?.startsWith(WATCH) == true

    /** While the user drives the browser from the watch screen, AI calls wait for it back. */
    @Synchronized private fun awaitUser() {
        val until = System.currentTimeMillis() + USER_WAIT_MS
        while (owner?.startsWith(WATCH) == true) {
            val left = until - System.currentTimeMillis()
            check(left > 0) {
                "The user is still using the browser. Stop browsing for now and tell them you will continue when they hand it back."
            }
            (this as Object).wait(left)
        }
    }

    @Synchronized fun beginAi(talkChatId: String? = null): Long {
        check(owner == null) { "The browser is being controlled by the user. Try again after they finish." }
        notice?.let {
            if (System.currentTimeMillis() > it.until) {
                notice = null
            } else if (it.talkChatId == talkChatId) {
                notice = null
                throw IllegalStateException(it.text)
            }
        }
        calls++
        return epoch
    }
    @Synchronized private fun endAi() { calls-- }
    @Synchronized fun check(token: Long?) {
        if (token != null) check(owner == null && token == epoch) { "Browser control changed; this action was cancelled" }
    }
    fun token(): Long? = invocation.get()
    /** [callerChatId] is the chat the call comes from when the tool runtime does not carry it (package calls). */
    fun <T> aiCall(callerChatId: String? = null, block: () -> T): T {
        val caller = OrbitBrowserWatch.caller(callerChatId)
        while (true) {
            awaitUser()
            if (!aiQueue.tryLock(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException(
                    "The browser is busy with another agent's action. Try again in a moment."
                )
            }
            // The user took the browser while this call queued: give the queue back and wait for them.
            if (!watching()) break
            aiQueue.unlock()
        }
        try {
            val old = invocation.get()
            val token = beginAi(caller?.talkChatId)
            invocation.set(token)
            OrbitBrowserWatch.begin(caller)
            return try { block().also { check(token) } } finally {
                invocation.set(old)
                endAi()
                OrbitBrowserWatch.end(caller)
            }
        } finally {
            aiQueue.unlock()
        }
    }
}

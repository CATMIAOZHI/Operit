package com.ai.assistance.operit.features.orbit

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Only guards AI browser calls while a user is handling a task's page. UI callbacks stay usable. */
internal object OrbitBrowserControl {
    /** How long an AI call waits for another AI's browser action before giving up. */
    private const val QUEUE_WAIT_SECONDS = 90L
    private var owner: String? = null
    private var epoch = 0L
    private var calls = 0
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
    @Synchronized fun beginAi(): Long {
        check(owner == null) { "The browser is being controlled by the user. Try again after they finish." }
        calls++
        return epoch
    }
    @Synchronized private fun endAi() { calls-- }
    @Synchronized fun check(token: Long?) {
        if (token != null) check(owner == null && token == epoch) { "Browser control changed; this action was cancelled" }
    }
    fun token(): Long? = invocation.get()
    fun <T> aiCall(block: () -> T): T {
        if (!aiQueue.tryLock(QUEUE_WAIT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException(
                "The browser is busy with another agent's action. Try again in a moment."
            )
        }
        try {
            val old = invocation.get()
            val token = beginAi()
            invocation.set(token)
            return try { block().also { check(token) } } finally { invocation.set(old); endAi() }
        } finally {
            aiQueue.unlock()
        }
    }
}

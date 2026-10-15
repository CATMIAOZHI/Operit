package com.ai.assistance.operit.features.orbit

/** Only guards AI browser calls while a user is handling a task's page. UI callbacks stay usable. */
internal object OrbitBrowserControl {
    private var owner: String? = null
    private var epoch = 0L
    private var calls = 0
    private val invocation = ThreadLocal<Long?>()
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
        val old = invocation.get()
        val token = beginAi()
        invocation.set(token)
        return try { block().also { check(token) } } finally { invocation.set(old); endAi() }
    }
}

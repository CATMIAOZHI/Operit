package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

/** A browser task owns its tabs independently of the login profile they share. */
internal object BrowserTaskScope {
    const val MAX_TASKS = 3
    const val MAX_TABS_PER_TASK = 2
    private val scope = ThreadLocal<String?>()
    fun current(): String? = scope.get()

    fun <T> withScope(task: String?, block: () -> T): T {
        val old = scope.get()
        scope.set(task)
        return try { block() } finally { scope.set(old) }
    }

    fun canAccess(profileScope: String?, taskScope: String?, profile: String, task: String?): Boolean =
        (profileScope == null || profileScope == profile) &&
            (if (taskScope != null) taskScope == task else profileScope == null || task == null)
}

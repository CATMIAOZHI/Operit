package com.ai.assistance.operit.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the resumed Activity so non-UI code can pick a dialog host without pulling in
 * `lifecycle-process`. Reset on pause, so a backgrounded screen never claims a dialog.
 * Also counts started windows from the very start of the process, before any other tracker.
 */
object AppActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var current = WeakReference<Activity>(null)
    // Touched only on the main thread, where the callbacks run.
    private var startedCount = 0

    /** Run on the main thread when the last started window stops. */
    var onBackground: (() -> Unit)? = null

    /** Activity currently resumed, or null while no Operit screen is on top. */
    val resumedActivity: Activity?
        get() = current.get()

    /** Whether any window of this process is started. Read on the main thread. */
    fun hasStartedActivity(): Boolean = startedCount > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        current = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) current = WeakReference(null)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
    }

    override fun onActivityStopped(activity: Activity) {
        if (startedCount == 0) return
        startedCount -= 1
        if (startedCount == 0) onBackground?.let { runCatching { it() } }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

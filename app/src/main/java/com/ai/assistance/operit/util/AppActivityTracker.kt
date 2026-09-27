package com.ai.assistance.operit.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Tracks the resumed Activity so non-UI code can pick a dialog host without pulling in
 * `lifecycle-process`. Reset on pause, so a backgrounded screen never claims a dialog.
 */
object AppActivityTracker : Application.ActivityLifecycleCallbacks {
    @Volatile private var current = WeakReference<Activity>(null)

    /** Activity currently resumed, or null while no Operit screen is on top. */
    val resumedActivity: Activity?
        get() = current.get()

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

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

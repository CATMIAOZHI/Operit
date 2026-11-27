package com.ai.assistance.operit.core.application

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ai.assistance.operit.util.AppActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Which name and icon the app shows on the home screen. Only Orbit is offered now; the Operit Ry
 * entry stays declared so a phone that picked it earlier can be turned back to Orbit.
 */
enum class AppLauncherIdentity(internal val aliasClass: String) {
    ORBIT("com.ai.assistance.operit.launcher.OrbitLauncher"),
    OPERIT("com.ai.assistance.operit.launcher.OperitLauncher");

    companion object {
        // Outlives any screen, so a switch back to Orbit finishes once begun.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Switches one at a time: two interleaved ones could leave both entries off.
        private val lock = Mutex()

        private val _known = kotlinx.coroutines.flow.MutableStateFlow<AppLauncherIdentity?>(null)

        /**
         * The entry as last read at start-up or set by a switch, so notifications need not ask the
         * package manager; the ongoing notification redraws when it changes.
         */
        val known: kotlinx.coroutines.flow.StateFlow<AppLauncherIdentity?> = _known

        /**
         * The entry the launcher shows now. Read from the package manager itself, so it is always
         * what the home screen has; it is a binder call, so call it off the main thread.
         */
        fun current(context: Context): AppLauncherIdentity {
            val state = context.packageManager.getComponentEnabledSetting(component(context, OPERIT))
            // The manifest turns the Operit Ry entry off until the user picks it.
            return if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) OPERIT else ORBIT
        }

        /**
         * Reads the entry once at start-up, off the main thread and in turn with switches, so
         * notifications drawn later already know it.
         */
        fun load(context: Context) {
            val app = context.applicationContext
            scope.launch {
                val read = lock.withLock {
                    if (_known.value != null) return@withLock false
                    runCatching { current(app) }.onSuccess { _known.value = it }.isSuccess
                }
                // A notification drawn before this read (the pet or a floating window alone) follows.
                if (read) runCatching { CompanionNotification.refresh(app) }
            }
        }

        /** Until [load] has read it, the default entry: notifications never wait on the package manager. */
        private fun shown(): AppLauncherIdentity = _known.value ?: ORBIT

        /** The status-bar icon for the app's notifications, matching the home-screen entry. */
        fun notificationIcon(context: Context): Int =
            if (shown() == OPERIT) com.ai.assistance.operit.R.drawable.ic_launcher_simple_foreground
            else com.ai.assistance.operit.R.drawable.ic_stat_orbit

        /** The app's name as the home screen shows it, for notifications. */
        fun displayName(context: Context): String =
            context.getString(
                if (shown() == OPERIT) com.ai.assistance.operit.R.string.app_name
                else com.ai.assistance.operit.R.string.orbit_launcher_name
            )

        /**
         * Turns a home screen that still shows Operit Ry back to Orbit. Called when the app goes to
         * the background and, after [delayMs], at start-up. Turning off the old entry would close a
         * window opened from it, so both changes run on the main thread, in turn with the window
         * callbacks, and only while no window is started; otherwise they wait for the next time the
         * app goes to the background. Orbit is turned on first, so the app never has no icon.
         */
        fun restoreOrbit(context: Context, delayMs: Long = 0L) {
            if (_known.value == ORBIT) return
            val app = context.applicationContext
            scope.launch {
                if (delayMs > 0) delay(delayMs)
                val switched = lock.withLock {
                    // An earlier read or switch may have settled it while this one waited.
                    if (_known.value == ORBIT) return@withLock false
                    val now = runCatching { current(app) }.getOrNull() ?: return@withLock false
                    if (now == ORBIT) {
                        _known.value = ORBIT
                        return@withLock false
                    }
                    // Two binder calls, once per phone that picked Operit Ry.
                    val changed = withContext(Dispatchers.Main) {
                        !AppActivityTracker.hasStartedActivity() &&
                            runCatching {
                                setEnabled(app, ORBIT, true)
                                setEnabled(app, OPERIT, false)
                            }.isSuccess
                    }
                    if (!changed) return@withLock false
                    val after = runCatching { current(app) }.getOrNull() ?: return@withLock false
                    _known.value = after
                    after == ORBIT
                }
                // The shared notification follows the new name and icon, once the switch is confirmed.
                if (switched) runCatching { CompanionNotification.refresh(app) }
            }
        }

        private fun setEnabled(context: Context, identity: AppLauncherIdentity, enabled: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context, identity),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        private fun component(context: Context, identity: AppLauncherIdentity) =
            ComponentName(context.packageName, identity.aliasClass)
    }
}

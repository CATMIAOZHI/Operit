package com.ai.assistance.operit.core.application

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which name and icon the app shows on the home screen. Orbit is the default. */
enum class AppLauncherIdentity(internal val aliasClass: String) {
    ORBIT("com.ai.assistance.operit.launcher.OrbitLauncher"),
    OPERIT("com.ai.assistance.operit.launcher.OperitLauncher");

    companion object {
        // Outlives the settings page, so a confirmed switch finishes even if the page closes.
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
         * Shows [identity] on the home screen and hides the other entry, off the main thread, then
         * calls [onDone] on the main thread with the entry now shown. The new one is turned on
         * first, so the app never has no icon. Launchers may take a few seconds to redraw.
         */
        fun switchTo(context: Context, identity: AppLauncherIdentity, onDone: (AppLauncherIdentity) -> Unit) {
            val app = context.applicationContext
            scope.launch {
                val now = lock.withLock {
                    runCatching { set(app, identity) }
                    current(app).also { _known.value = it }
                }
                // Also the shared notification when only the pet or a floating window holds it.
                runCatching { CompanionNotification.refresh(app) }
                android.os.Handler(android.os.Looper.getMainLooper()).post { onDone(now) }
            }
        }

        private fun set(context: Context, identity: AppLauncherIdentity) {
            val pm = context.packageManager
            val other = if (identity == ORBIT) OPERIT else ORBIT
            pm.setComponentEnabledSetting(
                component(context, identity),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            pm.setComponentEnabledSetting(
                component(context, other),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        private fun component(context: Context, identity: AppLauncherIdentity) =
            ComponentName(context.packageName, identity.aliasClass)
    }
}

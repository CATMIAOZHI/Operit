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
         * Shows [identity] on the home screen and hides the other entry, off the main thread, then
         * calls [onDone] on the main thread with the entry now shown. The new one is turned on
         * first, so the app never has no icon. Launchers may take a few seconds to redraw.
         */
        fun switchTo(context: Context, identity: AppLauncherIdentity, onDone: (AppLauncherIdentity) -> Unit) {
            val app = context.applicationContext
            scope.launch {
                val now = lock.withLock {
                    runCatching { set(app, identity) }
                    current(app)
                }
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

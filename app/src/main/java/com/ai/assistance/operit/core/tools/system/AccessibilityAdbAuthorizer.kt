package com.ai.assistance.operit.core.tools.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.system.shell.DebuggerShellExecutor
import com.ai.assistance.operit.data.repository.UIHierarchyManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Explicit user action only; never enable a service while merely checking permissions. */
object AccessibilityAdbAuthorizer {
    private val mutex = Mutex()

    suspend fun enable(context: Context): String = mutex.withLock {
        withTimeout(20_000) {
            val shell = DebuggerShellExecutor(context)
            check(shell.isAvailable() && shell.hasPermission().granted) {
                context.getString(R.string.accessibility_adb_unavailable)
            }
            val services = context.packageManager.queryIntentServices(
                Intent(AccessibilityService.SERVICE_INTERFACE)
                    .setPackage(UIHierarchyManager.PROVIDER_PACKAGE_NAME), 0
            ).filter { it.serviceInfo.permission == android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE }
            check(services.size == 1) { context.getString(R.string.accessibility_adb_service_missing) }
            val info = services.single().serviceInfo
            val component = ComponentName(info.packageName, info.name).flattenToString()
            check(Regex("[A-Za-z0-9_.$/]+").matches(component))
            // Read and append in the shell, preserving all existing accessibility services.
            val command = """
                component='$component'
                current=${'$'}(settings --user current get secure enabled_accessibility_services) || exit 1
                [ "${'$'}current" = null ] && current=''
                case ":${'$'}current:" in
                  *":${'$'}component:"*) ;;
                  *) settings --user current put secure enabled_accessibility_services "${'$'}{current:+${'$'}current:}${'$'}component" || exit 1 ;;
                esac
                settings --user current put secure accessibility_enabled 1
            """.trimIndent()
            val outcome = shell.executeCommand(command, ShellIdentity.SHELL)
            check(outcome.success) {
                context.getString(R.string.accessibility_adb_failed, outcome.stderr.take(300))
            }
            repeat(10) {
                if (UIHierarchyManager.isAccessibilityServiceEnabled(context)) {
                    return@withTimeout context.getString(R.string.accessibility_adb_success)
                }
                delay(500)
            }
            error(context.getString(R.string.accessibility_adb_not_active))
        }
    }
}

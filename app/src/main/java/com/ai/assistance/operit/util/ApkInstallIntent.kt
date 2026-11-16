package com.ai.assistance.operit.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Operit opens any file, so an APK view intent would otherwise ask the user to pick between the
 * system installer and Operit itself. This points it at the phone's own (system) installer, never
 * another app that opens files, such as another Operit build; without one it is left as it was.
 */
object ApkInstallIntent {
    fun toInstaller(context: Context, intent: Intent): Intent {
        val own = context.packageName
        val installer = runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo }
                .filter { it.packageName != own }
                .firstOrNull { (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 }
                ?.packageName
        }.getOrNull()
        if (installer != null) intent.setPackage(installer)
        return intent
    }
}

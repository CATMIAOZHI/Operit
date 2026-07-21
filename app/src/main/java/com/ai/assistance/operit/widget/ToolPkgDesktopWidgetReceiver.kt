package com.ai.assistance.operit.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.ai.assistance.operit.core.application.OperitApplication

class ToolPkgDesktopWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = ToolPkgDesktopGlanceWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (!OperitApplication.isMainDataAccessAllowed(context)) return
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { appWidgetId ->
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                ToolPkgDesktopWidgetHost.clearSelection(context, appWidgetId)
            }
        }
    }
}

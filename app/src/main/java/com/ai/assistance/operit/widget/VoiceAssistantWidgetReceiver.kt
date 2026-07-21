package com.ai.assistance.operit.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.ai.assistance.operit.core.application.OperitApplication

/**
 * Widget Receiver for Voice Assistant Widget
 * 
 * This receiver handles widget lifecycle events and binds to the Glance widget.
 */
class VoiceAssistantWidgetReceiver : GlanceAppWidgetReceiver() {
    
    override val glanceAppWidget: GlanceAppWidget
        get() = VoiceAssistantGlanceWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (!OperitApplication.isMainDataAccessAllowed(context)) return
        super.onReceive(context, intent)
    }
}


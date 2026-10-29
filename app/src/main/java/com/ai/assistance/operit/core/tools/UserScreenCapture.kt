package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.core.tools.defaultTool.ToolGetter
import com.ai.assistance.operit.core.tools.defaultTool.standard.StandardUITools
import com.ai.assistance.operit.core.tools.system.AndroidPermissionLevel
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.preferences.androidPermissionPreferences
import com.ai.assistance.operit.services.FloatingChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A screenshot the user asked for (region capture, "screen content"). It is the app acting for the
 * user, not a model's tool call, so it goes to the screen tools directly: the retired model phone
 * tools must not block it. The floating window steps aside while the screen is taken.
 */
object UserScreenCapture {
    private val cached = HashMap<AndroidPermissionLevel, StandardUITools>()

    /**
     * The screen tools for the current permission level, kept between uses: a backend holds its
     * screen capture session, so a new one each time would open another one every capture.
     */
    fun uiTools(context: Context): StandardUITools = synchronized(this) {
        val level = androidPermissionPreferences.getPreferredPermissionLevel() ?: AndroidPermissionLevel.STANDARD
        // One per level, so switching levels back and forth reuses each one's capture session.
        cached.getOrPut(level) { ToolGetter.getUITools(context.applicationContext) }
    }

    /** The saved screenshot's path, or null when the screen could not be taken. */
    suspend fun capture(context: Context): String? = withContext(Dispatchers.IO) {
        val floating = FloatingChatService.getInstance()
        try {
            floating?.setFloatingWindowVisible(false)
            floating?.setStatusIndicatorVisible(false)
            delay(200)
            uiTools(context).captureScreenshot(AITool(name = "capture_screenshot"))
                .first?.trim()?.takeIf(String::isNotBlank)
        } finally {
            // Even when the screen asking for it closes meanwhile, the window comes back.
            withContext(NonCancellable) {
                floating?.setFloatingWindowVisible(true)
                floating?.setStatusIndicatorVisible(false)
            }
        }
    }
}

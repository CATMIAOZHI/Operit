package com.ai.assistance.operit.core.tools.phone

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.common.displays.UIAutomationProgressOverlay

/** Main-thread only; reuse the card without sharing legacy-agent lifecycle or touch shields. */
internal class PhoneControlOverlay(private val context: Context, private val onStop: () -> Unit) {
    private val panel = UIAutomationProgressOverlay.create(context)
    fun show() = panel.show(
        totalSteps = 0,
        initialStatus = context.getString(R.string.phone_control_observing),
        onCancel = onStop
    )
    fun updateStatus(status: String) = panel.updateProgress(1, 0, status)
    suspend fun setVisible(visible: Boolean) = panel.setOverlayVisible(visible)
    fun containsStop(x: Int, y: Int): Boolean = panel.containsPoint(x, y)
    fun avoidGesture(minY: Int, maxY: Int, height: Int) = panel.avoidGesture(minY, maxY, height)
    fun intersectsGesture(minY: Int, maxY: Int) = panel.intersectsGesture(minY, maxY)
    fun close() = panel.hide()
}

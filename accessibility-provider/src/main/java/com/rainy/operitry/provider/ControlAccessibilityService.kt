package com.rainy.operitry.provider

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ControlAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var current: ControlAccessibilityService? = null
            private set
    }
    @Volatile var activityName = ""
        private set

    override fun onServiceConnected() { current = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            activityName = "${event.packageName}/${event.className}"
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }
}

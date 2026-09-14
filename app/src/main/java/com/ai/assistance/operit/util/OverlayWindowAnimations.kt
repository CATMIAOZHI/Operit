package com.ai.assistance.operit.util

import android.view.WindowManager

/** Overlay content already positions itself; WM interpolation would move it a second time. */
internal fun WindowManager.LayoutParams.disableMoveAnimation() {
    try {
        val field = javaClass.getField("privateFlags")
        field.setInt(this, field.getInt(this) or 0x00000040) // PRIVATE_FLAG_NO_MOVE_ANIMATION
    } catch (error: Exception) {
        AppLogger.e("OverlayWindowAnimations", "Unable to disable system move animation", error)
    }
}

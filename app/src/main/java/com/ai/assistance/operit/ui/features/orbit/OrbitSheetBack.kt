package com.ai.assistance.operit.ui.features.orbit

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle

/**
 * Lets a bottom sheet's own back gesture (the sheet shrinking under the finger) work inside Orbit's
 * pages. Orbit's page host hands the activity's back dispatcher down explicitly; a sheet opens in
 * its own dialog window but inherits that dispatcher, so its gesture handler listens where the
 * gestures never arrive. The sheet gets a stand-in dispatcher instead, and [forward], placed in the
 * sheet's content, passes the dialog window's gestures on to it.
 */
internal class OrbitSheetBack(private val lifecycleOf: () -> Lifecycle) : OnBackPressedDispatcherOwner {
    private val dispatcher = OnBackPressedDispatcher()
    override val onBackPressedDispatcher: OnBackPressedDispatcher get() = dispatcher
    override val lifecycle: Lifecycle get() = lifecycleOf()

    /** Wraps the whole sheet call, so the sheet's gesture handler registers here. */
    @Composable
    fun Provide(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides this, content = content)
    }

    /** Goes inside the sheet's content: hands the dialog window's back gestures to the sheet. */
    @Composable
    fun forward() {
        val window = LocalView.current.findViewTreeOnBackPressedDispatcherOwner() ?: return
        DisposableEffect(window) {
            val callback = object : OnBackPressedCallback(true) {
                private var started = false
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    started = dispatcher.hasEnabledCallbacks()
                    if (started) dispatcher.dispatchOnBackStarted(backEvent)
                }
                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    if (started) dispatcher.dispatchOnBackProgressed(backEvent)
                }
                override fun handleOnBackCancelled() {
                    if (started) dispatcher.dispatchOnBackCancelled()
                    started = false
                }
                override fun handleOnBackPressed() {
                    started = false
                    if (dispatcher.hasEnabledCallbacks()) {
                        dispatcher.onBackPressed()
                    } else {
                        // Nothing in the sheet wants it: let the dialog close the usual way.
                        isEnabled = false
                        window.onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
            window.onBackPressedDispatcher.addCallback(callback)
            onDispose { callback.remove() }
        }
    }
}

@Composable
internal fun rememberOrbitSheetBack(): OrbitSheetBack {
    val lifecycleOwner = LocalLifecycleOwner.current
    return remember(lifecycleOwner) { OrbitSheetBack { lifecycleOwner.lifecycle } }
}

package com.ai.assistance.operit.features.orbit

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ai.assistance.operit.data.preferences.OrbitSettings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orbit's app lock: when it is on, Orbit opens only after the system check (fingerprint, face or
 * the screen lock). Once unlocked it stays open until the app has been away for [RELOCK_AFTER_MS],
 * so a file picker, a permission screen or the check itself never locks it again.
 */
object OrbitAppLock : Application.ActivityLifecycleCallbacks {
    /** Time in the background after which Orbit asks again. */
    const val RELOCK_AFTER_MS = 60_000L

    private val _unlocked = MutableStateFlow(false)
    /** Whether the user has passed the check since the app last went away for long. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /** Whether the check for the current lock already showed by itself; a cancel then waits for a tap. */
    @Volatile var autoPrompted: Boolean = false

    private var startedActivities = 0
    private var backgroundSince = 0L
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val relock = Runnable { if (startedActivities == 0) lock() }
    private var registered = false

    /** Handler delays pause in deep sleep; service-only entry points must also check elapsed time. */
    fun refreshBackgroundLock() {
        if (startedActivities == 0 && backgroundSince != 0L &&
            SystemClock.elapsedRealtime() - backgroundSince >= RELOCK_AFTER_MS
        ) lock()
    }

    /** Floating windows have no Activity lifecycle of their own. */
    fun isLocked(context: Context, settings: OrbitSettings?, chatId: String?): Boolean {
        refreshBackgroundLock()
        if (settings == null) return true
        return settings.appLock && settings.ownerForChat(chatId) != null &&
            !_unlocked.value && deviceSecure(context)
    }

    fun register(application: Application) {
        if (registered) return
        registered = true
        application.registerActivityLifecycleCallbacks(this)
        // An already open overlay can survive screen-off without starting an Activity again.
        ContextCompat.registerReceiver(
            application,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refreshBackgroundLock()
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun markUnlocked() {
        _unlocked.value = true
    }

    private fun lock() {
        _unlocked.value = false
        autoPrompted = false
    }

    override fun onActivityStarted(activity: Activity) {
        handler.removeCallbacks(relock)
        if (startedActivities == 0 && backgroundSince != 0L &&
            SystemClock.elapsedRealtime() - backgroundSince > RELOCK_AFTER_MS
        ) {
            lock()
        }
        startedActivities++
        backgroundSince = 0L
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            backgroundSince = SystemClock.elapsedRealtime()
            handler.removeCallbacks(relock)
            handler.postDelayed(relock, RELOCK_AFTER_MS)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    /** Whether the phone has a screen lock; without one there is nothing to check against. */
    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /**
     * Shows the system check on Android 10 and later (biometrics or the screen lock, as the user
     * picks). Returns false when it cannot be shown there, so the caller uses [legacyIntent].
     */
    fun showSystemPrompt(
        activity: Activity,
        title: String,
        subtitle: String,
        onResult: (Boolean) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)
            // A wrong finger keeps the prompt open; only an error or a cancel ends it.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
        }
        return runCatching {
            builder.build().authenticate(CancellationSignal(), activity.mainExecutor, callback)
        }.isSuccess
    }

    /** The screen-lock check of older Android versions; null when the phone has no screen lock. */
    @Suppress("DEPRECATION")
    fun legacyIntent(context: Context, title: String, subtitle: String): Intent? =
        context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, subtitle)
}

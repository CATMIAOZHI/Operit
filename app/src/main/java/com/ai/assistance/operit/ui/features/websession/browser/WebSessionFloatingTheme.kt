package com.ai.assistance.operit.ui.features.websession.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.ai.assistance.operit.data.model.SerializableColorScheme
import com.ai.assistance.operit.data.model.SerializableTypography
import com.ai.assistance.operit.data.model.toComposeColorScheme
import com.ai.assistance.operit.data.model.toComposeTypography
import com.ai.assistance.operit.services.FloatingChatService
import com.ai.assistance.operit.ui.floating.FloatingWindowTheme
import com.google.gson.Gson
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val FLOATING_PREFS_NAME = "floating_chat_prefs"
private const val PREF_KEY_COLOR_SCHEME = "floating_color_scheme_json"
private const val PREF_KEY_TYPOGRAPHY = "floating_typography_json"

private data class WebSessionFloatingThemeSnapshot(
    val colorScheme: ColorScheme? = null,
    val typography: Typography? = null
)

private object FloatingThemeStore {
    private val gson by lazy { Gson() }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerRegistered = AtomicBoolean(false)
    private val loadLock = Any()
    private var lastColorRaw: String? = null
    private var lastTypographyRaw: String? = null
    private var loadedOnce = false

    // Strong reference: SharedPreferences stores listeners weakly.
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    val snapshot = MutableStateFlow(WebSessionFloatingThemeSnapshot())

    fun refreshBlocking(appContext: Context) {
        val prefs = appContext.getSharedPreferences(FLOATING_PREFS_NAME, Context.MODE_PRIVATE)
        if (listenerRegistered.compareAndSet(false, true)) {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == PREF_KEY_COLOR_SCHEME || key == PREF_KEY_TYPOGRAPHY || key == null) {
                        ioScope.launch { refreshBlocking(appContext) }
                    }
                }
            prefsListener = listener
            prefs.registerOnSharedPreferenceChangeListener(listener)
        }
        synchronized(loadLock) {
            val colorRaw = prefs.getString(PREF_KEY_COLOR_SCHEME, null)
            val typographyRaw = prefs.getString(PREF_KEY_TYPOGRAPHY, null)
            if (loadedOnce && colorRaw == lastColorRaw && typographyRaw == lastTypographyRaw) {
                return
            }
            val colorScheme =
                colorRaw
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        runCatching {
                            gson.fromJson(raw, SerializableColorScheme::class.java).toComposeColorScheme()
                        }.getOrNull()
                    }
            val typography =
                typographyRaw
                    ?.takeIf { it.isNotBlank() }
                    ?.let { raw ->
                        runCatching {
                            gson.fromJson(raw, SerializableTypography::class.java).toComposeTypography()
                        }.getOrNull()
                    }
            lastColorRaw = colorRaw
            lastTypographyRaw = typographyRaw
            loadedOnce = true
            snapshot.value = WebSessionFloatingThemeSnapshot(colorScheme = colorScheme, typography = typography)
        }
    }

    fun warmAsync(appContext: Context): Deferred<Unit> =
        ioScope.async { refreshBlocking(appContext.applicationContext) }
}

/** Starts loading the saved floating theme on IO and waits briefly so the first frame avoids flashing. */
internal suspend fun warmFloatingThemeBriefly(context: Context, maxWaitMs: Long = 30L) {
    val job = FloatingThemeStore.warmAsync(context.applicationContext)
    withTimeoutOrNull(maxWaitMs) { job.await() }
}

@Composable
internal fun WebSessionFloatingTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val snapshot by FloatingThemeStore.snapshot.collectAsState()

    LaunchedEffect(context) {
        withContext(Dispatchers.IO) { FloatingThemeStore.refreshBlocking(context) }
    }

    val liveService = FloatingChatService.getInstance()
    FloatingWindowTheme(
        colorScheme = liveService?.getColorScheme() ?: snapshot.colorScheme,
        typography = liveService?.getTypography() ?: snapshot.typography
    ) {
        content()
    }
}

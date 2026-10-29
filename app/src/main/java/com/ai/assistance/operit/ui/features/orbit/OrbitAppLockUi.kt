package com.ai.assistance.operit.ui.features.orbit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitAppLock
import com.ai.assistance.operit.ui.common.dynamicui.CardIcons

private tailrec fun Context.lockActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.lockActivity()
    else -> null
}

/**
 * The system check (fingerprint, face or the screen lock). The returned function shows it with the
 * given title; [onResult] gets true only when the user passed it.
 */
@Composable
internal fun rememberOrbitDeviceCheck(onResult: (Boolean) -> Unit): (title: String) -> Unit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onResult)
    val subtitle = stringResource(R.string.orbit_app_lock_prompt_note)
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        latest(result.resultCode == Activity.RESULT_OK)
    }
    return remember(context, subtitle, legacy) {
        { title ->
            val activity = context.lockActivity()
            val shown = activity != null && OrbitAppLock.showSystemPrompt(activity, title, subtitle) { ok -> latest(ok) }
            if (!shown) {
                val intent = OrbitAppLock.legacyIntent(context, title, subtitle)
                if (intent == null || runCatching { legacy.launch(intent) }.isFailure) latest(false)
            }
        }
    }
}

/**
 * Keeps Orbit behind the app lock while it is on and not yet passed. The pages keep their saved
 * state while locked, so passing the check returns to where the user was.
 */
@Composable
internal fun OrbitAppLockGate(enabled: Boolean, orbit: OrbitProfile?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val unlocked by OrbitAppLock.unlocked.collectAsState()
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // Read again on each return: a phone whose screen lock was removed has nothing left to check
    // against, and the user must not be shut out of Orbit (and of the switch that turns this off).
    val secure = remember(enabled, resumed) { !enabled || OrbitAppLock.deviceSecure(context) }
    val holder = rememberSaveableStateHolder()
    if (!enabled || unlocked || !secure) {
        holder.SaveableStateProvider("orbit") { content() }
        return
    }
    // The lock wears Orbit's own look (its light or dark mode and colors), like the pages behind it.
    if (orbit != null) {
        OrbitAppearanceTheme(orbit) { OrbitLockScreen(orbit = orbit, resumed = resumed) }
    } else {
        OrbitLockScreen(orbit = null, resumed = resumed)
    }
}

@Composable
private fun OrbitLockScreen(orbit: OrbitProfile?, resumed: Boolean) {
    val context = LocalContext.current
    val name = orbit?.let { orbitName(it) } ?: stringResource(R.string.nav_orbit)
    val title = stringResource(R.string.orbit_app_lock_prompt_title, name)
    val check = rememberOrbitDeviceCheck { ok -> if (ok) OrbitAppLock.markUnlocked() }
    // The check shows by itself once per lock; after a cancel it waits for the button.
    LaunchedEffect(resumed) {
        if (resumed && !OrbitAppLock.autoPrompted) {
            OrbitAppLock.autoPrompted = true
            check(title)
        }
    }
    // Back leaves the app as it would from Orbit's first page; it never skips the lock.
    BackHandler { context.lockActivity()?.moveTaskToBack(true) }
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize()
            // Taps never reach anything behind the lock.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        val windowHeight = maxHeight
        // Centered when it fits; short windows and large text scroll, so the unlock button stays reachable.
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = windowHeight)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (orbit != null) {
                OrbitAvatar(orbit = orbit, size = 96)
            } else {
                Icon(CardIcons.get("lucide:lock-keyhole") ?: return@Column, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.orbit_app_lock_locked_note), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            OrbitDialogAction(onClick = { check(title) }) {
                Icon(CardIcons.get("lucide:fingerprint") ?: return@OrbitDialogAction, contentDescription = null,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.orbit_app_lock_unlock))
            }
        }
        }
    }
    }
}

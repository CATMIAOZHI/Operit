package com.ai.assistance.operit.ui.features.settings.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.application.AppLauncherIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Picks the name and icon on the home screen: Orbit or Operit Ry. Turning off the entry the app
 * was opened from makes Android close its window, so the switch is confirmed first.
 */
@Composable
fun AppIconSettingsRow(searchable: Boolean = true) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // Null until the package manager answers, so neither option flashes as chosen.
    var chosen by remember { mutableStateOf<AppLauncherIdentity?>(null) }
    var asking by remember { mutableStateOf<AppLauncherIdentity?>(null) }
    // True while a switch is being written; the options wait for it.
    var switching by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        chosen = withContext(Dispatchers.IO) { AppLauncherIdentity.current(context.applicationContext) }
    }
    val title = stringResource(R.string.app_icon_setting_title)
    Column(
        modifier =
            (if (searchable) Modifier.settingsSearchTarget(title) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(
            // Both cards as tall as the taller one, when a long name wraps.
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppLauncherIdentity.entries.forEach { identity ->
                AppIconOption(
                    identity = identity,
                    selected = chosen == identity,
                    enabled = chosen != null && !switching,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = {
                        if (chosen != null && !switching && chosen != identity) asking = identity
                    },
                )
            }
        }
    }

    asking?.let { target ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = {
                Text(stringResource(R.string.app_icon_switch_title, identityName(target)))
            },
            text = { Text(stringResource(R.string.app_icon_switch_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        asking = null
                        haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        switching = true
                        AppLauncherIdentity.switchTo(context, target) { now ->
                            chosen = now
                            switching = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.app_icon_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun identityName(identity: AppLauncherIdentity): String =
    stringResource(
        if (identity == AppLauncherIdentity.ORBIT) R.string.orbit_launcher_name else R.string.app_name
    )

@Composable
private fun AppIconOption(
    identity: AppLauncherIdentity,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val name = identityName(identity)
    Surface(
        modifier =
            modifier
                .clip(RoundedCornerShape(16.dp))
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color =
            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border =
            BorderStroke(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.size(56.dp).clip(CircleShape)) {
                val (background, foreground) =
                    if (identity == AppLauncherIdentity.ORBIT) {
                        R.drawable.ic_launcher_orbit_background to R.drawable.ic_launcher_orbit_foreground
                    } else {
                        R.drawable.ic_launcher_background_simple to R.drawable.ic_launcher_simple_foreground
                    }
                Image(painterResource(background), contentDescription = null, modifier = Modifier.fillMaxSize())
                Image(painterResource(foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

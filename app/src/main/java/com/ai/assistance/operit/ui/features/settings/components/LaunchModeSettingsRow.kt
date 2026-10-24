package com.ai.assistance.operit.ui.features.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.AppLaunchMode
import com.ai.assistance.operit.data.preferences.LaunchModePreferences

@Composable
fun LaunchModeSettingsRow(searchable: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember { LaunchModePreferences.getInstance(context) }
    val mode by preferences.modeFlow.collectAsState()
    SettingsSwitchRow(
        title = stringResource(R.string.launch_mode_setting_title),
        subtitle = stringResource(
            if (mode == AppLaunchMode.ORBIT) R.string.launch_mode_setting_orbit
            else R.string.launch_mode_setting_standard
        ),
        checked = mode == AppLaunchMode.ORBIT,
        searchable = searchable,
        onCheckedChange = { orbit ->
            preferences.setMode(if (orbit) AppLaunchMode.ORBIT else AppLaunchMode.STANDARD)
        },
    )
}

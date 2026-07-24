package com.ai.assistance.operit.ui.common

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.ai.assistance.operit.ui.theme.rainyBaseColorScheme

@Composable
fun OperitUtilityTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = rainyBaseColorScheme(isSystemInDarkTheme()),
        content = content
    )
}

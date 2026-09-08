package com.ai.assistance.operit.ui.floating

import androidx.compose.runtime.Composable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ColorScheme
import com.ai.assistance.operit.ui.theme.rainyBaseColorScheme

/**
 * 为悬浮窗提供的独立主题
 * 使用静态颜色，避免对Activity上下文的依赖
 */
@Composable
fun FloatingWindowTheme(
    colorScheme: ColorScheme? = null,
    typography: Typography? = null,
    followAppTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val appStyle = if (followAppTheme) com.ai.assistance.operit.ui.theme.rememberAppThemeStyle() else null
    val finalColorScheme = appStyle?.colorScheme ?: colorScheme ?: rainyBaseColorScheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme())
    
    val finalTypography = appStyle?.typography ?: typography ?: com.ai.assistance.operit.ui.theme.Typography

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = finalTypography,
        content = content
    )
}

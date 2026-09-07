package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.ui.theme.Typography

/** Shared visual rules for main and floating composers; interaction remains with each host. */
fun chatComposerShape(agent: Boolean, floating: Boolean) = when {
    floating -> RoundedCornerShape(22.dp)
    agent -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    else -> RoundedCornerShape(0.dp)
}

@Composable
fun chatComposerTextStyle(agent: Boolean) = MaterialTheme.typography.bodyMedium.let { base ->
    val scale = base.fontSize.value / Typography.bodyMedium.fontSize.value
    base.copy(
        fontSize = (if (agent) 14.sp else 13.sp) * scale,
        lineHeight = (if (agent) 20.sp else 16.sp) * scale,
    )
}

@Composable
fun chatComposerColor(agent: Boolean, transparent: Boolean, hasBackgroundImage: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    val darkAgent = agent && scheme.onSurface.luminance() > 0.5f
    val darkColor = lerp(scheme.surface, scheme.onSurface, 0.08f)
    return when {
        transparent -> Color.Transparent
        darkAgent && hasBackgroundImage -> darkColor.copy(alpha = 0.82f)
        darkAgent -> darkColor
        hasBackgroundImage -> scheme.surface.copy(alpha = 0.85f)
        else -> scheme.surface
    }
}

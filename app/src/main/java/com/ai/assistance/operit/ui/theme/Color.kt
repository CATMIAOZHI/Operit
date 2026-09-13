package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color

// Mirrors the active OpenCode Rainy theme palette.
val RainyPink = Color(0xFFFF85A2)
val RainyPinkHover = Color(0xFFFF6B8E)
val RainySakura = Color(0xFFFFB3C6)
val RainyRose = Color(0xFFE91E63)

val RainyLightBackground = Color(0xFFFFF0F5)
val RainyLightPanel = Color(0xFFFFFFFF)
val RainyLightElement = Color(0xFFFAF6F8)
val RainyLightHover = Color(0xFFFFD1DC)
val RainyLightText = Color(0xFF3D2C35)
val RainyLightMuted = Color(0xFF7A6B72)
val RainyLightBorder = Color(0xFFD9CFD3)

val RainyDarkBackground = Color(0xFF1F1419)
val RainyDarkPanel = Color(0xFF2A1F25)
val RainyDarkElement = Color(0xFF3D2A33)
val RainyDarkText = Color(0xFFEFE0E5)
val RainyDarkMuted = Color(0xFFC9B8BE)
val RainyDarkBorder = Color(0xFF4D3A42)

val RainySuccess = Color(0xFF66BB6A)
val RainyWarning = Color(0xFFFFA726)
val RainyInfo = Color(0xFF64B5F6)

/**
 * The warning hue kept legible as small text on the light background. RainyWarning is a decorative
 * orange with too little contrast there to be read as a label, and a stopped run's label also lands
 * on the selected row's tinted container, which is the darkest surface it is drawn on.
 */
val RainyWarningTextLight = Color(0xFF963F12)

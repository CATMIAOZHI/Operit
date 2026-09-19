package com.ai.assistance.operit.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Determines the appropriate text color (black or white) based on background color
 *
 * Measured rather than thresholded: the 0.299/0.587/0.114 luma this used to compare against 0.5
 * agrees with a real contrast ratio for most fills, but it picks the worse of the two on 14.4% of
 * them. A bright green `#00D901` sits just under the line, so it used to get white text at 1.92:1
 * where black measures 10.93:1. The fills that reach here are the user's own colors (the drawer
 * background, chat bubbles, the color picker preview), so the side that reads wins.
 */
fun getTextColorForBackground(backgroundColor: Color): Color =
        getResolvedContrastingTextColor(backgroundColor)

/**
 * Determines if a color has high contrast with both black and white
 * Colors in the middle range (not too light, not too dark) tend to have low contrast with both
 * A good high contrast color is either fairly dark or fairly light
 */
fun isHighContrast(backgroundColor: Color): Boolean {
    val luminance =
            0.299 * backgroundColor.red +
                    0.587 * backgroundColor.green +
                    0.114 * backgroundColor.blue
    return luminance < 0.3 || luminance > 0.7
} 

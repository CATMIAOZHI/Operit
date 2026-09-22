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
 * Whether the better of black and white reaches WCAG AAA for body text (7:1) on
 * [backgroundColor]. The color picker rates a pick with this next to the sample text it draws with
 * [getTextColorForBackground].
 *
 * This used to threshold a 0.299/0.587/0.114 luma at 0.3/0.7, which called 65.5% of the color space
 * "low contrast" - including picks whose sample text now measures 5.3:1 or 10.9:1. Since the label
 * is chosen by measurement, so is the rating; one side always clears 4.58:1, so this is about
 * headroom rather than about being readable at all.
 */
fun isHighContrast(backgroundColor: Color): Boolean =
        maxOf(
                resolvedContrastRatio(Color.Black, backgroundColor),
                resolvedContrastRatio(Color.White, backgroundColor),
        ) >= HIGH_CONTRAST_RATIO

/** WCAG AAA for body text. */
private const val HIGH_CONTRAST_RATIO = 7.0

package com.ai.assistance.operit.ui.features.orbit

import org.junit.Assert.assertEquals
import org.junit.Test

class OrbitAvatarColorTest {
    @Test fun transparentPaddingAndPlainBackgroundDoNotHideCharacterColor() {
        val pixels = IntArray(600) { 0x00FF0000 } + IntArray(600) { 0xFFFFFFFF.toInt() } +
            IntArray(600) { 0xFF000000.toInt() } + IntArray(200) { 0xFFCA8EAE.toInt() }
        assertEquals(0xFFCA8EAE.toInt(), orbitDominantColor(pixels))
    }

    @Test fun emptyOrMonochromeImageUsesNeutralFallback() {
        assertEquals(0xFFDDE6F2.toInt(), orbitDominantColor(intArrayOf()))
        assertEquals(0xFFDDE6F2.toInt(), orbitDominantColor(intArrayOf(0, -1, 0xFF000000.toInt())))
    }
}

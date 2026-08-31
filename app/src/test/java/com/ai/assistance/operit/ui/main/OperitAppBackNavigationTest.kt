package com.ai.assistance.operit.ui.main

import com.ai.assistance.operit.ui.main.screens.Screen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitAppBackNavigationTest {
    @Test
    fun aiChatAtRootLeavesSystemBackToAndroid() {
        assertFalse(shouldHandleSystemBack(canPop = false, currentScreen = Screen.AiChat))
    }

    @Test
    fun aiChatPushedFromToolPkgUsesOperitBackStack() {
        assertTrue(shouldHandleSystemBack(canPop = true, currentScreen = Screen.AiChat))
    }

    @Test
    fun nonChatScreenAlwaysUsesOperitBackHandling() {
        assertTrue(shouldHandleSystemBack(canPop = false, currentScreen = Screen.Packages))
    }
}

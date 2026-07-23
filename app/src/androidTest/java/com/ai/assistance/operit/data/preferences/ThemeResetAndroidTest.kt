package com.ai.assistance.operit.data.preferences

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 DataStore 重置回归测试。
 *
 * 正确语义：关闭自定义颜色时默认显示 Rainy 粉色（通过 Material 基础色方案）。
 * 重置后 useCustomColors=false，颜色键删除 → Flow 返回 null。
 * 基础色方案已在 Color.kt / Theme.kt / ThemeColorSchemeResolver.kt 中改为 Rainy。
 */
@RunWith(AndroidJUnit4::class)
class ThemeResetAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        runBlocking {
            val manager = UserPreferencesManager.getInstance(context)
            manager.resetThemeSettings()
        }
    }

    @Test
    fun `reset clears custom colors and sets useCustomColors false`() = runBlocking {
        val manager = UserPreferencesManager.getInstance(context)

        // 先写入自定义颜色和 useCustomColors=true，模拟 v1 错误状态
        manager.saveThemeSettings(
            customPrimaryColor = 0xFF0000FF.toInt(),
            customSecondaryColor = 0xFF00FF00.toInt(),
            useCustomColors = true,
        )

        // 执行重置
        manager.resetThemeSettings()

        // 断言：开关为 false，颜色键被删除（Flow 返回 null）
        val ucc = manager.useCustomColors.first()
        val primary = manager.customPrimaryColor.first()
        val secondary = manager.customSecondaryColor.first()

        assertFalse("useCustomColors must be false after reset", ucc)
        assertNull("primary color must be null (key deleted)", primary)
        assertNull("secondary color must be null (key deleted)", secondary)
    }

    @Test
    fun `snapshot has useCustomColors false and null colors after reset`() = runBlocking {
        val manager = UserPreferencesManager.getInstance(context)
        manager.resetThemeSettings()

        val snapshot = manager.resolveThemePreferenceSnapshot()
        assertEquals("source must be global", "global", snapshot.source)
        assertFalse("useCustomColors must be false in snapshot", snapshot.useCustomColors)
        assertNull("customPrimaryColor must be null in snapshot", snapshot.customPrimaryColor)
        assertNull("customSecondaryColor must be null in snapshot", snapshot.customSecondaryColor)
    }

    @Test
    fun `reset preserves non color defaults like chat style`() = runBlocking {
        val manager = UserPreferencesManager.getInstance(context)

        // 先写入非默认的聊天样式
        manager.saveThemeSettings(chatStyle = "cursor")
        manager.resetThemeSettings()

        // 重置应删除该键，回退到 Flow 默认值 bubble
        val snapshot = manager.resolveThemePreferenceSnapshot()
        assertEquals("chatStyle must reset to bubble", "bubble", snapshot.chatStyle)
    }
}

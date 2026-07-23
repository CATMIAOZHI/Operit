package com.ai.assistance.operit.data.preferences

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实 DataStore 重置回归测试：验证 resetThemeSettings() 将颜色恢复为 Rainy 默认值，
 * 并且 resolveThemePreferenceSnapshot 对缺键和回退也返回匹配的 Rainy 结果。
 */
@RunWith(AndroidJUnit4::class)
class ThemeResetAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        // 测试结束后再次重置，避免测试数据污染同一测试包中的其他用例
        runBlocking {
            val manager = UserPreferencesManager.getInstance(context)
            manager.resetThemeSettings()
        }
    }

    @Test
    fun `reset to Rainy writes correct colors`() = runBlocking {
        val manager = UserPreferencesManager.getInstance(context)

        // 先保存非 Rainy 颜色和 useCustomColors=false，模拟旧默认状态
        val otherPrimary = 0xFF0000FF.toInt()
        val otherSecondary = 0xFF00FF00.toInt()
        manager.saveThemeSettings(
            customPrimaryColor = otherPrimary,
            customSecondaryColor = otherSecondary,
            useCustomColors = false,
        )

        // 执行重置
        manager.resetThemeSettings()

        // 断言 Flow 返回 Rainy
        val ucc = manager.useCustomColors.first()
        val primary = manager.customPrimaryColor.first()
        val secondary = manager.customSecondaryColor.first()

        assertEquals("useCustomColors must be true after reset", true, ucc)
        assertEquals(
            "primary color must be DEFAULT_CUSTOM_PRIMARY_COLOR",
            UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR,
            primary,
        )
        assertEquals(
            "secondary color must be DEFAULT_CUSTOM_SECONDARY_COLOR",
            UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR,
            secondary,
        )
    }

    @Test
    fun `snapshot resolves Rainy after reset`() = runBlocking {
        val manager = UserPreferencesManager.getInstance(context)
        manager.resetThemeSettings()

        val snapshot = manager.resolveThemePreferenceSnapshot()
        assertEquals("source must be global", "global", snapshot.source)
        assertEquals("useCustomColors must be true in snapshot", true, snapshot.useCustomColors)
        assertEquals(
            "customPrimaryColor must be Rainy in snapshot",
            UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR,
            snapshot.customPrimaryColor,
        )
        assertEquals(
            "customSecondaryColor must be Rainy in snapshot",
            UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR,
            snapshot.customSecondaryColor,
        )
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

    @Test
    fun `snapshot resolves Rainy for fresh install with no keys`() = runBlocking {
        val manager = UserPreferencesManager.getInstance(context)

        // 确保三键不存在（先删除）
        manager.resetThemeSettings()

        val snapshot = manager.resolveThemePreferenceSnapshot()
        assertTrue("useCustomColors must default to true", snapshot.useCustomColors)
        assertEquals(
            "customPrimaryColor must default to Rainy",
            UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR,
            snapshot.customPrimaryColor,
        )
        assertEquals(
            "customSecondaryColor must default to Rainy",
            UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR,
            snapshot.customSecondaryColor,
        )
    }
}

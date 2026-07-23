package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 主题迁移行为契��（[UserPreferencesManager.performThemeMigration]）与纯判定函数测试。
 *
 * 迁移行为契约：
 *
 * 1. 旧默认形态（useCustomColors 不为 true 且主色、辅色均不存在）→ 一次性纠正为 Rainy。
 *    - 包括全局和角色卡/群组前缀数据。
 *    - 与 v1 写入的 false + 无颜色值状态一致。
 *
 * 2. 非旧默认形态不覆盖。
 *    - useCustomColors=true 或有任一颜色值 → 保留。
 *    - useCustomColors=false 但有颜色 → 不覆盖（用户有意关闭但保留配色）。
 *
 * 3. 全缺键状态（三键都不存在）→ 也写入 Rainy，形成稳定的出厂数据。
 *
 * 4. 幂等：theme_rainy_defaults_v2_done=true 后不再执行。
 *
 * 5. v1 完成标记 theme_migration_v1_done 不阻止 v2 执行；
 *    只有 v2 完成标记可以阻止重复迁移。
 */
class ThemeMigrationTest {

    // ========== 基础常量测试 ==========

    @Test
    fun `Rainy default colors are non zero and distinct`() {
        val primary = UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR
        val secondary = UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR
        assertTrue("DEFAULT_CUSTOM_PRIMARY_COLOR must be non-zero", primary != 0)
        assertTrue("DEFAULT_CUSTOM_SECONDARY_COLOR must be non-zero", secondary != 0)
        assertTrue("primary and secondary must be distinct", primary != secondary)
    }

    // ========== isOldDefaultThemeState 纯判定函数测试 ==========

    @Test
    fun `false plus no colors is old default`() {
        assertTrue(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = false,
                hasPrimaryColor = false,
                hasSecondaryColor = false,
            )
        )
    }

    @Test
    fun `null useCustomColors plus no colors is old default`() {
        // 全缺键形态：useCustomColors 为 null（三键都不存在）
        assertTrue(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = null,
                hasPrimaryColor = false,
                hasSecondaryColor = false,
            )
        )
    }

    @Test
    fun `true plus no colors is NOT old default`() {
        // 用户显式开启自定义颜色但还未选择颜色
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = true,
                hasPrimaryColor = false,
                hasSecondaryColor = false,
            )
        )
    }

    @Test
    fun `false plus has primary color is NOT old default`() {
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = false,
                hasPrimaryColor = true,
                hasSecondaryColor = false,
            )
        )
    }

    @Test
    fun `false plus has secondary color is NOT old default`() {
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = false,
                hasPrimaryColor = false,
                hasSecondaryColor = true,
            )
        )
    }

    @Test
    fun `false plus both colors is NOT old default`() {
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = false,
                hasPrimaryColor = true,
                hasSecondaryColor = true,
            )
        )
    }

    @Test
    fun `true plus has colors is NOT old default`() {
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = true,
                hasPrimaryColor = true,
                hasSecondaryColor = true,
            )
        )
    }

    @Test
    fun `null plus has primary color is NOT old default`() {
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = null,
                hasPrimaryColor = true,
                hasSecondaryColor = false,
            )
        )
    }

    @Test
    fun `null plus has secondary color is NOT old default`() {
        assertFalse(
            UserPreferencesManager.isOldDefaultThemeState(
                useCustomColors = null,
                hasPrimaryColor = false,
                hasSecondaryColor = true,
            )
        )
    }
}

package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3 主题迁移行为契约（[UserPreferencesManager.performThemeMigration]）与纯判定函数测试。
 *
 * 迁移行为契约（v2 正确语义）：
 *
 * 1. 关闭自定义颜色时，默认显示 Rainy 粉色（通过 Material 基础色方案）。
 *    useCustomColors 默认为 false；Flow/快照颜色缺键返回 null。
 *
 * 2. 旧版错误写入纠正：旧版对全新安装写入了 useCustomColors=true + Rainy 颜色。
 *    v3 检测到这种精确组合时，删除颜色键并设置 useCustomColors=false。
 *
 * 3. 非 Rainy 色的自定义颜色不受影响，完整保留。
 *
 * 4. 幂等：theme_rainy_defaults_v3_done=true 后不再执行。
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

    // ========== isErroneousV1RainyState 纯判定函数测试 ==========

    @Test
    fun `true plus Rainy colors is erroneous v1 state`() {
        assertTrue(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = true,
                primaryColor = UserPreferencesManager.LEGACY_RAINY_PRIMARY_COLOR,
                secondaryColor = UserPreferencesManager.LEGACY_RAINY_SECONDARY_COLOR,
            )
        )
    }

    @Test
    fun `false plus Rainy colors is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = false,
                primaryColor = UserPreferencesManager.LEGACY_RAINY_PRIMARY_COLOR,
                secondaryColor = UserPreferencesManager.LEGACY_RAINY_SECONDARY_COLOR,
            )
        )
    }

    @Test
    fun `true plus null colors is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = true,
                primaryColor = null,
                secondaryColor = null,
            )
        )
    }

    @Test
    fun `true plus non Rainy primary is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = true,
                primaryColor = 0xFF0000FF.toInt(),
                secondaryColor = UserPreferencesManager.LEGACY_RAINY_SECONDARY_COLOR,
            )
        )
    }

    @Test
    fun `true plus non Rainy secondary is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = true,
                primaryColor = UserPreferencesManager.LEGACY_RAINY_PRIMARY_COLOR,
                secondaryColor = 0xFF00FF00.toInt(),
            )
        )
    }

    @Test
    fun `true plus only primary Rainy is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = true,
                primaryColor = UserPreferencesManager.LEGACY_RAINY_PRIMARY_COLOR,
                secondaryColor = null,
            )
        )
    }

    @Test
    fun `true plus only secondary Rainy is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = true,
                primaryColor = null,
                secondaryColor = UserPreferencesManager.LEGACY_RAINY_SECONDARY_COLOR,
            )
        )
    }

    @Test
    fun `false plus null colors is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = false,
                primaryColor = null,
                secondaryColor = null,
            )
        )
    }

    @Test
    fun `null useCustomColors plus Rainy colors is NOT erroneous`() {
        assertFalse(
            UserPreferencesManager.isErroneousV1RainyState(
                useCustomColors = null,
                primaryColor = UserPreferencesManager.LEGACY_RAINY_PRIMARY_COLOR,
                secondaryColor = UserPreferencesManager.LEGACY_RAINY_SECONDARY_COLOR,
            )
        )
    }
}

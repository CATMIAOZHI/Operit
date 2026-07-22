package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 [UserPreferencesManager.isFreshInstallMigration] 的新安装判定逻辑，
 * 并记录迁移行为契约供后续回归验证。
 *
 * 迁移行为契约（[UserPreferencesManager.performThemeMigration]）：
 *
 * 1. 全新安装：DataStore 首次打开，除 theme_migration_v1_done 自身外无任何键。
 *    迁移后应写入 USE_CUSTOM_COLORS=true, CUSTOM_PRIMARY_COLOR=DEFAULT_CUSTOM_PRIMARY_COLOR,
 *    CUSTOM_SECONDARY_COLOR=DEFAULT_CUSTOM_SECONDARY_COLOR, theme_migration_v1_done=true。
 *
 * 2. 老用户（USE_CUSTOM_COLORS=true 但无颜色键）：升级前用户只开了自定义颜色开关
 *    但从未保存颜色。迁移不覆盖 USE_CUSTOM_COLORS（已存在），不补颜色键 → 快照中
 *    customPrimaryColor=null, customSecondaryColor=null → ThemeColorSchemeResolver 的
 *    `customPrimaryColor?.let { }` 守卫跳过自定义配色 → 保持旧版行为。
 *
 * 3. 老用户（仅保存主色，无辅色）：辅色缺键 → 快照 customSecondaryColor=null →
 *    resolver 辅色跟随系统 colorScheme.secondary → 保持旧版行为。
 *
 * 4. 老用户（USE_CUSTOM_COLORS 缺键）：迁移补 false → useCustomColors=false →
 *    不应用自定义配色 → 保持旧版行为。
 *
 * 5. 重复启动：theme_migration_v1_done=true → 直接 return → 幂等。
 */
class ThemeMigrationTest {

    @Test
    fun `empty key set is fresh install`() {
        assertTrue(UserPreferencesManager.isFreshInstallMigration(emptySet()))
    }

    @Test
    fun `only migration key is fresh install`() {
        assertTrue(
            UserPreferencesManager.isFreshInstallMigration(
                setOf("theme_migration_v1_done")
            )
        )
    }

    @Test
    fun `any other key means existing user`() {
        assertFalse(
            UserPreferencesManager.isFreshInstallMigration(
                setOf("active_memory_space_id")
            )
        )
    }

    @Test
    fun `mix of migration key and other key means existing user`() {
        assertFalse(
            UserPreferencesManager.isFreshInstallMigration(
                setOf("theme_migration_v1_done", "use_custom_colors")
            )
        )
    }

    @Test
    fun `multiple non migration keys means existing user`() {
        assertFalse(
            UserPreferencesManager.isFreshInstallMigration(
                setOf("chat_style", "use_custom_colors", "custom_primary_color")
            )
        )
    }

    @Test
    fun `Rainy default colors are non zero and distinct`() {
        val primary = UserPreferencesManager.DEFAULT_CUSTOM_PRIMARY_COLOR
        val secondary = UserPreferencesManager.DEFAULT_CUSTOM_SECONDARY_COLOR
        assertTrue("DEFAULT_CUSTOM_PRIMARY_COLOR must be non-zero", primary != 0)
        assertTrue("DEFAULT_CUSTOM_SECONDARY_COLOR must be non-zero", secondary != 0)
        assertTrue("primary and secondary must be distinct", primary != secondary)
    }

    @Test
    fun `fresh install detection ignores custom migration key name`() {
        // Custom key name for testing flexibility
        assertTrue(
            UserPreferencesManager.isFreshInstallMigration(
                keyNames = setOf("custom_migration_flag"),
                migrationDoneKeyName = "custom_migration_flag"
            )
        )
        assertFalse(
            UserPreferencesManager.isFreshInstallMigration(
                keyNames = setOf("custom_migration_flag", "user_data"),
                migrationDoneKeyName = "custom_migration_flag"
            )
        )
    }
}

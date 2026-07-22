package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 [UserPreferencesManager.resolveThemePrefix] 的前缀解析逻辑：
 * - 正常角色卡/群组键名
 * - 键名重叠时的最长后缀匹配
 * - 非主题键应返回 null
 * - 缺少 `_` 边界的误匹配应被拒绝
 */
class ThemeMigrationPrefixResolutionTest {

    // 模拟精简版候选键名集合（与 buildThemeKeyNameSet() 结构一致）
    private val themeKeyNames = setOf(
        // String 键（与 getAllStringThemeKeys 一致的部分子集）
        "chat_style",
        "input_style",
        "custom_font_path",
        "bubble_user_custom_font_path",
        "bubble_ai_custom_font_path",
        "system_font_name",
        "bubble_user_system_font_name",
        "bubble_ai_system_font_name",
        // Boolean 键（与 getAllBooleanThemeKeys 一致的部分子集）
        "use_custom_colors",
        "use_custom_font",
        "bubble_user_use_custom_font",
        "bubble_ai_use_custom_font",
        "chat_input_floating",
        "bubble_wide_layout_enabled",
        "show_model_provider",
        "show_model_name",
        "show_message_token_stats",
        // Int 键
        "custom_primary_color",
        "custom_secondary_color",
    )

    @Test
    fun `normal prefix resolution for character card theme key`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_abc123_chat_style",
            themeKeyNames,
        )
        assertEquals("character_card_theme_abc123_", result)
    }

    @Test
    fun `normal prefix resolution for character group theme key`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_group_theme_grp456_use_custom_colors",
            themeKeyNames,
        )
        assertEquals("character_group_theme_grp456_", result)
    }

    @Test
    fun `returns null for key without theme prefix`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "some_other_key",
            themeKeyNames,
        )
        assertNull(result)
    }

    @Test
    fun `returns null for global key without prefix`() {
        // 全局键不以 character_card_theme_ 或 character_group_theme_ 开头
        val result = UserPreferencesManager.resolveThemePrefix(
            "chat_style",
            themeKeyNames,
        )
        assertNull(result)
    }

    // ========== 键名重叠——最长匹配测试 ==========

    @Test
    fun `longest match picks longer suffix for bubble_user_use_custom_font vs use_custom_font`() {
        // key: character_card_theme_X_bubble_user_use_custom_font
        // 候选: use_custom_font（短）、bubble_user_use_custom_font（长）— 应选长
        val key = "character_card_theme_x1_bubble_user_use_custom_font"
        val result = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertNotNull("应能解析出前缀", result)
        assertEquals("character_card_theme_x1_", result)
    }

    @Test
    fun `longest match picks longer suffix for bubble_ai_use_custom_font vs use_custom_font`() {
        val key = "character_card_theme_x2_bubble_ai_use_custom_font"
        val result = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertNotNull("应能解析出前缀", result)
        assertEquals("character_card_theme_x2_", result)
    }

    @Test
    fun `longest match picks longer suffix for bubble_user_custom_font_path vs custom_font_path`() {
        val key = "character_card_theme_x3_bubble_user_custom_font_path"
        val result = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertNotNull("应能解析出前缀", result)
        assertEquals("character_card_theme_x3_", result)
    }

    @Test
    fun `longest match picks longer suffix for bubble_ai_custom_font_path vs custom_font_path`() {
        val key = "character_card_theme_x4_bubble_ai_custom_font_path"
        val result = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertNotNull("应能解析出前缀", result)
        assertEquals("character_card_theme_x4_", result)
    }

    @Test
    fun `longest match picks longer suffix for bubble_user_system_font_name vs system_font_name`() {
        val key = "character_card_theme_x5_bubble_user_system_font_name"
        val result = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertNotNull("应能解析出前缀", result)
        assertEquals("character_card_theme_x5_", result)
    }

    // ========== 边界校验测试 ==========

    @Test
    fun `rejects match where candidate prefix does not end with underscore`() {
        // 如果键名恰好为 "character_card_theme_use_custom_colors"（无 card ID），
        // 前缀 "character_card_theme_" 以 `_` 结尾 → 会通过。
        // 但如果键名是 "character_card_themes"（不是合法前缀），
        // 它不以 character_card_theme_ 开头，已被第一层过滤拒绝 → null。
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_themes",  // 不是合法前缀
            themeKeyNames,
        )
        assertNull(result)
    }

    @Test
    fun `returns null when no theme key name matches`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_xyz_unknown_key_that_no_theme_has",
            themeKeyNames,
        )
        assertNull(result)
    }

    @Test
    fun `prefix preservation with card id containing underscores`() {
        // Card ID 为 "my_card_v2" — 应完整保留
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_my_card_v2_use_custom_colors",
            themeKeyNames,
        )
        assertEquals("character_card_theme_my_card_v2_", result)
    }

    @Test
    fun `prefix preservation with group id containing special chars`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_group_theme_grp-abc_42_chat_input_floating",
            themeKeyNames,
        )
        assertEquals("character_group_theme_grp-abc_42_", result)
    }

    // ========== 整数/颜色键名测试 ==========

    @Test
    fun `resolves prefix for custom primary color key`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_c1_custom_primary_color",
            themeKeyNames,
        )
        assertEquals("character_card_theme_c1_", result)
    }

    @Test
    fun `resolves prefix for custom secondary color key`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_c2_custom_secondary_color",
            themeKeyNames,
        )
        assertEquals("character_card_theme_c2_", result)
    }

    // ========== 无重叠的键名（回归测试）==========

    @Test
    fun `resolves prefix for show_model_provider key`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_p1_show_model_provider",
            themeKeyNames,
        )
        assertEquals("character_card_theme_p1_", result)
    }

    @Test
    fun `resolves prefix for show_model_name key without overlapping show_model_provider`() {
        // show_model_name 与 show_model_provider 不构成后缀关系
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_p2_show_model_name",
            themeKeyNames,
        )
        assertEquals("character_card_theme_p2_", result)
    }

    @Test
    fun `resolves prefix for show_message_token_stats key`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_p3_show_message_token_stats",
            themeKeyNames,
        )
        assertEquals("character_card_theme_p3_", result)
    }

    // ========== 空候选集合边界 ==========

    @Test
    fun `returns null for valid prefix but empty key name set`() {
        val result = UserPreferencesManager.resolveThemePrefix(
            "character_card_theme_abc_chat_style",
            emptySet(),
        )
        assertNull(result)
    }

    // ========== 幂等性：二次迁移不重复处理（逻辑层面）==========

    @Test
    fun `same input always produces same prefix`() {
        val key = "character_card_theme_abc123_chat_style"
        val first = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        val second = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertEquals(first, second)
    }

    @Test
    fun `edge case with long key name and multiple candidates`() {
        // 所有候选中最长匹配应清晰胜出
        val key = "character_group_theme_long_group_id_bubble_user_system_font_name"
        val result = UserPreferencesManager.resolveThemePrefix(key, themeKeyNames)
        assertNotNull(result)
        assertEquals("character_group_theme_long_group_id_", result)
    }
}

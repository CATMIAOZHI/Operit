package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ai.assistance.operit.ui.theme.RainyPink
import com.ai.assistance.operit.ui.theme.RainySakura
import com.ai.assistance.operit.ui.theme.RainyWarning

/**
 * 统计页颜色接口（阶段 4）：Rainy 粉色风格集中在这里，组件不散落硬编码色值。
 * 后续若增加统计页自定义配色，只需替换 [tokenStatsColors] 的取值来源，
 * 组件签名不变。
 */
data class TokenStatsColors(
    /** Token 分类堆叠：未缓存输入。 */
    val uncachedInput: Color,
    /** Token 分类堆叠：缓存读取。 */
    val cachedInput: Color,
    /** Token 分类堆叠：缓存写入。 */
    val cacheWrite: Color,
    /** Token 分类堆叠：输出。 */
    val output: Color,
    /** Token 分类堆叠：推理。 */
    val reasoning: Color,
    /** 折线图强调色。 */
    val chartAccent: Color,
    /** 图表网格参考线。 */
    val chartGrid: Color,
    /** 图表坐标轴标签。 */
    val chartLabel: Color,
    /** 图表 tooltip 容器。 */
    val tooltipContainer: Color,
    /** 图表 tooltip 文字。 */
    val tooltipContent: Color,
    /** 费用堆叠按模型取色（12 色 Material 色阶，模型多时循环）。 */
    val modelPalette: List<Color>,
    /** unknown 提示色（未知 ≠ 0）。 */
    val unknownHint: Color,
    /** “默认估算”徽标容器。 */
    val estimatedBadgeContainer: Color,
    /** “默认估算”徽标文字。 */
    val estimatedBadgeContent: Color,
    /** 生命周期累计总览卡片容器。 */
    val summaryCardContainer: Color,
    /** 生命周期累计总览卡片文字。 */
    val summaryCardContent: Color,
)

internal val TokenStatsCardContainer = Color.White
internal val TokenStatsCardContent = Color(0xFF202124)
internal val TokenStatsCardMuted = Color(0xFF5F6368)

// Rainytoken Token 堆叠色板：缓存读取（顶）、未缓存输入（中）、输出（底）。
internal val RainyTokenCacheRead = Color(0xFFFFD1DC)
internal val RainyTokenUncachedInput = Color(0xFFFF85A2)
internal val RainyTokenOutput = Color(0xFFE91E63)

/** 组件默认入口：从当前 [MaterialTheme] 派生，明暗自适应。 */
@Composable
fun tokenStatsColors(): TokenStatsColors {
    val scheme = MaterialTheme.colorScheme
    // 按实际背景亮度判断明暗（自定义主题/背景图下依然正确）
    val dark = scheme.background.luminance() < 0.5f
    return if (dark) darkTokenStatsColors(scheme) else lightTokenStatsColors(scheme)
}

private fun lightTokenStatsColors(scheme: androidx.compose.material3.ColorScheme): TokenStatsColors =
    TokenStatsColors(
        uncachedInput = RainyTokenUncachedInput,
        cachedInput = RainyTokenCacheRead,
        cacheWrite = RainySakura,
        output = RainyTokenOutput,
        reasoning = Color(0xFFF48FB1),
        chartAccent = RainyPink,
        chartGrid = scheme.outlineVariant,
        chartLabel = scheme.onSurfaceVariant,
        tooltipContainer = scheme.surfaceVariant,
        tooltipContent = scheme.onSurfaceVariant,
        modelPalette = MODEL_PALETTE,
        unknownHint = RainyWarning,
        estimatedBadgeContainer = scheme.tertiaryContainer,
        estimatedBadgeContent = scheme.onTertiaryContainer,
        summaryCardContainer = TokenStatsCardContainer,
        summaryCardContent = TokenStatsCardContent,
    )

private fun darkTokenStatsColors(scheme: androidx.compose.material3.ColorScheme): TokenStatsColors =
    TokenStatsColors(
        uncachedInput = RainyTokenUncachedInput,
        cachedInput = RainyTokenCacheRead,
        cacheWrite = RainySakura,
        output = RainyTokenOutput,
        reasoning = Color(0xFFF8BBD0),
        chartAccent = RainyPink,
        chartGrid = scheme.outlineVariant,
        chartLabel = scheme.onSurfaceVariant,
        tooltipContainer = scheme.surfaceVariant,
        tooltipContent = scheme.onSurfaceVariant,
        modelPalette = MODEL_PALETTE,
        unknownHint = RainyWarning,
        estimatedBadgeContainer = scheme.tertiaryContainer,
        estimatedBadgeContent = scheme.onTertiaryContainer,
        summaryCardContainer = TokenStatsCardContainer,
        summaryCardContent = TokenStatsCardContent,
    )

/** 模型费用堆叠色板（与 Rainytoken 参考实现一致，12 色足够区分常见模型数）。 */
private val MODEL_PALETTE =
    listOf(
        Color(0xFFF44336), // Red
        Color(0xFFE91E63), // Pink
        Color(0xFF9C27B0), // Purple
        Color(0xFF673AB7), // Deep Purple
        Color(0xFF3F51B5), // Indigo
        Color(0xFF2196F3), // Blue
        Color(0xFF00BCD4), // Cyan
        Color(0xFF009688), // Teal
        Color(0xFF4CAF50), // Green
        Color(0xFFFF9800), // Orange
        Color(0xFF795548), // Brown
        Color(0xFF607D8B), // Blue Grey
    )

/** 页面级 CompositionLocal：由 [TokenStatsColorsProvider] 提供。 */
val LocalTokenStatsColors = staticCompositionLocalOf<TokenStatsColors> {
    error("TokenStatsColors not provided")
}

/** 在子树内提供统计页颜色。 */
@Composable
fun TokenStatsColorsProvider(content: @Composable () -> Unit) {
    val colors = tokenStatsColors()
    CompositionLocalProvider(LocalTokenStatsColors provides colors, content = content)
}

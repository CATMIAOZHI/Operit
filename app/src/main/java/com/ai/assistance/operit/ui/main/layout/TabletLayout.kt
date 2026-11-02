package com.ai.assistance.operit.ui.main.layout

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ai.assistance.operit.ui.common.NavItem
import com.ai.assistance.operit.ui.main.navigation.NavigationEntrySpec
import com.ai.assistance.operit.ui.main.components.CollapsedDrawerContent
import com.ai.assistance.operit.ui.main.components.DrawerContent
import com.ai.assistance.operit.ui.main.components.rememberNavigationDrawerAppearance
import com.ai.assistance.operit.ui.main.screens.Screen
import com.ai.assistance.operit.ui.theme.waterGlass
import kotlinx.coroutines.CoroutineScope

/**
 * Layout for tablet devices with a permanent side navigation drawer. [content] is the app's one
 * page tree, shared with the phone layout; it is called once here.
 * With [hideWhenCollapsed] (Orbit) the collapsed sidebar is gone at once, with no closing slide
 * whose items could still be tapped, and [content] takes the whole width.
 */
@Composable
fun TabletLayout(
        selectedItem: NavItem?,
        isTabletSidebarExpanded: Boolean,
        navItems: List<NavItem>,
        pluginSidebarEntries: List<NavigationEntrySpec>,
        selectedRouteId: String,
        isNetworkAvailable: Boolean,
        networkType: String,
        scope: CoroutineScope,
        drawerState: androidx.compose.material3.DrawerState,
        tabletSidebarWidth: androidx.compose.ui.unit.Dp,
        collapsedTabletSidebarWidth: androidx.compose.ui.unit.Dp,
        hideWhenCollapsed: Boolean = false,
        onDrawerItemSelected: (Screen) -> Unit,
        onNavigationEntrySelected: (NavigationEntrySpec) -> Unit,
        content: @Composable () -> Unit,
) {
        val drawerAppearance = rememberNavigationDrawerAppearance()
        val sidebarWidthAnimationDurationMillis = 280
        val sidebarContentFadeDurationMillis = 160
        var isSidebarWidthExpanded by remember { mutableStateOf(isTabletSidebarExpanded) }
        var isSidebarContentExpanded by remember { mutableStateOf(isTabletSidebarExpanded) }

        LaunchedEffect(isTabletSidebarExpanded) {
                if (isTabletSidebarExpanded) {
                        isSidebarWidthExpanded = true
                        kotlinx.coroutines.delay(sidebarWidthAnimationDurationMillis.toLong())
                        isSidebarContentExpanded = true
                } else {
                        isSidebarContentExpanded = false
                        kotlinx.coroutines.delay(sidebarContentFadeDurationMillis.toLong())
                        isSidebarWidthExpanded = false
                }
        }

        // 计算侧边栏的动画宽度，轻微调整动画时间为280ms，保持原有效果但稍快
        val animatedSidebarWidth by
                animateDpAsState(
                        targetValue =
                                if (isSidebarWidthExpanded) tabletSidebarWidth
                                else collapsedTabletSidebarWidth,
                        animationSpec = tween(durationMillis = sidebarWidthAnimationDurationMillis),
                        label = "sidebarWidth"
                )

        // 使用Box作为顶层容器，这样可以允许子元素重叠
        val hidden = hideWhenCollapsed && !isTabletSidebarExpanded
        Box(modifier = Modifier.fillMaxSize()) {
                // 计算主内容区域的宽度（屏幕宽度减去侧边栏宽度），轻微调整动画时间
                val contentWidth by
                        animateDpAsState(
                                targetValue =
                                        if (isSidebarWidthExpanded)
                                                androidx.compose.ui.platform.LocalConfiguration
                                                        .current
                                                        .screenWidthDp
                                                        .dp - tabletSidebarWidth + 1.dp // 增加1dp解决右侧白线问题
                                        else
                                                androidx.compose.ui.platform.LocalConfiguration
                                                        .current
                                                        .screenWidthDp
                                                        .dp - collapsedTabletSidebarWidth + 1.dp, // 增加1dp解决右侧白线问题
                                animationSpec = tween(durationMillis = sidebarWidthAnimationDurationMillis),
                                label = "contentWidth"
                        )

                // 侧边栏区域，使用动画宽度，无圆角以完全遮住背景
                // Not drawn at all once it has shrunk to nothing, so nothing hidden can be tapped or read aloud.
                if (!hidden && animatedSidebarWidth > 0.dp) Surface(
                        modifier =
                                Modifier.width(animatedSidebarWidth)
                                        .fillMaxHeight()
                                        .waterGlass(
                                                enabled = drawerAppearance.waterGlassEnabled,
                                                shape = MaterialTheme.shapes.medium,
                                                containerColor = drawerAppearance.containerColor,
                                                shadowElevation = 4.dp,
                                                borderWidth = 0.7.dp,
                                                overlayAlphaBoost = 0.04f
                                        )
                                        .zIndex(2f), // 确保侧边栏在主内容之上
                        shadowElevation = if (drawerAppearance.waterGlassEnabled) 0.dp else 4.dp,
                        color =
                                if (drawerAppearance.waterGlassEnabled) Color.Transparent
                                else drawerAppearance.containerColor
                ) {
                        Crossfade(
                                targetState = isSidebarContentExpanded,
                                animationSpec = tween(durationMillis = sidebarContentFadeDurationMillis),
                                label = "sidebarContent"
                        ) { isContentExpanded ->
                                if (isContentExpanded) {
                                        DrawerContent(
                                                navItems = navItems,
                                                pluginEntries = pluginSidebarEntries,
                                                selectedItem = selectedItem,
                                                selectedRouteId = selectedRouteId,
                                                isNetworkAvailable = isNetworkAvailable,
                                                networkType = networkType,
                                                appearance = drawerAppearance,
                                                scope = scope,
                                                drawerState = drawerState,
                                                onScreenSelected = onDrawerItemSelected,
                                                onNavigationEntrySelected = onNavigationEntrySelected
                                        )
                                } else {
                                        CollapsedDrawerContent(
                                                navItems = navItems,
                                                pluginEntries = pluginSidebarEntries,
                                                selectedItem = selectedItem,
                                                selectedRouteId = selectedRouteId,
                                                isNetworkAvailable = isNetworkAvailable,
                                                appearance = drawerAppearance,
                                                onScreenSelected = onDrawerItemSelected,
                                                onNavigationEntrySelected = onNavigationEntrySelected
                                        )
                                }
                        }
                }

                // 主内容区域 - 使用width+offset方式，保留稳定的布局
                val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                Box(
                        modifier =
                                Modifier.width(if (hidden) screenWidth + 1.dp else contentWidth)
                                        .fillMaxHeight()
                                        .offset(x = if (hidden) 0.dp else animatedSidebarWidth)
                                        .zIndex(1f)
                ) {
                        content()
                }
        }
}

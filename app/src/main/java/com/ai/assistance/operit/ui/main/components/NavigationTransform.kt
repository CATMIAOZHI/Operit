package com.ai.assistance.operit.ui.main.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Layout

/**
 * Places the navigation layer on the child's outer coordinator. A graphicsLayer modifier
 * instead transforms a wrapped coordinator, whose cached subtree bounds can remain marked
 * as transformed after the animation in Compose 1.10.4, making later scrolling expensive.
 */
@Composable
internal fun NavigationTransform(
    modifier: Modifier = Modifier,
    layerBlock: GraphicsLayerScope.() -> Unit,
    content: @Composable () -> Unit,
) {
    Layout(modifier = modifier, content = content) { measurables, constraints ->
        val child = measurables.single().measure(constraints)
        layout(child.width, child.height) {
            child.placeWithLayer(0, 0, layerBlock = layerBlock)
        }
    }
}

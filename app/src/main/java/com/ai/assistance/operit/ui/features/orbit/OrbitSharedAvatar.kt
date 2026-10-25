package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.ai.assistance.operit.data.preferences.OrbitProfile

/**
 * Orbit's one picture. A single avatar is drawn once into a layer; the chat header, the profile
 * and the moving copy between them all show that same layer, so the picture never restarts, never
 * loads again and never jumps a frame. While the profile opens or closes, the moving copy is drawn
 * above both, so the picture always stays on top.
 */
@Stable
internal class OrbitSharedAvatar(
    /** The profile's place: 0 fully shown, 1 closed. Drives the profile page as well. */
    val profile: Animatable<Float, AnimationVector1D>,
) {
    /** The chat's header picture, and the one the other bottom tabs show in the same place. */
    internal var chatHeader: LayoutCoordinates? = null
    internal var tabHeader: LayoutCoordinates? = null
    /** Set by the screen: a bottom tab other than the chat is showing. */
    internal var onTab: Boolean = false
    internal var profileAvatar: LayoutCoordinates? = null
    internal var profilePage: LayoutCoordinates? = null
    internal var overlay: LayoutCoordinates? = null
    /** The drawn picture, and its side in pixels; places redraw when either changes. */
    internal var picture: GraphicsLayer? by mutableStateOf(null)
    internal var pictureSide: Float by mutableFloatStateOf(0f)

    private fun attached(c: LayoutCoordinates?) = c?.takeIf { it.isAttached && it.size.width > 0 }

    private val header: LayoutCoordinates? get() = if (onTab) tabHeader else chatHeader

    /** True while the moving copy is drawn, so the header's and the profile's places stay empty. */
    fun moving(): Boolean {
        val v = profile.value
        return v > 0f && v < 1f && attached(header) != null && attached(overlay) != null && profileRect() != null
    }

    /**
     * [moving] for drawing: it changes only when the moving copy starts or stops, so a frame of
     * the motion redraws no picture, only the copy's position.
     */
    val isMoving: State<Boolean> = derivedStateOf { moving() }

    fun headerRect(): Rect? {
        val o = attached(overlay) ?: return null
        val h = attached(header) ?: return null
        return o.localBoundingBoxOf(h, clipBounds = false)
    }

    /**
     * The profile picture's place on the page, without the page's own zoom. Null when it is not
     * fully in view (the list scrolled), so it then simply fades with the page.
     */
    fun profileRect(): Rect? {
        val page = attached(profilePage) ?: return null
        val a = attached(profileAvatar) ?: return null
        val full = Rect(page.localPositionOf(a, Offset.Zero), a.size.toSize())
        val shown = page.localBoundingBoxOf(a, clipBounds = true)
        if (shown.height < full.height - 1f || shown.width < full.width - 1f) return null
        return full
    }

    /** The profile page grows out of its picture. */
    fun pageZoom(scope: GraphicsLayerScope, s: Float) = with(scope) {
        alpha = 1f - s
        val scale = 1f - 0.1f * s
        scaleX = scale
        scaleY = scale
        val r = profileRect()
        transformOrigin = if (r != null && size.height > 0f && size.width > 0f) {
            TransformOrigin(r.center.x / size.width, r.center.y / size.height)
        } else TransformOrigin(0.5f, 0.1f)
    }
}

internal val LocalOrbitSharedAvatar = staticCompositionLocalOf<OrbitSharedAvatar?> { null }

/**
 * A place that shows Orbit's one picture: the chat header's or the profile's. It takes the tap
 * (shrinking a little, like iOS) and stays empty while the moving copy is on its way.
 */
@Composable
internal fun OrbitAvatarSlot(
    shared: OrbitSharedAvatar,
    size: Int,
    onClickLabel: String?,
    onClick: () -> Unit,
    report: (LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.93f else 1f, tween(140), label = "orbitAvatarSlotPress")
    Box(
        modifier.size(size.dp)
            .onGloballyPositioned(report)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClickLabel = onClickLabel, onClick = onClick)
            .drawWithContent {
                val picture = shared.picture ?: return@drawWithContent
                if (shared.isMoving.value || shared.pictureSide <= 0f) return@drawWithContent
                val k = this.size.width / shared.pictureSide
                scale(k, k, pivot = Offset.Zero) { drawLayer(picture) }
            }
    )
}

/**
 * Orbit's one picture, drawn into a layer for every place that shows it, and the moving copy
 * drawn above the profile and under any page pushed over it.
 */
@Composable
internal fun OrbitSharedAvatarLayer(orbit: OrbitProfile, shared: OrbitSharedAvatar, playing: Boolean) {
    val side = maxOf(orbit.appearance.avatarSize.dp, ORBIT_PROFILE_AVATAR_DP)
    val moving by shared.isMoving
    val picture = rememberGraphicsLayer()
    SideEffect { shared.picture = picture }
    Box(
        Modifier.fillMaxSize().onPlaced { shared.overlay = it }.clearAndSetSemantics { },
        contentAlignment = Alignment.TopStart,
    ) {
        Box(
            Modifier.size(side.dp)
                .graphicsLayer {
                    val from = shared.headerRect()
                    val to = shared.profileRect()
                    if (!shared.moving() || from == null || to == null) return@graphicsLayer
                    val t = shared.profile.value
                    transformOrigin = TransformOrigin(0f, 0f)
                    translationX = lerp(to.left, from.left, t)
                    translationY = lerp(to.top, from.top, t)
                    val scale = lerp(to.width, from.width, t) / this.size.width.coerceAtLeast(1f)
                    scaleX = scale
                    scaleY = scale
                }
                .onSizeChanged { shared.pictureSide = it.width.toFloat() }
                .drawWithContent {
                    picture.record { this@drawWithContent.drawContent() }
                    if (shared.isMoving.value) drawLayer(picture)
                }
        ) {
            OrbitAvatar(orbit = orbit, size = side, animatePet = true, petVisible = playing || moving)
        }
    }
}

internal const val ORBIT_PROFILE_AVATAR_DP = 120
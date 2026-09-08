package com.ai.assistance.operit.pet

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.theme.RainySuccess
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal const val PET_BUBBLE_WIDTH_DP = 232

@Composable
internal fun PetCompanion(
    settings: PetSettings,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onToggleBubble: () -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    dragging: Boolean = false,
    onDragStart: () -> Unit = {},
    anchorX: Float = settings.x,
    anchorY: Float = settings.y,
    dragPetOffset: IntOffset? = null,
    bubbleOnly: Boolean = false,
    previewAnimation: PetAnimation = PetAnimation.THINKING,
) {
    val context = LocalContext.current
    val model = remember(preview) { if (preview) null else PetTasks.get(context) }
    val tasks = model?.visibleTasks?.collectAsState()?.value.orEmpty()
    val selectedKey = model?.selectedKey?.collectAsState()?.value
    val task = if (preview) {
        PetTask("preview", "", com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN,
            stringResource(R.string.pet_preview_task), PetActivity.THINKING, true)
    } else {
        val selected = tasks.firstOrNull { it.key == selectedKey }
        selected?.takeUnless { it.activity == PetActivity.IDLE }
            ?: tasks.firstOrNull { it.active } ?: selected ?: tasks.lastOrNull()
    }
    var interaction by remember { mutableIntStateOf(0) }
    val recentTaps = remember(settings.animations, settings.mediaType) { ArrayDeque<Long>() }
    var dizzyUntil by remember(settings.animations, settings.mediaType) { mutableLongStateOf(0L) }
    val dizzy = dizzyUntil != 0L
    LaunchedEffect(dizzyUntil) {
        if (dizzyUntil != 0L) {
            delay((dizzyUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            dizzyUntil = 0L
        }
    }
    val lift by animateFloatAsState(
        if (dragging && settings.dragAnimation) 1f else 0f,
        animationSpec = if (settings.dragAnimation) spring(dampingRatio = 0.65f) else snap(),
        label = "petDragLift",
    )
    val petDescription = stringResource(R.string.pet_interact)
    val acknowledgeDescription = stringResource(R.string.pet_acknowledge_completion)
    val dragStartCallback by rememberUpdatedState(onDragStart)
    val dragCallback by rememberUpdatedState(onDrag)
    val dragEndCallback by rememberUpdatedState(onDragEnd)

    val dragModifier = if (preview) Modifier else Modifier.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = { dragStartCallback() },
            onDragEnd = { dragEndCallback() },
            onDragCancel = { dragEndCallback() },
        ) { change, amount ->
            change.consume()
            dragCallback(amount)
        }
    }
    val hasBubble = settings.showBubble
    val bubbleScroll = rememberScrollState()
    // In a height-constrained window, leave vertical gestures to content scrolling.
    val bubbleDragModifier = if (bubbleScroll.maxValue == 0) dragModifier else if (preview) Modifier else Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { dragStartCallback() },
            onDragEnd = { dragEndCallback() },
            onDragCancel = { dragEndCallback() },
        ) { change, amount ->
            change.consume()
            dragCallback(Offset(amount, 0f))
        }
    }
    Layout(
        modifier = modifier.graphicsLayer { alpha = settings.opacity },
        content = {
            if (!bubbleOnly) PetSprite(
                if (dizzy) PetActivity.ERROR else task?.activity ?: PetActivity.IDLE, settings, interaction,
                Modifier.size(settings.sizeDp.dp)
                    .semantics { contentDescription = petDescription }
                    .then(dragModifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (!dizzy) {
                            val now = SystemClock.elapsedRealtime()
                            while (recentTaps.isNotEmpty() && now - recentTaps.first() > 1500L) {
                                recentTaps.removeFirst()
                            }
                            recentTaps.addLast(now)
                            if (recentTaps.size >= 5) {
                                recentTaps.clear()
                                dizzyUntil = now + 3000L
                            } else {
                                interaction++
                            }
                        }
                        onToggleBubble()
                    }
                    // Pointer deltas must stay in screen-aligned coordinates; rotating
                    // the gesture layer turns a horizontal drag into diagonal movement.
                    .graphicsLayer {
                        scaleX = 1f - 0.08f * lift
                        scaleY = 1f - 0.03f * lift
                        rotationZ = -7f * lift
                    },
                dizzy = dizzy,
                animationOverride = if (preview && !dizzy) previewAnimation else null,
            )
            if (hasBubble) {
                Box(Modifier.padding(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        tonalElevation = 2.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.verticalScroll(bubbleScroll).then(bubbleDragModifier)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier.weight(1f)
                                        .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
                                ) {
                                    Text(
                                        task?.takeUnless { it.activity == PetActivity.IDLE }?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.pet_ready_to_chat),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(
                                        modifier = if (!preview && task?.activity == PetActivity.COMPLETE) {
                                            Modifier.clickable(onClickLabel = acknowledgeDescription) {
                                                task?.let { model?.acknowledgeCompletion(it) }
                                            }.padding(vertical = 4.dp)
                                        } else Modifier,
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (task?.activity == PetActivity.COMPLETE) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = RainySuccess,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                        Text(
                                            stringResource(if (preview) previewAnimation.label() else (task?.activity ?: PetActivity.IDLE).label()),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { model?.openFloating(task) },
                                    enabled = !preview,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = stringResource(R.string.pet_open_floating),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            if (tasks.size > 1) {
                                TextButton(onClick = {
                                    val index = tasks.indexOfFirst { it.key == task?.key }
                                    model?.selectedKey?.value = tasks[(index + 1) % tasks.size].key
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(R.string.pet_next_task, tasks.indexOf(task) + 1, tasks.size),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        if (bubbleOnly) {
            val bubble = measurables.single().measure(constraints.copy(minHeight = 0))
            return@Layout layout(bubble.width, bubble.height) { bubble.place(0, 0) }
        }
        val petSize = minOf(settings.sizeDp.dp.roundToPx(), constraints.maxWidth, constraints.maxHeight)
        val pet = measurables[0].measure(Constraints.fixed(petSize, petSize))
        val width = constraints.maxWidth
        val vertical = settings.edge.vertical
        val bubble = measurables.getOrNull(1)?.measure(Constraints(
            minWidth = if (vertical) width else (width - petSize).coerceAtLeast(0),
            maxWidth = if (vertical) width else (width - petSize).coerceAtLeast(0),
            maxHeight = (constraints.maxHeight - if (vertical) petSize else 0).coerceAtLeast(0),
        ))
        val height = (if (vertical) petSize + (bubble?.height ?: 0) else maxOf(petSize, bubble?.height ?: 0))
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // Keep the pet in the same composition slot when orientation or bubble visibility changes.
            pet.place(
                dragPetOffset?.x ?: ((width - pet.width) * anchorX).roundToInt(),
                dragPetOffset?.y ?: ((height - pet.height) * anchorY).roundToInt(),
            )
            bubble?.place(
                if (vertical || settings.edge == PetEdge.RIGHT) 0 else petSize,
                if (!vertical) ((height - bubble.height) * anchorY).roundToInt()
                else if (settings.edge == PetEdge.TOP) petSize else 0,
            )
        }
    }
}

@Composable
private fun PetSprite(
    activity: PetActivity, settings: PetSettings, interaction: Int, modifier: Modifier,
    dizzy: Boolean = false, animationOverride: PetAnimation? = null,
) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val animate = settings.animated && resumed
    if (!settings.isReady) {
        Icon(Icons.Default.Pets, stringResource(R.string.pet_idle_required), modifier)
        return
    }
    var greeting by remember(settings.animations) { mutableStateOf(false) }
    var handledInteraction by remember(settings.animations) { mutableIntStateOf(interaction) }
    LaunchedEffect(interaction, dizzy, animationOverride) {
        if (dizzy || animationOverride != null) {
            greeting = false
            handledInteraction = interaction
        } else if (interaction != handledInteraction) {
            greeting = settings.animated && settings.animations.containsKey(PetAnimation.GREETING)
            handledInteraction = interaction
        }
    }
    LaunchedEffect(activity, settings.animated) { greeting = false }
    LaunchedEffect(greeting, interaction, settings.mediaType) {
        if (greeting && settings.mediaType == PetMediaType.IMAGE) {
            delay(1500)
            greeting = false
        }
    }
    val transientGreeting = greeting && animationOverride == null && !dizzy
    val animation = animationOverride ?: if (transientGreeting) PetAnimation.GREETING else activity.animation()
    val assetId = settings.artwork(if (settings.mediaType == PetMediaType.ATLAS) PetAnimation.IDLE else animation)?.id.orEmpty()
    if (settings.mediaType == PetMediaType.GIF) {
        PetGif(assetId, animate, !transientGreeting, if (transientGreeting) interaction else 0, { greeting = false }, modifier)
        return
    }
    if (settings.mediaType == PetMediaType.VIDEO) {
        PetVideo(assetId, animate, !transientGreeting, if (transientGreeting) interaction else 0, { greeting = false }, modifier)
        return
    }
    val loaded by produceState<Result<PetImage>?>(null, assetId, settings.mediaType) {
        value = null
        value = try {
            Result.success(PetAssets.load(context, assetId, settings.mediaType == PetMediaType.ATLAS))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val image = loaded?.getOrNull()
    // The encoded row/column is intentionally not read by composition or layout.
    val cell = remember { mutableIntStateOf(0) }
    var playedInteraction by remember(settings.animations) { mutableIntStateOf(interaction) }
    val atlasAnimation = animationOverride ?: activity.animation()
    LaunchedEffect(atlasAnimation, animate, interaction, image, dizzy) {
        cell.intValue = 0
        if (image?.atlas != true) return@LaunchedEffect
        if (!animate || dizzy || animationOverride != null) playedInteraction = interaction
        if (animate && interaction > playedInteraction) {
            playedInteraction = interaction
            repeat(4) { frame -> cell.intValue = 3 * 8 + frame; delay(140) }
        }
        val row = when (atlasAnimation) {
            PetAnimation.TOOL -> 7
            PetAnimation.THINKING, PetAnimation.SUMMARIZING -> 8
            PetAnimation.COMPLETE, PetAnimation.GREETING -> 3
            PetAnimation.ERROR -> 5
            else -> 0
        }
        val count = when (row) { 3 -> 4; 5 -> 8; else -> 6 }
        cell.intValue = row * 8
        if (animate) {
            while (true) {
                repeat(count) { frame ->
                    cell.intValue = row * 8 + frame
                    delay(if (row == 0) longArrayOf(1680, 660, 660, 840, 840, 1920)[frame] else 150)
                }
                if (row != 0) delay(450)
            }
        }
    }
    if (loaded?.isFailure == true) {
        Icon(Icons.Default.BrokenImage, stringResource(R.string.pet_asset_unavailable), modifier)
        return
    }
    Canvas(modifier) {
        image?.let { source ->
            val atlas = source.bitmap
            val side = size.minDimension.roundToInt()
            val sourceWidth = if (source.atlas) 192 else atlas.width
            val sourceHeight = if (source.atlas) 208 else atlas.height
            val scale = side.toFloat() / maxOf(sourceWidth, sourceHeight)
            val width = (sourceWidth * scale).roundToInt()
            val height = (sourceHeight * scale).roundToInt()
            val current = if (source.atlas) cell.intValue else 0
            drawImage(
                atlas,
                srcOffset = IntOffset((current % 8) * 192, (current / 8) * 208),
                srcSize = IntSize(sourceWidth, sourceHeight),
                dstOffset = IntOffset((size.width.toInt() - width) / 2, (side - height) / 2),
                dstSize = IntSize(width, height),
            )
        }
    }
}

private fun PetActivity.label(): Int = when (this) {
    PetActivity.IDLE -> R.string.pet_idle
    PetActivity.THINKING -> R.string.pet_thinking
    PetActivity.TOOL -> R.string.pet_tool
    PetActivity.SUMMARIZING -> R.string.pet_summarizing
    PetActivity.COMPLETE -> R.string.pet_complete
    PetActivity.ERROR -> R.string.pet_error
    PetActivity.ENDED -> R.string.pet_ended
}

package com.ai.assistance.operit.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BrokenImage
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
) {
    val context = LocalContext.current
    val model = remember(preview) { if (preview) null else PetTasks.get(context) }
    val tasks = model?.visibleTasks?.collectAsState()?.value.orEmpty()
    val selectedKey = model?.selectedKey?.collectAsState()?.value
    val task = if (preview) {
        PetTask("preview", "", com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN,
            stringResource(R.string.pet_preview_task), PetActivity.THINKING, true)
    } else {
        tasks.firstOrNull { it.key == selectedKey } ?: tasks.firstOrNull { it.active } ?: tasks.lastOrNull()
    }
    var expanded by remember(task?.key) { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val petDescription = stringResource(R.string.pet_interact)
    val dragStartCallback by rememberUpdatedState(onDragStart)
    val dragCallback by rememberUpdatedState(onDrag)
    val dragEndCallback by rememberUpdatedState(onDragEnd)

    val hasBubble = task != null && settings.showBubble && !dragging
    Layout(
        modifier = modifier.graphicsLayer { alpha = settings.opacity },
        content = {
            PetSprite(
                task?.activity ?: PetActivity.IDLE, settings, interaction,
                Modifier.size(settings.sizeDp.dp)
                    .semantics { contentDescription = petDescription }
                    .then(if (preview) Modifier else Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { dragStartCallback() },
                            onDragEnd = { dragEndCallback() },
                            onDragCancel = { dragEndCallback() },
                        ) { change, amount ->
                            change.consume()
                            dragCallback(amount)
                        }
                    })
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        interaction++
                        expanded = false
                        onToggleBubble()
                    },
            )
            if (task != null && hasBubble) {
                Box(Modifier.padding(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        tonalElevation = 2.dp,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Column(
                                Modifier.fillMaxWidth().clickable(enabled = !preview) { expanded = !expanded }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    task.title.ifBlank { stringResource(R.string.pet_task) },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    stringResource(task.activity.label()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (tasks.size > 1) {
                                TextButton(onClick = {
                                    val index = tasks.indexOfFirst { it.key == task.key }
                                    model?.selectedKey?.value = tasks[(index + 1) % tasks.size].key
                                }, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        stringResource(R.string.pet_next_task, tasks.indexOf(task) + 1, tasks.size),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (expanded) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    IconButton(onClick = { model?.open(task) }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.pet_open_task))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val petSize = minOf(settings.sizeDp.dp.roundToPx(), constraints.maxWidth, constraints.maxHeight)
        val pet = measurables[0].measure(Constraints.fixed(petSize, petSize))
        val width = constraints.maxWidth
        val vertical = settings.edge.vertical
        val bubble = measurables.getOrNull(1)?.measure(Constraints(
            minWidth = if (vertical) width else (width - petSize).coerceAtLeast(0),
            maxWidth = if (vertical) width else (width - petSize).coerceAtLeast(0),
            maxHeight = (constraints.maxHeight - if (vertical) petSize else 0).coerceAtLeast(0),
        ))
        val height = if (vertical) petSize + (bubble?.height ?: 0) else maxOf(petSize, bubble?.height ?: 0)
        layout(width, height) {
            // Keep the pet in the same composition slot when orientation or bubble visibility changes.
            pet.place(
                ((width - pet.width) * anchorX).roundToInt(),
                ((height - pet.height) * anchorY).roundToInt(),
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
private fun PetSprite(activity: PetActivity, settings: PetSettings, interaction: Int, modifier: Modifier) {
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
    LaunchedEffect(interaction) {
        if (interaction != handledInteraction) {
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
    val animation = if (greeting) PetAnimation.GREETING else activity.animation()
    val assetId = settings.artwork(if (settings.mediaType == PetMediaType.ATLAS) PetAnimation.IDLE else animation)?.id.orEmpty()
    if (settings.mediaType == PetMediaType.GIF) {
        PetGif(assetId, animate, !greeting, if (greeting) interaction else 0, { greeting = false }, modifier)
        return
    }
    if (settings.mediaType == PetMediaType.VIDEO) {
        PetVideo(assetId, animate, !greeting, if (greeting) interaction else 0, { greeting = false }, modifier)
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
    LaunchedEffect(activity, animate, interaction, image) {
        cell.intValue = 0
        if (image?.atlas != true) return@LaunchedEffect
        if (!animate) playedInteraction = interaction
        if (animate && interaction > playedInteraction) {
            playedInteraction = interaction
            repeat(4) { frame -> cell.intValue = 3 * 8 + frame; delay(140) }
        }
        val row = when (activity) {
            PetActivity.TOOL -> 7
            PetActivity.THINKING, PetActivity.SUMMARIZING -> 8
            PetActivity.COMPLETE -> 3
            PetActivity.ERROR -> 5
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

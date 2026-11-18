package com.ai.assistance.operit.ui.features.chat.components.attachments

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.orbit.OrbitDialog
import com.ai.assistance.operit.ui.features.orbit.OrbitDialogAction
import com.ai.assistance.operit.ui.features.orbit.OrbitModalWindow
import com.ai.assistance.operit.ui.features.orbit.orbitIcon
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One line drawn with a finger, in the picture's own pixels; its path is built once. */
private class MarkStroke(val color: Color, val width: Float, val points: List<Offset>) {
    val path: Path = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        // A tap is a dot.
        if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y)
    }
}

/** A change to the picture, applied in order to the picture as it was opened. */
private sealed interface PictureEdit {
    /** A quarter turn to the right. */
    data object Turn : PictureEdit
    data class Cut(val left: Int, val top: Int, val width: Int, val height: Int) : PictureEdit
    data class Mark(val strokes: List<MarkStroke>) : PictureEdit
}

private enum class EditTool { NONE, CROP, MARK }

private val markColors = listOf(
    Color(0xFFE5484D) to R.string.attachment_edit_color_red,
    Color(0xFFF5C518) to R.string.attachment_edit_color_yellow,
    Color(0xFF30A46C) to R.string.attachment_edit_color_green,
    Color(0xFF3E7BFA) to R.string.attachment_edit_color_blue,
    Color.White to R.string.attachment_edit_color_white,
    Color.Black to R.string.attachment_edit_color_black,
)

/** The longest side a picture is edited at: sharp enough to send, small enough to keep a few copies. */
private const val EDIT_MAX_SIDE = 2560

private fun markWidth(picture: Bitmap) = max(picture.width, picture.height) * 0.008f + 2f

/**
 * A picture waiting in the input box, full screen. With [onEdited] it can also be turned, cropped
 * and marked up; Done hands an edited copy (the file and the name to show) to [onEdited] and
 * closes once it has taken it. The picture itself is never changed. Without [onEdited] it is
 * only shown.
 */
@Composable
fun AttachmentImageEditor(
    file: File,
    fileName: String,
    /** The picture's type as attached; a PNG stays a PNG even when its name does not say so. */
    mimeType: String,
    onEdited: (suspend (File, String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var original by remember(file) { mutableStateOf<Bitmap?>(null) }
    // What was done so far; the picture on screen is the opened one with these applied.
    val edits = remember(file) { mutableStateListOf<PictureEdit>() }
    var shown by remember(file) { mutableStateOf<Bitmap?>(null) }
    // The changes [shown] was drawn with; tools wait until it matches, so a tap never acts on an older picture.
    var shownFor by remember(file) { mutableStateOf<List<PictureEdit>?>(null) }
    var tool by remember(file) { mutableStateOf(EditTool.NONE) }
    // Lines drawn since the pen was picked up; they become one edit when the pen is put down.
    val strokes = remember(file) { mutableStateListOf<MarkStroke>() }
    var color by remember { mutableStateOf(markColors.first().first) }
    // The crop frame, in the shown picture's pixels.
    var cropRect by remember(file) { mutableStateOf(Rect.Zero) }
    var saving by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(file) {
        val loaded = withContext(Dispatchers.IO) {
            try { loadUpright(file) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
        }
        if (loaded == null) {
            Toast.makeText(context, R.string.attachment_edit_load_failed, Toast.LENGTH_SHORT).show()
            currentOnDismiss()
        } else {
            original = loaded
        }
    }
    // Each change draws the picture again from the opened one, so undo is only dropping the last change.
    val editsNow = edits.toList()
    LaunchedEffect(original, editsNow) {
        val base = original ?: return@LaunchedEffect
        // Already on screen (a change taken back after running out of memory): nothing to draw.
        if (shownFor == editsNow && shown != null) return@LaunchedEffect
        val drawn = withContext(Dispatchers.Default) {
            try { applyEdits(base, editsNow) } catch (e: OutOfMemoryError) { null }
        }
        if (drawn == null) {
            // Too big to change once more: back to the picture still on screen, as it was.
            Toast.makeText(context, R.string.attachment_edit_save_failed, Toast.LENGTH_SHORT).show()
            val good = shownFor.orEmpty()
            edits.clear()
            edits.addAll(good)
            return@LaunchedEffect
        }
        shown = drawn
        shownFor = editsNow
    }
    val ready = shown != null && shownFor == editsNow && !saving
    val changed = edits.isNotEmpty() || strokes.isNotEmpty()

    fun finishMark() {
        if (strokes.isNotEmpty()) edits.add(PictureEdit.Mark(strokes.toList()))
        strokes.clear()
        tool = EditTool.NONE
    }

    // Back and close leave a tool first (keeping the lines drawn), and ask before dropping changes.
    fun back() {
        when {
            saving -> Unit
            tool == EditTool.CROP -> tool = EditTool.NONE
            tool == EditTool.MARK -> finishMark()
            changed -> askDiscard = true
            else -> onDismiss()
        }
    }

    fun save() {
        val picture = shown ?: return
        val done = onEdited ?: return
        // Read now, not from the last frame: a second tap or a change just made must not slip through.
        if (saving || tool == EditTool.CROP || shownFor != edits.toList() || (edits.isEmpty() && strokes.isEmpty())) return
        // The picture on screen already has every change but the lines being drawn; those are added once.
        val pending = strokes.toList()
        saving = true
        scope.launch {
            // Written here and removed here however this ends; the taker copies it before returning.
            var out: File? = null
            try {
                val name = withContext(Dispatchers.IO) {
                    val final = if (pending.isEmpty()) picture else applyEdits(picture, listOf(PictureEdit.Mark(pending)))
                    val (written, shownName) = writeEdited(context, final, fileName, mimeType)
                    out = written
                    shownName
                }
                done(checkNotNull(out), name)
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e !is Exception && e !is OutOfMemoryError) throw e
                Toast.makeText(context, R.string.attachment_edit_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                out?.let { scratch ->
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { scratch.delete() }
                }
                saving = false
            }
        }
    }

    OrbitModalWindow(onDismissRequest = { back() }, dismissEnabled = !saving) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { back() }, enabled = !saving) {
                    Icon(orbitIcon("x"), stringResource(R.string.common_close), Modifier.size(24.dp), tint = Color.White)
                }
                Text(
                    fileName, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (onEdited != null) {
                    val canSave = changed && ready && tool != EditTool.CROP
                    TextButton(onClick = { save() }, enabled = canSave) {
                        Text(stringResource(R.string.done), color = if (canSave) Color.White else Color.White.copy(alpha = 0.4f))
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bitmap = shown
                if (bitmap != null) {
                    PictureCanvas(
                        bitmap = bitmap,
                        tool = if (ready) tool else EditTool.NONE,
                        strokes = strokes,
                        color = color,
                        cropRect = cropRect,
                        onCropChange = { cropRect = it },
                        onStroke = { strokes.add(it) },
                    )
                }
                if (bitmap == null || saving) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White, strokeWidth = 3.dp)
                }
            }
            if (onEdited != null) {
                when (tool) {
                    EditTool.NONE -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        EditorTool(orbitIcon("rotate-cw"), stringResource(R.string.attachment_edit_rotate), ready) {
                            edits.add(PictureEdit.Turn)
                        }
                        EditorTool(orbitIcon("crop"), stringResource(R.string.attachment_edit_crop), ready) {
                            val b = shown ?: return@EditorTool
                            cropRect = Rect(0f, 0f, b.width.toFloat(), b.height.toFloat())
                            tool = EditTool.CROP
                        }
                        EditorTool(orbitIcon("pencil"), stringResource(R.string.attachment_edit_mark), ready) {
                            tool = EditTool.MARK
                        }
                        EditorTool(orbitIcon("undo-2"), stringResource(R.string.attachment_edit_undo), ready && edits.isNotEmpty()) {
                            edits.removeAt(edits.lastIndex)
                        }
                    }
                    EditTool.CROP -> Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        EditorTool(orbitIcon("x"), stringResource(R.string.cancel), !saving) { tool = EditTool.NONE }
                        EditorTool(orbitIcon("rotate-ccw"), stringResource(R.string.attachment_edit_reset), ready) {
                            val b = shown ?: return@EditorTool
                            cropRect = Rect(0f, 0f, b.width.toFloat(), b.height.toFloat())
                        }
                        EditorTool(orbitIcon("check"), stringResource(R.string.confirm), ready) {
                            val b = shown
                            if (b != null) {
                                val left = cropRect.left.roundToInt().coerceIn(0, b.width - 1)
                                val top = cropRect.top.roundToInt().coerceIn(0, b.height - 1)
                                val width = cropRect.width.roundToInt().coerceIn(1, b.width - left)
                                val height = cropRect.height.roundToInt().coerceIn(1, b.height - top)
                                if (left != 0 || top != 0 || width != b.width || height != b.height) {
                                    edits.add(PictureEdit.Cut(left, top, width, height))
                                }
                            }
                            tool = EditTool.NONE
                        }
                    }
                    EditTool.MARK -> Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            markColors.forEach { (option, label) ->
                                ColorDot(option, stringResource(label), picked = option == color) { color = option }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            EditorTool(orbitIcon("x"), stringResource(R.string.cancel), !saving) {
                                strokes.clear()
                                tool = EditTool.NONE
                            }
                            EditorTool(orbitIcon("undo-2"), stringResource(R.string.attachment_edit_undo), strokes.isNotEmpty() && !saving) {
                                strokes.removeAt(strokes.lastIndex)
                            }
                            EditorTool(orbitIcon("check"), stringResource(R.string.confirm), !saving) { finishMark() }
                        }
                    }
                }
            }
        }
        if (askDiscard) {
            OrbitDialog(
                onDismissRequest = { askDiscard = false },
                title = stringResource(R.string.orbit_file_discard_title),
                confirmButton = {
                    OrbitDialogAction(destructive = true, onClick = { askDiscard = false; onDismiss() }) {
                        Text(stringResource(R.string.orbit_file_discard))
                    }
                },
                dismissButton = {
                    OrbitDialogAction(onClick = { askDiscard = false }, secondary = true) {
                        Text(stringResource(R.string.orbit_file_keep_editing))
                    }
                },
            )
        }
    }
}

/** The picture fitted to the space, with the lines being drawn or the crop frame over it. */
@Composable
private fun PictureCanvas(
    bitmap: Bitmap,
    tool: EditTool,
    strokes: List<MarkStroke>,
    color: Color,
    cropRect: Rect,
    onCropChange: (Rect) -> Unit,
    onStroke: (MarkStroke) -> Unit,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    // The line under the finger: its points for keeping, its path grown point by point for drawing.
    val livePoints = remember(bitmap) { ArrayList<Offset>() }
    val livePath = remember(bitmap) { Path() }
    var liveVersion by remember(bitmap) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val handleReach = with(density) { 32.dp.toPx() }
    val minSide = with(density) { 48.dp.toPx() }
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    // Where the picture sits on screen: its scale and top left corner.
    val scale = if (canvasSize.width > 0f) min(canvasSize.width / bitmap.width, canvasSize.height / bitmap.height) else 1f
    val origin = Offset((canvasSize.width - bitmap.width * scale) / 2f, (canvasSize.height - bitmap.height * scale) / 2f)
    val currentCrop by rememberUpdatedState(cropRect)
    val currentOnCropChange by rememberUpdatedState(onCropChange)
    val currentOnStroke by rememberUpdatedState(onStroke)
    val currentColor by rememberUpdatedState(color)
    val currentScale by rememberUpdatedState(scale)
    val currentOrigin by rememberUpdatedState(origin)
    val gestures = when (tool) {
        EditTool.MARK -> Modifier.pointerInput(bitmap, tool) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val s = currentScale
                val o = currentOrigin
                fun picturePoint(at: Offset) = Offset((at.x - o.x) / s, (at.y - o.y) / s)
                val start = picturePoint(down.position)
                // A line starts on the picture; one leaving it is cut at its edge when drawn.
                if (start.x < 0f || start.y < 0f || start.x > bitmap.width || start.y > bitmap.height) return@awaitEachGesture
                down.consume()
                try {
                    livePoints.clear()
                    livePoints.add(start)
                    livePath.reset()
                    livePath.moveTo(start.x, start.y)
                    livePath.lineTo(start.x + 0.1f, start.y)
                    liveVersion++
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val point = picturePoint(change.position)
                        livePoints.add(point)
                        livePath.lineTo(point.x, point.y)
                        liveVersion++
                        change.consume()
                    }
                    currentOnStroke(MarkStroke(currentColor, markWidth(bitmap), livePoints.toList()))
                } finally {
                    // Also when the pen is put down mid-line: an unfinished line is not left showing.
                    livePoints.clear()
                    livePath.reset()
                    liveVersion++
                }
            }
        }
        EditTool.CROP -> Modifier.pointerInput(bitmap, tool) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val s = currentScale
                val o = currentOrigin
                // Kept here as it changes, so several moves within one frame all count.
                var r = currentCrop
                val frame = Rect(o.x + r.left * s, o.y + r.top * s, o.x + r.right * s, o.y + r.bottom * s)
                val corners = listOf(frame.topLeft, frame.topRight, frame.bottomRight, frame.bottomLeft)
                // A small frame keeps room in its middle for moving it.
                val reach = min(handleReach, min(frame.width, frame.height) / 3f)
                val nearest = corners.indices.minBy { hypot(corners[it].x - down.position.x, corners[it].y - down.position.y) }
                // 0-3: a corner (top left, top right, bottom right, bottom left); 4: the whole frame.
                val grabbed = when {
                    hypot(corners[nearest].x - down.position.x, corners[nearest].y - down.position.y) <= max(reach, 12f) -> nearest
                    frame.contains(down.position) -> 4
                    else -> return@awaitEachGesture
                }
                down.consume()
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                // The smallest frame, each way on its own, so a long thin picture can still be cut short.
                val leastX = min(minSide / s, w / 4f)
                val leastY = min(minSide / s, h / 4f)
                var last = down.position
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dx = (change.position.x - last.x) / s
                    val dy = (change.position.y - last.y) / s
                    last = change.position
                    change.consume()
                    // A frame already smaller than the least (the window narrowed since) cannot
                    // shrink further but can still grow; every range stays the right way round.
                    val leftMost = max(r.right - leastX, r.left)
                    val topMost = max(r.bottom - leastY, r.top)
                    val rightLeast = min(r.left + leastX, r.right)
                    val bottomLeast = min(r.top + leastY, r.bottom)
                    r = when (grabbed) {
                        4 -> r.translate(dx.coerceIn(-r.left, w - r.right), dy.coerceIn(-r.top, h - r.bottom))
                        0 -> r.copy(left = (r.left + dx).coerceIn(0f, leftMost), top = (r.top + dy).coerceIn(0f, topMost))
                        1 -> r.copy(right = (r.right + dx).coerceIn(rightLeast, w), top = (r.top + dy).coerceIn(0f, topMost))
                        2 -> r.copy(right = (r.right + dx).coerceIn(rightLeast, w), bottom = (r.bottom + dy).coerceIn(bottomLeast, h))
                        else -> r.copy(left = (r.left + dx).coerceIn(0f, leftMost), bottom = (r.bottom + dy).coerceIn(bottomLeast, h))
                    }
                    currentOnCropChange(r)
                }
            }
        }
        EditTool.NONE -> Modifier
    }
    val cropLabel = if (tool == EditTool.CROP) {
        stringResource(
            R.string.attachment_edit_crop_frame,
            (cropRect.width / bitmap.width * 100).roundToInt(),
            (cropRect.height / bitmap.height * 100).roundToInt(),
            (cropRect.left / bitmap.width * 100).roundToInt(),
            (cropRect.top / bitmap.height * 100).roundToInt(),
        )
    } else null
    // For screen readers: the frame moves and resizes by a tenth of the picture a step.
    val cropActions = if (tool == EditTool.CROP) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val stepX = w / 10f
        val stepY = h / 10f
        val leastX = min(minSide / scale, w / 4f)
        val leastY = min(minSide / scale, h / 4f)
        // An action that would not move the frame (already at the edge or the least size) says so.
        fun act(label: String, change: (Rect) -> Rect) = CustomAccessibilityAction(label) {
            val next = change(currentCrop)
            if (next == currentCrop) false else { currentOnCropChange(next); true }
        }
        fun move(r: Rect, dx: Float, dy: Float) =
            r.translate(dx.coerceIn(-r.left, w - r.right), dy.coerceIn(-r.top, h - r.bottom))
        listOf(
            act(stringResource(R.string.attachment_edit_crop_smaller)) { r ->
                val insetX = max(0f, min(stepX / 2f, (r.width - leastX) / 2f))
                val insetY = max(0f, min(stepY / 2f, (r.height - leastY) / 2f))
                Rect(r.left + insetX, r.top + insetY, r.right - insetX, r.bottom - insetY)
            },
            act(stringResource(R.string.attachment_edit_crop_larger)) { r ->
                Rect(
                    max(0f, r.left - stepX / 2f), max(0f, r.top - stepY / 2f),
                    min(w, r.right + stepX / 2f), min(h, r.bottom + stepY / 2f),
                )
            },
            act(stringResource(R.string.attachment_edit_crop_left)) { move(it, -stepX, 0f) },
            act(stringResource(R.string.attachment_edit_crop_right)) { move(it, stepX, 0f) },
            act(stringResource(R.string.attachment_edit_crop_up)) { move(it, 0f, -stepY) },
            act(stringResource(R.string.attachment_edit_crop_down)) { move(it, 0f, stepY) },
        )
    } else null
    Canvas(
        Modifier.fillMaxSize().padding(12.dp)
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (cropLabel != null && cropActions != null) {
                    Modifier.semantics { contentDescription = cropLabel; customActions = cropActions }
                } else Modifier
            )
            .then(gestures)
    ) {
        val pic = Rect(origin, Size(bitmap.width * scale, bitmap.height * scale))
        drawImage(
            image,
            dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
            dstSize = IntSize(pic.width.roundToInt(), pic.height.roundToInt()),
        )
        // Lines are drawn in the picture's own pixels, cut at its edges.
        liveVersion.let { }
        clipRect(pic.left, pic.top, pic.right, pic.bottom) {
            withTransform({ translate(origin.x, origin.y); scale(scale, scale, Offset.Zero) }) {
                strokes.forEach {
                    drawPath(it.path, it.color, style = Stroke(it.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (tool == EditTool.MARK && livePoints.isNotEmpty()) {
                    drawPath(livePath, color, style = Stroke(markWidth(bitmap), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (tool == EditTool.CROP) {
            val frame = Rect(
                origin.x + cropRect.left * scale, origin.y + cropRect.top * scale,
                origin.x + cropRect.right * scale, origin.y + cropRect.bottom * scale,
            )
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(pic.left, pic.top), Size(pic.width, frame.top - pic.top))
            drawRect(shade, Offset(pic.left, frame.bottom), Size(pic.width, pic.bottom - frame.bottom))
            drawRect(shade, Offset(pic.left, frame.top), Size(frame.left - pic.left, frame.height))
            drawRect(shade, Offset(frame.right, frame.top), Size(pic.right - frame.right, frame.height))
            // White lines with a thin dark edge, so they show on light pictures too.
            val edge = Color.Black.copy(alpha = 0.45f)
            drawRect(edge, frame.topLeft, frame.size, style = Stroke(4.dp.toPx()))
            drawRect(Color.White, frame.topLeft, frame.size, style = Stroke(2.dp.toPx()))
            // Thirds, to line things up.
            for (i in 1..2) {
                val x = frame.left + frame.width * i / 3f
                val y = frame.top + frame.height * i / 3f
                drawLine(edge, Offset(x, frame.top), Offset(x, frame.bottom), 2.dp.toPx())
                drawLine(edge, Offset(frame.left, y), Offset(frame.right, y), 2.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.7f), Offset(x, frame.top), Offset(x, frame.bottom), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.7f), Offset(frame.left, y), Offset(frame.right, y), 1.dp.toPx())
            }
            val arm = min(18.dp.toPx(), min(frame.width, frame.height) / 2f)
            val thick = 4.dp.toPx()
            listOf(
                frame.topLeft to Offset(1f, 1f), frame.topRight to Offset(-1f, 1f),
                frame.bottomRight to Offset(-1f, -1f), frame.bottomLeft to Offset(1f, -1f),
            ).forEach { (corner, dir) ->
                drawLine(edge, corner, Offset(corner.x + arm * dir.x, corner.y), thick + 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(edge, corner, Offset(corner.x, corner.y + arm * dir.y), thick + 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Color.White, corner, Offset(corner.x + arm * dir.x, corner.y), thick, cap = StrokeCap.Round)
                drawLine(Color.White, corner, Offset(corner.x, corner.y + arm * dir.y), thick, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun EditorTool(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

/** A pen color: a small dot in a finger-sized place; the chosen one carries a tick. */
@Composable
private fun ColorDot(option: Color, name: String, picked: Boolean, onPick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(onClick = onPick)
            .semantics { contentDescription = name; role = Role.RadioButton; selected = picked },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(option)
                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (picked) {
                Icon(orbitIcon("check"), null, Modifier.size(18.dp),
                    tint = if (option.luminance() > 0.5f) Color.Black else Color.White)
            }
        }
    }
}

/** The picture upright (camera pictures carry their turn separately) and no bigger than [EDIT_MAX_SIDE]. */
private fun loadUpright(file: File): Bitmap? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > EDIT_MAX_SIDE) {
                val ratio = EDIT_MAX_SIDE.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while ((max(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > EDIT_MAX_SIDE) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val orientation = runCatching {
        android.media.ExifInterface(file.path).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)
    // Every way a camera may store a picture: turned, mirrored, or both.
    val matrix = Matrix()
    when (orientation) {
        android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        android.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        android.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        android.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return decoded
    }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}

/** [base] with [edits] applied in order. The pictures in between are left to the collector, as one may be on screen. */
private fun applyEdits(base: Bitmap, edits: List<PictureEdit>): Bitmap =
    edits.fold(base) { picture, edit ->
        when (edit) {
            PictureEdit.Turn -> Bitmap.createBitmap(
                picture, 0, 0, picture.width, picture.height, Matrix().apply { postRotate(90f) }, true,
            )
            is PictureEdit.Cut -> {
                val left = edit.left.coerceIn(0, picture.width - 1)
                val top = edit.top.coerceIn(0, picture.height - 1)
                Bitmap.createBitmap(
                    picture, left, top,
                    edit.width.coerceIn(1, picture.width - left), edit.height.coerceIn(1, picture.height - top),
                )
            }
            is PictureEdit.Mark -> {
                val copy = picture.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = android.graphics.Canvas(copy)
                edit.strokes.forEach { stroke ->
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = stroke.color.toArgb()
                        style = Paint.Style.STROKE
                        strokeWidth = stroke.width
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    val path = android.graphics.Path()
                    stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    if (stroke.points.size == 1) path.lineTo(stroke.points[0].x + 0.1f, stroke.points[0].y)
                    canvas.drawPath(path, paint)
                }
                copy
            }
        }
    }

/**
 * Writes the edited picture to the app's cache and returns it with the name to show. A PNG (by
 * type or name) stays a PNG, as it may be see-through; anything else becomes a JPEG on white. The
 * name keeps its ending when that already fits, and gets the right one otherwise.
 */
private fun writeEdited(context: Context, picture: Bitmap, fileName: String, mimeType: String): Pair<File, String> {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val png = ext == "png" || mimeType.equals("image/png", ignoreCase = true)
    val base = if (fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
    val name = when {
        png -> if (ext == "png") fileName else "$base.png"
        ext == "jpg" || ext == "jpeg" -> fileName
        else -> "$base.jpg"
    }
    val dir = File(context.cacheDir, "attachment_edits").apply { mkdirs() }
    val out = File(dir, java.util.UUID.randomUUID().toString() + if (png) ".png" else ".jpg")
    val solid = if (png || !picture.hasAlpha()) picture else
        Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(picture, 0f, 0f, null) }
        }
    try {
        out.outputStream().use { stream ->
            check(solid.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, stream)) { "Could not write the picture" }
        }
    } catch (e: Throwable) {
        out.delete()
        throw e
    }
    return out to name
}

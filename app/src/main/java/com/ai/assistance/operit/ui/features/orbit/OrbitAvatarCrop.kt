package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the picture sits in the round frame: its scale and how far its center is from the frame's. */
internal data class OrbitAvatarCropPlacement(val scale: Float, val offsetX: Float, val offsetY: Float)

/**
 * The round frame's math, kept apart from the screen. Scale goes from "the whole picture fits in
 * the circle" to well past "the picture fills the circle"; the picture moves until its edge meets
 * the frame's edge, from inside (zoomed out) or from outside (zoomed in).
 */
internal object OrbitAvatarCropMath {
    const val OUTPUT_PX = 512
    private const val MAX_ZOOM_OVER_FILL = 6f

    /** The whole picture inside the circle: its diagonal is the circle's diameter. */
    fun fitScale(width: Int, height: Int, frame: Float) = frame / hypot(width.toFloat(), height.toFloat())
    fun fillScale(width: Int, height: Int, frame: Float) = frame / min(width, height)
    fun maxScale(width: Int, height: Int, frame: Float) = fillScale(width, height, frame) * MAX_ZOOM_OVER_FILL

    fun clamp(width: Int, height: Int, frame: Float, scale: Float, offsetX: Float, offsetY: Float): OrbitAvatarCropPlacement {
        val s = scale.coerceIn(fitScale(width, height, frame), maxScale(width, height, frame))
        val limitX = abs(width * s - frame) / 2f
        val limitY = abs(height * s - frame) / 2f
        return OrbitAvatarCropPlacement(s, offsetX.coerceIn(-limitX, limitX), offsetY.coerceIn(-limitY, limitY))
    }

    /** Pinch around the centroid (measured from the frame's center), then pan. */
    fun transform(
        width: Int, height: Int, frame: Float, current: OrbitAvatarCropPlacement,
        zoom: Float, centroidX: Float, centroidY: Float, panX: Float, panY: Float,
    ): OrbitAvatarCropPlacement {
        val target = (current.scale * zoom)
            .coerceIn(fitScale(width, height, frame), maxScale(width, height, frame))
        val ratio = target / current.scale
        return clamp(
            width, height, frame, target,
            centroidX + (current.offsetX - centroidX) * ratio + panX,
            centroidY + (current.offsetY - centroidY) * ratio + panY,
        )
    }
}

/**
 * Crops a picture for Orbit's round avatar before it goes to the chat. Pinch to zoom and drag to
 * move; zooming out is allowed until the whole picture fits, and the rest of the circle stays clear
 * (the avatar's white shows there). [onCropped] gets a square PNG and runs while the window still
 * shows it is saving; back or the close button cancels.
 */
@Composable
internal fun OrbitAvatarCropWindow(source: Uri, onCropped: suspend (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentOnCropped by rememberUpdatedState(onCropped)
    var image by remember(source) { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun failed() = android.widget.Toast.makeText(
        context, context.getString(R.string.orbit_avatar_save_failed), android.widget.Toast.LENGTH_SHORT,
    ).show()
    LaunchedEffect(source) {
        val loaded = withContext(Dispatchers.IO) { runCatching { loadForCrop(context, source) }.getOrNull() }
        if (loaded == null) {
            failed()
            currentOnDismiss()
        } else {
            image = loaded
        }
    }
    OrbitModalWindow(onDismissRequest = onDismiss, dismissEnabled = !saving) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            val bitmap = image
            if (bitmap == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), strokeWidth = 3.dp)
            } else {
                CropArea(bitmap, saving) { placement, frame ->
                    if (!saving) {
                        saving = true
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching { renderAvatar(context, bitmap, placement, frame) }.getOrNull()
                            }
                            try {
                                if (file == null) failed() else currentOnCropped(file)
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            }
            OrbitCircleButton(
                icon = orbitIcon("x"),
                contentDescription = stringResource(R.string.common_close),
                onClick = { if (!saving) onDismiss() },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp),
            )
            Text(
                stringResource(R.string.orbit_avatar_crop_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 30.dp),
            )
        }
    }
}

@Composable
private fun CropArea(bitmap: Bitmap, saving: Boolean, onDone: (OrbitAvatarCropPlacement, Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    val w = bitmap.width
    val h = bitmap.height
    // Gestures run in long-lived coroutines: they read the latest value, so nothing moves while saving.
    val savingNow by rememberUpdatedState(saving)
    // The free space between the title and the buttons; the circle fits it in portrait and landscape.
    var area by remember { mutableStateOf(IntSize.Zero) }
    val frame = with(density) {
        minOf(area.width - 48.dp.toPx(), area.height - 24.dp.toPx(), 360.dp.toPx())
    }
    val frameCenter = Offset(area.width / 2f, area.height / 2f)
    val ready = frame > 0f
    val fit = if (ready) OrbitAvatarCropMath.fitScale(w, h, frame) else 0f
    val fill = if (ready) OrbitAvatarCropMath.fillScale(w, h, frame) else 0f
    // Opens filled, like the avatar shows a picture by default; zooming out shows more of it.
    var scale by remember(bitmap, frame) { mutableFloatStateOf(fill) }
    var offsetX by remember(bitmap, frame) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap, frame) { mutableFloatStateOf(0f) }
    val showingWhole = scale <= fit * 1.001f
    fun place(target: Float) {
        scale = target
        offsetX = 0f
        offsetY = 0f
    }
    val hint = stringResource(R.string.orbit_avatar_crop_hint)
    Column(Modifier.fillMaxSize()) {
        // Room for the close button and the title drawn over this window.
        Spacer(Modifier.statusBarsPadding().height(76.dp))
        Canvas(
            Modifier.fillMaxWidth().weight(1f).clipToBounds()
                .onSizeChanged { area = it }
                .semantics { contentDescription = hint }
                .pointerInput(bitmap, frame, frameCenter) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (savingNow || !ready) return@detectTransformGestures
                        val next = OrbitAvatarCropMath.transform(
                            w, h, frame, OrbitAvatarCropPlacement(scale, offsetX, offsetY),
                            zoom, centroid.x - frameCenter.x, centroid.y - frameCenter.y, pan.x, pan.y,
                        )
                        scale = next.scale
                        offsetX = next.offsetX
                        offsetY = next.offsetY
                    }
                }
                .pointerInput(bitmap, frame) {
                    // Double tap switches between the whole picture and a filled circle.
                    detectTapGestures(onDoubleTap = {
                        if (!savingNow && ready) place(if (scale <= fit * 1.001f) fill else fit)
                    })
                },
        ) {
            if (!ready) return@Canvas
            val radius = frame / 2f
            drawCircle(Color.White, radius, frameCenter)
            val dw = w * scale
            val dh = h * scale
            drawImage(
                imageBitmap,
                dstOffset = IntOffset(
                    (frameCenter.x + offsetX - dw / 2f).roundToInt(),
                    (frameCenter.y + offsetY - dh / 2f).roundToInt(),
                ),
                dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.High,
            )
            // Everything outside the circle is veiled, so it is clear what the avatar will show.
            val veil = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(Rect(frameCenter, radius))
            }
            drawPath(veil, colors.background.copy(alpha = 0.86f))
            drawCircle(colors.outlineVariant, radius, frameCenter, style = Stroke(width = 1.5.dp.toPx()))
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Surface(
                onClick = { if (!saving && ready) place(if (showingWhole) fill else fit) },
                shape = CircleShape,
                color = colors.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        orbitIcon(if (showingWhole) "maximize-2" else "minimize-2"),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(
                            if (showingWhole) R.string.orbit_avatar_crop_fill else R.string.orbit_avatar_crop_whole,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            // The same full-width pill as the app's other confirm buttons.
            Button(
                onClick = { onDone(OrbitAvatarCropPlacement(scale, offsetX, offsetY), frame) },
                enabled = !saving && ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
            ) {
                Text(stringResource(if (saving) R.string.orbit_avatar_crop_saving else R.string.orbit_avatar_crop_done))
            }
        }
    }
}

/** Reads the picture upright and no bigger than a sharp avatar needs. */
private fun loadForCrop(context: Context, uri: Uri): Bitmap? {
    val maxSide = 2048
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > maxSide) {
                val ratio = maxSide.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    // Each step halves the sides (rounding up); stop once the longest one fits the limit.
    while ((max(bounds.outWidth, bounds.outHeight) + sample - 1) / sample > maxSide) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: return null
    val degrees = context.contentResolver.openInputStream(uri)?.use {
        when (android.media.ExifInterface(it).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    } ?: 0f
    if (degrees == 0f) return decoded
    val rotated = Bitmap.createBitmap(
        decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true,
    )
    if (rotated !== decoded) decoded.recycle()
    return rotated
}

/** Draws what the circle shows into a square PNG; the part with no picture stays clear. */
private fun renderAvatar(context: Context, bitmap: Bitmap, placement: OrbitAvatarCropPlacement, frame: Float): File {
    val out = OrbitAvatarCropMath.OUTPUT_PX
    val k = out / frame
    val result = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
    try {
        val drawScale = placement.scale * k
        val matrix = Matrix().apply {
            postScale(drawScale, drawScale)
            postTranslate(
                out / 2f + placement.offsetX * k - bitmap.width * drawScale / 2f,
                out / 2f + placement.offsetY * k - bitmap.height * drawScale / 2f,
            )
        }
        android.graphics.Canvas(result).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val file = File(context.filesDir, "orbit_avatar_request_${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { check(result.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file
    } finally {
        result.recycle()
    }
}

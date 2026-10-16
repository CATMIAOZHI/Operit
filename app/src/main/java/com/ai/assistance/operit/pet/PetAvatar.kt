package com.ai.assistance.operit.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a pet's resting picture into a still image that can be used as an avatar. */
internal object PetAvatar {
    private const val CELL_WIDTH = 192
    private const val CELL_HEIGHT = 208

    /** A small still for showing the pet in a picker, or null when it cannot be read. */
    suspend fun preview(context: Context, pet: PetProfile): androidx.compose.ui.graphics.ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching { still(context, pet.settings) }.getOrNull()?.let { bitmap ->
                val side = 192
                val scaled = if (bitmap.width > side * 2 || bitmap.height > side * 2) {
                    val ratio = side.toFloat() / maxOf(bitmap.width, bitmap.height)
                    Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1), true).also { bitmap.recycle() }
                } else bitmap
                scaled.asImageBitmap()
            }
        }

    /** Writes the still as `<prefix><uuid>.png` in the app's files and returns it, or null. */
    suspend fun export(context: Context, pet: PetProfile, prefix: String): File? = withContext(Dispatchers.IO) {
        val bitmap = runCatching { still(context, pet.settings) }.getOrNull() ?: return@withContext null
        val file = File(context.filesDir, "$prefix${UUID.randomUUID()}.png")
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        } catch (error: Exception) {
            file.delete()
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun still(context: Context, settings: PetSettings): Bitmap? {
        val id = settings.artwork(PetAnimation.IDLE)?.id.orEmpty()
        if (settings.mediaType == PetMediaType.ATLAS) {
            val stream = if (id.isEmpty()) {
                context.assets.open("pets/shuiqingmiao/spritesheet.webp")
            } else {
                PetAssets.assetFile(context, id).inputStream()
            }
            // The first cell of the first row is the resting pose; decode only that cell, not the sheet.
            return stream.use {
                @Suppress("DEPRECATION")
                val decoder = BitmapRegionDecoder.newInstance(it, false) ?: return null
                try {
                    decoder.decodeRegion(
                        Rect(0, 0, CELL_WIDTH.coerceAtMost(decoder.width), CELL_HEIGHT.coerceAtMost(decoder.height)),
                        null,
                    )
                } finally {
                    decoder.recycle()
                }
            }
        }
        if (id.isEmpty()) return null
        val file = PetAssets.assetFile(context, id)
        if (settings.mediaType == PetMediaType.VIDEO) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.path)
                retriever.getFrameAtTime(0)
            } finally {
                retriever.release()
            }
        }
        // PNG/WebP, or the first frame of a GIF, kept small: an avatar needs only a few hundred pixels.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

package com.ai.assistance.operit.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns a pet's resting picture into a still image that can be used as an avatar. */
internal object PetAvatar {
    private const val CELL_WIDTH = 192
    private const val CELL_HEIGHT = 208

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
            val atlas = if (id.isEmpty()) {
                context.assets.open("pets/shuiqingmiao/spritesheet.webp").use(BitmapFactory::decodeStream)
            } else {
                BitmapFactory.decodeFile(PetAssets.assetFile(context, id).path)
            } ?: return null
            // The first cell of the first row is the resting pose.
            return try {
                Bitmap.createBitmap(atlas, 0, 0, CELL_WIDTH.coerceAtMost(atlas.width), CELL_HEIGHT.coerceAtMost(atlas.height))
            } finally {
                atlas.recycle()
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

package com.ai.assistance.operit.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ImportedPet(val id: String, val name: String, val type: PetMediaType)
internal data class PetImage(val bitmap: ImageBitmap, val atlas: Boolean)

internal object PetAssets {
    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One current custom image plus the built-in; no unbounded bitmap catalogue.
    private var cachedId: String? = null
    private var cached: PetImage? = null
    private var builtIn: PetImage? = null

    suspend fun load(context: Context, id: String, atlas: Boolean): PetImage = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (id.isEmpty()) {
                builtIn ?: context.assets.open("pets/shuiqingmiao/spritesheet.webp").use {
                    PetImage(BitmapFactory.decodeStream(it).asImageBitmap(), true).also { builtIn = it }
                }
            } else {
                if (id == cachedId && cached != null) return@withLock cached!!
                val image = decode(assetFile(context, id), atlas)
                cachedId = id
                cached = image
                image
            }
        }
    }

    suspend fun importPet(context: Context, uri: Uri, expectedType: PetMediaType): ImportedPet {
        val id = UUID.randomUUID().toString()
        try {
            return importPetFile(context, uri, id, expectedType)
        } catch (cancelled: CancellationException) {
            // withContext may discard a successful IO result when the settings page leaves.
            // In that case no list entry owns this file yet.
            withContext(NonCancellable) { delete(context, id) }
            throw cancelled
        }
    }

    private suspend fun importPetFile(context: Context, uri: Uri, id: String, expectedType: PetMediaType): ImportedPet = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = File(context.filesDir, "pet_companion").apply { mkdirs() }
            val source = File.createTempFile("import-", ".tmp", directory)
            val imageFile = assetFile(context, id)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { copyPetBytes(input, it) }
                } ?: throw PetImportException(PetImportException.Reason.INVALID_IMAGE)
                val signature = ByteArray(12)
                source.inputStream().use { it.read(signature) }
                val isZip = signature[0] == 0x50.toByte() && signature[1] == 0x4b.toByte()
                val providerName = context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }.orEmpty().substringBeforeLast('.').take(80)
                val name = if (isZip) {
                    imageFile.outputStream().use { readPetPack(source, it) }.ifBlank { providerName }
                } else {
                    source.inputStream().use { input -> imageFile.outputStream().use { copyPetBytes(input, it) } }
                    providerName
                }
                val type = when {
                    isZip -> PetMediaType.ATLAS
                    signature.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> PetMediaType.VIDEO
                    signature.copyOfRange(0, 3).toString(Charsets.US_ASCII) == "GIF" -> PetMediaType.GIF
                    else -> PetMediaType.IMAGE
                }
                if (type != expectedType) throw PetImportException(PetImportException.Reason.FORMAT_MISMATCH)
                // Validate the real media before adding it to the library.
                if (type == PetMediaType.VIDEO) {
                    validateVideo(imageFile)
                } else {
                    val decoded = decode(imageFile, isZip, allowGif = type == PetMediaType.GIF)
                    if (type != PetMediaType.GIF) {
                        cachedId = id
                        cached = decoded
                    }
                }
                ImportedPet(id, name, type)
            } catch (error: Exception) {
                imageFile.delete()
                throw error
            } finally {
                source.delete()
            }
        }
    }

    fun assetFile(context: Context, id: String): File {
        // IDs are generated by us, never taken from pet.json or the document provider.
        require(UUID.fromString(id).toString() == id)
        return File(File(context.filesDir, "pet_companion"), "$id.img")
    }

    fun deleteAfterRemoval(context: Context, id: String) {
        val application = context.applicationContext
        cleanupScope.launch { delete(application, id) }
    }

    private suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        if (id.isEmpty()) return@withContext
        mutex.withLock {
            assetFile(context, id).delete()
            if (cachedId == id) { cachedId = null; cached = null }
        }
    }

    private fun validateVideo(file: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0 || width.toLong() * height > 16_777_216L) {
                throw PetImportException(PetImportException.Reason.INVALID_VIDEO)
            }
            val frame = retriever.getFrameAtTime(0)
                ?: throw PetImportException(PetImportException.Reason.INVALID_VIDEO)
            frame.recycle()
        } catch (error: Exception) {
            throw PetImportException(PetImportException.Reason.INVALID_VIDEO)
        } finally {
            retriever.release()
        }
    }

    private fun decode(file: File, atlas: Boolean, allowGif: Boolean = false): PetImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val pixels = bounds.outWidth.toLong() * bounds.outHeight
        if ((bounds.outMimeType !in setOf("image/png", "image/webp") && !(allowGif && bounds.outMimeType == "image/gif")) ||
            bounds.outWidth <= 0 || bounds.outHeight <= 0 || pixels > 16_777_216L ||
            (atlas && (bounds.outWidth != 1536 || bounds.outHeight != 2288))
        ) throw PetImportException(PetImportException.Reason.INVALID_IMAGE)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        if (!atlas) {
            while (bounds.outWidth / options.inSampleSize.coerceAtLeast(1) > 1024 ||
                bounds.outHeight / options.inSampleSize.coerceAtLeast(1) > 1024
            ) {
                options.inSampleSize = options.inSampleSize.coerceAtLeast(1) * 2
            }
        }
        val bitmap = BitmapFactory.decodeFile(file.path, options)
            ?: throw PetImportException(PetImportException.Reason.INVALID_IMAGE)
        return PetImage(bitmap.asImageBitmap(), atlas)
    }
}

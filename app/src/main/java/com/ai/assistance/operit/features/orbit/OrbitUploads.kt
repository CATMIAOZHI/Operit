package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The files the user sends in an Orbit chat (pictures, documents, recordings). Other chats keep
 * attachments in a folder that is emptied when the app closes; an Orbit keeps them in its own
 * folder, `orbit/<UUID>/uploads/<time>-<id>/<name>`, so its chat still shows them after a restart
 * and the Builds tab can list them. Together they stay under [LIMIT_BYTES]: past it the oldest
 * go first. Deleting the Orbit deletes them with the rest of its folder.
 */
object OrbitUploads {
    /** 5 GB for one Orbit's uploads. */
    const val LIMIT_BYTES = 5L * 1024 * 1024 * 1024
    private const val FOLDER = "uploads"
    /** Uploads still being copied live here, outside the listed folders, so trimming never sees them. */
    private const val INCOMING = ".incoming"
    /** A copy left in [INCOMING] this long ago was cut off by the app closing. */
    private const val STALE_INCOMING_MS = 24L * 60 * 60 * 1000
    private val FOLDER_NAME = Regex("(\\d{1,15})-[0-9a-f]{8}")
    private val lock = Any()

    data class Upload(val file: File, val name: String, val size: Long, val addedAt: Long)

    /** The file is bigger than the whole Library can hold. */
    class TooLargeException : java.io.IOException("The file is larger than the Orbit Library limit")

    fun root(context: Context, orbitId: String): File =
        File(OrbitPreferences.getInstance(context).orbitDir(orbitId), FOLDER)

    /** The Orbit whose chat [chatId] is, or null for an ordinary chat. Reads settings; call off the main thread. */
    fun orbitFor(context: Context, chatId: String?): String? =
        chatId?.let { OrbitPreferences.getInstance(context).loadedSettingsBlocking().ownerForChat(it)?.id }

    /**
     * Saves one upload and makes room under the limit, the newest file always kept. Blocking IO.
     * The copy is made in [INCOMING] and only moved into the Library when complete, so another
     * upload finishing meanwhile cannot trim it. Returns null (and keeps nothing) when the stream
     * was empty; throws [TooLargeException] for a file over [LIMIT_BYTES], keeping nothing.
     */
    fun save(context: Context, orbitId: String, name: String, input: InputStream): File? {
        val root = root(context, orbitId)
        val now = System.currentTimeMillis()
        val id = "$now-" + UUID.randomUUID().toString().replace("-", "").take(8)
        val incoming = File(File(root, INCOMING), id)
        check(incoming.mkdirs()) { "Could not make the upload folder" }
        val fileName = safeName(name)
        val partial = File(incoming, fileName)
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > LIMIT_BYTES) throw TooLargeException()
                    output.write(buffer, 0, read)
                }
            }
        } catch (e: Exception) {
            incoming.deleteRecursively()
            throw e
        }
        if (partial.length() <= 0) {
            incoming.deleteRecursively()
            return null
        }
        val folder = File(root, id)
        synchronized(lock) {
            if (!incoming.renameTo(folder)) {
                incoming.deleteRecursively()
                error("Could not keep the upload")
            }
            trim(root, keep = folder)
        }
        return File(folder, fileName)
    }

    /** Every upload, newest first. */
    suspend fun list(context: Context, orbitId: String): List<Upload> = withContext(Dispatchers.IO) {
        synchronized(lock) { uploads(root(context, orbitId)) }.sortedByDescending { it.addedAt }
    }

    suspend fun delete(context: Context, orbitId: String, upload: Upload) = withContext(Dispatchers.IO) {
        synchronized(lock) { folderOf(root(context, orbitId), upload.file)?.deleteRecursively() }
        Unit
    }

    /**
     * Throws away an upload the user took out of the input box before sending. Any other path is
     * left alone. Blocking IO.
     */
    fun discard(context: Context, path: String) {
        val file = File(path)
        val orbits = File(context.filesDir, "orbit").canonicalFile
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return
        // orbit/<UUID>/uploads/<folder>/<name>
        val folder = canonical.parentFile ?: return
        val uploads = folder.parentFile ?: return
        if (uploads.name != FOLDER || uploads.parentFile?.parentFile != orbits || !FOLDER_NAME.matches(folder.name)) return
        synchronized(lock) { folder.deleteRecursively() }
    }

    private fun uploads(root: File): List<Upload> =
        root.listFiles()?.mapNotNull { folder ->
            val time = FOLDER_NAME.matchEntire(folder.name)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
            val file = folder.listFiles()?.firstOrNull { it.isFile } ?: return@mapNotNull null
            Upload(file, file.name, file.length(), time)
        }.orEmpty()

    private fun folderOf(root: File, file: File): File? {
        val folder = file.canonicalFile.parentFile ?: return null
        return folder.takeIf { it.parentFile == root.canonicalFile && FOLDER_NAME.matches(it.name) }
    }

    /** Deletes the oldest uploads until the rest fit under the limit; [keep] is never deleted. */
    private fun trim(root: File, keep: File) {
        // Copies cut off by the app closing would otherwise stay forever.
        val cutoff = System.currentTimeMillis() - STALE_INCOMING_MS
        File(root, INCOMING).listFiles()?.forEach { folder ->
            val time = FOLDER_NAME.matchEntire(folder.name)?.groupValues?.get(1)?.toLongOrNull()
            if (time != null && time < cutoff) folder.deleteRecursively()
        }
        val all = uploads(root).sortedBy { it.addedAt }
        var total = all.sumOf { it.size }
        for (upload in all) {
            if (total <= LIMIT_BYTES) break
            val folder = upload.file.parentFile ?: continue
            if (folder == keep) continue
            if (folder.deleteRecursively()) total -= upload.size
        }
    }

    private fun safeName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.')
        val ext = clean.substringAfterLast('.', "").take(16)
        val base = clean.substringBeforeLast('.', clean).take(100)
        val result = if (ext.isEmpty() || ext == clean) base else "$base.$ext"
        return result.ifBlank { "file" }
    }
}

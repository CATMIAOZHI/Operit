package com.ai.assistance.operit.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DownloadResource(
    val id: String,
    val url: String,
    val bytes: Long,
    val sha256: String
)

/** Downloaded bytes do not match the pinned size or checksum, so the partial file is unusable. */
internal class ResourceContentException(message: String) : IOException(message)

/**
 * Caller serializes access to each target. Partial files are never exposed as usable resources, but
 * they are kept across interrupted transfers so a retry can resume instead of restarting.
 */
internal object VerifiedResourceStore {
    /** Bytes of a retained partial file that a resumed request may append to. */
    fun resumeOffset(part: File, resource: DownloadResource): Long {
        if (!part.isFile) return 0L
        val length = part.length()
        if (length >= resource.bytes) {
            part.delete()
            return 0L
        }
        return length.coerceAtLeast(0L)
    }

    suspend fun isValid(file: File, resource: DownloadResource): Boolean {
        if (!file.isFile || file.length() != resource.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest()).equals(resource.sha256, ignoreCase = true)
    }

    suspend fun write(
        target: File,
        resource: DownloadResource,
        input: InputStream,
        resumeFrom: Long,
        progress: (Long) -> Unit
    ): File {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val part = File(target.parentFile, "${target.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = resumeFrom.coerceAtLeast(0L)
            if (total > 0L) {
                // Seed the digest from the retained prefix so the final hash still covers every byte.
                part.inputStream().buffered().use { existing ->
                    val buffer = ByteArray(64 * 1024)
                    var seeded = 0L
                    while (seeded < total) {
                        currentCoroutineContext().ensureActive()
                        val read =
                            existing.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), total - seeded).toInt()
                            )
                        if (read < 0) {
                            throw ResourceContentException("Partial file is shorter than its offset")
                        }
                        digest.update(buffer, 0, read)
                        seeded += read
                    }
                }
            }
            val fileOutput = FileOutputStream(part, total > 0L)
            try {
                val output = fileOutput.buffered()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > resource.bytes) {
                        throw ResourceContentException("Resource exceeds expected size")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    progress(total)
                }
                output.flush()
                fileOutput.fd.sync()
            } finally {
                fileOutput.close()
            }
            if (total != resource.bytes || !hex(digest.digest()).equals(resource.sha256, true)) {
                throw ResourceContentException("Resource checksum mismatch")
            }
            currentCoroutineContext().ensureActive()
            // Same-directory rename on Android/Linux replaces an old invalid target atomically.
            if (!part.renameTo(target)) throw IOException("Cannot finalize downloaded resource")
            return target
        } catch (e: ResourceContentException) {
            // Only unusable content is discarded; interrupted transfers stay resumable.
            part.delete()
            throw e
        }
    }

    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

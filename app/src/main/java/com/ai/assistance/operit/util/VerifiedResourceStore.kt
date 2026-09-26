package com.ai.assistance.operit.util

import java.io.File
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

/** Caller serializes access to each target. Partial files are never exposed as usable resources. */
internal object VerifiedResourceStore {
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
        progress: (Long) -> Unit
    ): File {
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        val part = File(target.parentFile, "${target.name}.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > resource.bytes) throw IOException("Resource exceeds expected size")
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    progress(total)
                }
                output.fd.sync()
            }
            if (total != resource.bytes || !hex(digest.digest()).equals(resource.sha256, true)) {
                throw IOException("Resource checksum mismatch")
            }
            currentCoroutineContext().ensureActive()
            // Same-directory rename on Android/Linux replaces an old invalid target atomically.
            if (!part.renameTo(target)) throw IOException("Cannot finalize downloaded resource")
            return target
        } finally {
            part.delete()
        }
    }

    private fun hex(bytes: ByteArray) =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

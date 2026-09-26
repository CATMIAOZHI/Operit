package com.ai.assistance.operit.util

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SkillRepoZipPoolManager {
    @Volatile private var pool: SkillZipPool? = null

    @Synchronized
    fun initialize(baseDir: File) {
        if (pool == null) pool = SkillZipPool(OperitPaths.skillRepoZipPoolDir(baseDir))
    }

    suspend fun <T> withZip(
        key: String,
        downloadTo: suspend (File) -> Boolean,
        use: suspend (File) -> T
    ): T? = pool?.withZip(key, downloadTo, use)
}

/** Hold the lease through import: eviction must never delete a ZIP a caller is reading. */
internal class SkillZipPool(
    private val directory: File,
    private val maxEntries: Int = 6,
    private val maxBytes: Long = 128L * 1024 * 1024
) {
    private val mutex = Mutex()
    private val managedName = Regex("repo_[a-f0-9]{16,32}\\.(zip|download)")

    suspend fun <T> withZip(
        key: String,
        downloadTo: suspend (File) -> Boolean,
        use: suspend (File) -> T
    ): T? = mutex.withLock {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create skill ZIP cache" }
        // Only our own interrupted downloads are disposable. No active writer exists under this lock.
        directory.listFiles()?.filter { managedName.matches(it.name) && it.extension == "download" }
            ?.forEach { it.delete() }
        trim()
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val zip = File(directory, "repo_$digest.zip")
        val part = File(directory, "repo_$digest.download")
        try {
            if (!zip.isFile || zip.length() == 0L) {
                if (!downloadTo(part) || !part.isFile || part.length() == 0L) return@withLock null
                if (!part.renameTo(zip)) {
                    try {
                        part.copyTo(zip, overwrite = true)
                    } catch (e: Exception) {
                        zip.delete()
                        throw e
                    }
                }
            }
            zip.setLastModified(System.currentTimeMillis())
            use(zip)
        } finally {
            part.delete()
            // Oversized archives are usable for this import, but not retained.
            trim()
        }
    }

    private fun trim() {
        val files = directory.listFiles()?.filter {
            it.isFile && managedName.matches(it.name) && it.extension == "zip"
        }?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (count <= maxEntries && bytes <= maxBytes) break
            val size = file.length()
            if (file.delete()) {
                count--
                bytes -= size
            }
        }
    }
}

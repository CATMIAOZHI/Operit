package com.ai.assistance.operit.data.stats

import android.content.Context
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/** Low-level filesystem primitives for the token-stat spool; contains no queue policy. */
internal object SpoolFileSystem {
    fun spoolDir(context: Context, directoryName: String): File =
        File(context.filesDir, directoryName)

    fun syncDirectory(
        dir: File,
        logError: (String, Throwable?) -> Unit
    ): TokenStatSpool.DirSyncResult =
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            TokenStatSpool.DirSyncResult.OK
        } catch (e: AccessDeniedException) {
            logError(
                "statistics spool directory fsync unsupported on this platform; " +
                    "fail-closed: no directory entry is confirmed durable",
                e
            )
            TokenStatSpool.DirSyncResult.UNSUPPORTED
        } catch (e: Exception) {
            logError("statistics spool directory fsync failed: ${dir.absolutePath}", e)
            TokenStatSpool.DirSyncResult.FAILED
        }

    fun syncFile(file: File, logError: (String, Throwable?) -> Unit): Boolean =
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
            true
        } catch (e: Exception) {
            logError("statistics spool file fsync failed: ${file.name}", e)
            false
        }

    fun listDirectory(
        dir: File,
        seam: ((File) -> Array<File>?)?
    ): Array<File>? = if (seam != null) seam(dir) else dir.listFiles()

    fun contentsEqual(
        first: File,
        second: File,
        logError: (String, Throwable?) -> Unit
    ): Boolean? {
        if (first.length() != second.length()) return false
        if (first.length() == 0L) return true
        return try {
            first.inputStream().use { firstInput ->
                second.inputStream().use { secondInput ->
                    val firstBuffer = ByteArray(64 * 1024)
                    val secondBuffer = ByteArray(64 * 1024)
                    while (true) {
                        val firstCount = firstInput.read(firstBuffer)
                        val secondCount = secondInput.read(secondBuffer)
                        if (firstCount != secondCount) return@use false
                        if (firstCount < 0) return@use true
                        if (!firstBuffer.copyOfRange(0, firstCount)
                                .contentEquals(secondBuffer.copyOfRange(0, secondCount))) {
                            return@use false
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    true
                }
            }
        } catch (e: Exception) {
            logError("statistics spool seal duplicate content compare failed", e)
            null
        }
    }

    fun totalBytes(dir: File, cap: Long): Long {
        if (!dir.isDirectory) return 0L
        val saturated = if (cap == Long.MAX_VALUE) cap else cap + 1L
        var total = 0L
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) {
                    total += attrs.size()
                    if (total < 0L || total > cap) {
                        total = saturated
                        return FileVisitResult.TERMINATE
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                total = saturated
                return FileVisitResult.TERMINATE
            }
        }
        return try {
            Files.walkFileTree(
                dir.toPath(),
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                visitor
            )
            total
        } catch (_: IOException) {
            saturated
        }
    }
}

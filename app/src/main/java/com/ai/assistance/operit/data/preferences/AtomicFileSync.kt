package com.ai.assistance.operit.data.preferences

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The memory stores write a temp file, fsync it, then ATOMIC_MOVE over the target. That survives a
 * process kill, but a rename only reaches disk when the containing directory entry is flushed, so
 * the documented "recoverable after power loss" promise needs this call after the move.
 *
 * Best-effort on purpose: some filesystems refuse to open a directory for reading, and that must
 * never turn an otherwise complete write into a reported failure.
 */
internal fun syncDirectory(directory: File) {
    try {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
        // The rename itself already succeeded; only the durability hint is lost.
    }
}

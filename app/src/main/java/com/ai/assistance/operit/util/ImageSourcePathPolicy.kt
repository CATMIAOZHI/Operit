package com.ai.assistance.operit.util

import java.io.File

/** Bounds script-provided image paths without restricting native image callers. */
object ImageSourcePathPolicy {
    fun resolve(path: String, allowedRoots: List<File>): File {
        require(File(path).isAbsolute) { "Image path must be absolute" }
        val source = File(path).toPath().toRealPath()
        for (root in allowedRoots) {
            if (!root.isDirectory) continue
            val allowedRoot = root.toPath().toRealPath()
            if (source.startsWith(allowedRoot) && source != allowedRoot) {
                return source.toFile()
            }
        }
        throw IllegalArgumentException("Image path is outside permitted storage directories")
    }
}

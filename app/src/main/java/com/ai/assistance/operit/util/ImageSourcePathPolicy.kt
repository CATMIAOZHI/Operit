package com.ai.assistance.operit.util

import java.io.File

/** Bounds script-provided image paths without restricting native image callers. */
object ImageSourcePathPolicy {
    fun resolve(path: String, allowedRoots: List<File>): File {
        require(File(path).isAbsolute) { "Image path must be absolute" }
        val source = File(path).toPath().toRealPath().toFile()
        require(allowedRoots.any { root ->
            root.isDirectory &&
                source.path.startsWith(root.toPath().toRealPath().toString().trimEnd(File.separatorChar) + File.separator)
        }) { "Image path is outside permitted storage directories" }
        return source
    }
}

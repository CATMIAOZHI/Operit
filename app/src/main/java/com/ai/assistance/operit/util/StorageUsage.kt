package com.ai.assistance.operit.util

import java.io.File
import java.nio.file.Files

enum class StorageCategory { MEDIA, COMPONENTS, DATABASES, PREVIEW_CACHE, OTHER }

data class StorageUsage(
    val bytes: Map<StorageCategory, Long>,
    val skippedEntries: Int
) {
    val totalBytes: Long get() = bytes.values.sum()
}

/** Measures regular files only. Terminal symlinks must not escape the app or form cycles. */
internal fun measureStorage(
    roots: List<File>,
    classify: (File) -> StorageCategory,
    checkCancelled: () -> Unit = {}
): StorageUsage {
    val totals = StorageCategory.entries.associateWith { 0L }.toMutableMap()
    var skipped = 0
    val visited = mutableSetOf<String>()
    val pending = ArrayDeque<File>()
    roots.forEach { if (it.exists()) pending.add(it) }
    while (pending.isNotEmpty()) {
        checkCancelled()
        val file = pending.removeLast()
        try {
            if (Files.isSymbolicLink(file.toPath())) continue
            if (!visited.add(file.canonicalPath)) continue
            when {
                file.isDirectory -> {
                    val children = file.listFiles()
                    if (children == null) skipped++ else children.forEach(pending::add)
                }
                file.isFile -> {
                    val category = classify(file)
                    totals[category] = totals.getValue(category) + file.length()
                }
                else -> skipped++
            }
        } catch (_: SecurityException) {
            skipped++
        } catch (_: java.io.IOException) {
            skipped++
        }
    }
    return StorageUsage(totals, skipped)
}

internal fun storageCategory(relativeToDataDir: String): StorageCategory {
    val path = relativeToDataDir.replace('\\', '/')
    return when {
        path.startsWith("files/image_pool/") || path.startsWith("files/media_pool/") ->
            StorageCategory.MEDIA
        listOf("downloaded_resources", ".sherpa_ncnn_models", "local_voice_models", "usr")
            .any { path.startsWith("files/$it/") } -> StorageCategory.COMPONENTS
        path.startsWith("databases/") || path.startsWith("files/objectbox/") ||
            (path.startsWith("files/objectbox_") && path.substringAfter("files/").contains('/')) ->
            StorageCategory.DATABASES
        path.startsWith("files/image_cache/") -> StorageCategory.PREVIEW_CACHE
        else -> StorageCategory.OTHER
    }
}

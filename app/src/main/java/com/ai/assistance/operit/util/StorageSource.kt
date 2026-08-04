package com.ai.assistance.operit.util

import java.io.File

/**
 * Which physical store a loaded entry originated from.
 *
 * - [INTERNAL] is the app-private primary store ([OperitManagedPaths] `filesDir` roots). It is
 *   the default write target and wins on id/name conflicts during reads.
 * - [LEGACY_DOWNLOAD] is the optional, read-only `Download/Operit` compatibility source. It is
 *   only consulted while the corresponding [LegacyStoragePreferences] switch is on.
 *
 * Source is runtime metadata; it must not be persisted into user-facing JSON (Skill/MCP/workflow
 * files). Use [SourcedEntry] to carry it alongside the loaded value instead.
 */
enum class StorageSource {
    INTERNAL,
    LEGACY_DOWNLOAD;

    fun isLegacy(): Boolean = this == LEGACY_DOWNLOAD
}

/**
 * Wraps a loaded entry with the [source] it came from and the physical [sourceFile]. Callers
 * that need to decide whether a write-on-copy is required (legacy entries) can inspect [source]
 * without mutating the underlying model.
 */
data class SourcedEntry<T>(
    val value: T,
    val source: StorageSource,
    val sourceFile: File
) {
    val isLegacy: Boolean get() = source.isLegacy()
}
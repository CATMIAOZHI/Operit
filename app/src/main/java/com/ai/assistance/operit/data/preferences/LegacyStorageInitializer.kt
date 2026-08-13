package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitManagedPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal fun legacyDirectoryHasAnyEntry(dir: File): Boolean {
    val path = dir.toPath()
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
    return runCatching {
        Files.newDirectoryStream(path).use { entries -> entries.iterator().hasNext() }
    }.getOrDefault(false)
}

/**
 * One-shot initializer that seeds [LegacyStoragePreferences] on first launch.
 *
 * Default rules (applied exactly once, never re-evaluated automatically afterwards):
 *
 * - Existing user upgrading, and the corresponding legacy directory already contains data:
 *   the read switch for that source defaults to **on** (so old Skills / MCP / workflows keep
 *   showing up).
 * - Fresh install: **off**.
 * - Upgrading user whose legacy directory is missing or empty: **off** (nothing to be
 *   compatible with).
 *
 * Idempotent and concurrency-safe: the initialized guard lives in an [AtomicFile] under
 * [Context.noBackupFilesDir] (excluded from raw snapshots). If a pre-feature snapshot replaces
 * DataStore but leaves that guard intact, missing switch keys are reconstructed without
 * overwriting any explicit restored value. A process-wide lock serializes seed and repair.
 *
 * The initializer is a no-op while the official→Operit Ry migration is gating main-data access
 * ([OperitApplication.isMainDataAccessAllowed]), because probing legacy paths during that
 * window could observe a half-replaced data directory and produce wrong defaults.
 *
 * Legacy directory probes use [OperitManagedPaths] non-creating accessors, so this initializer
 * never resurrects `Download/Operit` on a fresh install.
 */
object LegacyStorageInitializer {

    private const val TAG = "LegacyStorageInitializer"

    /**
     * Seeds defaults or repairs missing restored keys. Safe to call on every startup. Returns
     * true only when persistent switch state changed this call.
     */
    suspend fun initializeIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!OperitApplication.isMainDataAccessAllowed(context)) {
            AppLogger.d(TAG, "skipped: main data access gated by migration")
            return@withContext false
        }
        val prefs = LegacyStoragePreferences.getInstance(context)
        val initialized = prefs.isInitialized()
        if (initialized && prefs.hasCompleteReadSwitchState()) {
            return@withContext false
        }

        val paths = OperitManagedPaths(context)
        val readSkills = legacyDirectoryHasAnyEntry(paths.legacySkills)
        val readMcp = legacyDirectoryHasAnyEntry(paths.legacyMcp)
        val readWorkflows = legacyDirectoryHasAnyEntry(paths.legacyWorkflows)
        if (initialized) {
            // A raw snapshot from before the compatibility feature can replace DataStore while
            // the no-backup marker survives. Reconstruct missing keys without overwriting any
            // switch that the restored snapshot already contains.
            prefs.restoreMissingReadSwitches(readSkills, readMcp, readWorkflows)
        } else {
            prefs.seedOnce(readSkills, readMcp, readWorkflows)
        }
    }

}

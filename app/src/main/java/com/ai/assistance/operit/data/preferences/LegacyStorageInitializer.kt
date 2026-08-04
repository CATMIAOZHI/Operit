package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.application.OperitApplication
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitManagedPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * Idempotent and concurrency-safe: [LegacyStoragePreferences.seedOnce] serializes the
 * check-and-seed with a process-wide lock and persists the initialized guard in an
 * [AtomicFile] under [Context.noBackupFilesDir] (excluded from raw snapshots), so a snapshot
 * restore cannot re-trigger auto-seeding and two concurrent startup callers seed at most once.
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
     * Seeds defaults if not already done. Safe to call on every startup. Returns true when the
     * flags were actually seeded this call, false when they were already initialized (or when the
     * main-data gate blocked the call).
     */
    suspend fun initializeIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!OperitApplication.isMainDataAccessAllowed(context)) {
            AppLogger.d(TAG, "skipped: main data access gated by migration")
            return@withContext false
        }
        val prefs = LegacyStoragePreferences.getInstance(context)
        if (prefs.isInitialized()) {
            return@withContext false
        }

        val paths = OperitManagedPaths(context)
        val readSkills = hasLegacyContent(paths.legacySkills)
        val readMcp = hasLegacyContent(paths.legacyMcp)
        val readWorkflows = hasLegacyContent(paths.legacyWorkflows)
        prefs.seedOnce(readSkills, readMcp, readWorkflows)
    }

    private fun hasLegacyContent(dir: File): Boolean {
        // Non-creating: only consider a pre-existing directory that is non-empty. A missing or
        // empty legacy directory yields false, so fresh installs default all switches off.
        if (!dir.isDirectory) return false
        return dir.listFiles()?.isNotEmpty() == true
    }
}
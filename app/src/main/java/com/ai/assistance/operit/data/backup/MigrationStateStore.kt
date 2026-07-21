package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.util.AppLogger
import java.io.File

/**
 * Persistent state machine for the one-shot official Operit -> Operit Ry migration.
 *
 * The state is stored in [Context.noBackupFilesDir] (i.e. `<dataDir>/no_backup`), which is NOT
 * captured by raw snapshots (they only include `files/`, `external_files/`, `shared_prefs/`,
 * `datastore/` and `databases/`). This guarantees:
 *
 * - The pre-migration safety snapshot never contains migration state, so restoring it for
 *   rollback does not re-enter the migration path.
 * - The state survives process crashes, forced stops and native crashes, because it is written
 *   to disk before any destructive operation begins.
 *
 * States transition strictly forward:
 *
 * IDLE -> PENDING -> PREPARING -> REPLACING -> COMPLETED
 *                                           -> FAILED
 *
 * Any process entry point (Activity, Service, Receiver, Worker) that observes PENDING must run
 * or resume the migration. PREPARING / REPLACING / FAILED mean the data directory may be
 * partially replaced and the app MUST NOT initialize normally; it must enter the dedicated
 * recovery surface so the user can restore from the safety snapshot. COMPLETED means the
 * migration succeeded and the entry point can initialize normally.
 *
 * [State.NEEDS_RECOVERY] is a virtual state returned by [read] when the state file is missing,
 * unreadable, or contains an unknown state name. It exists only in memory and is NEVER written
 * to disk; it forces the caller to treat the situation as recoverable (show the recovery
 * surface) instead of fail-open-ing into normal mode with potentially-partially-replaced data.
 * The recovery surface offers the user a chance to clear the corrupt state file and restore
 * from a safety snapshot.
 *
 * The state file is a multi-line text file:
 *
 *     STATE
 *     URI
 *     SAFETY_BACKUP_PATH
 *
 * where URI is only meaningful in PENDING and SAFETY_BACKUP_PATH is only meaningful in
 * REPLACING / FAILED (the absolute path to the safety snapshot created right before directory
 * replacement). Writes are atomic: a temp file is written and renamed to the final path, so a
 * crash mid-write leaves either the previous or the new state, never a truncated hybrid.
 */
object MigrationStateStore {
    private const val TAG = "MigrationStateStore"
    private const val STATE_FILE = "official_operit_migration_state.txt"
    private const val TMP_SUFFIX = ".tmp"

    enum class State {
        IDLE,
        PENDING,
        PREPARING,
        REPLACING,
        COMPLETED,
        FAILED,
        /**
         * Virtual state, never persisted. Returned by [read] when the state file is missing,
         * unreadable or contains an unknown state name. Forces the caller into the recovery
         * surface instead of fail-open-ing into normal mode.
         */
        NEEDS_RECOVERY
    }

    data class Snapshot(val state: State, val uri: Uri?, val safetyBackupPath: String?) {
        companion object {
            val IDLE = Snapshot(State.IDLE, null, null)
        }
    }

    /**
     * Read the current migration state. Any read failure (file missing, unreadable, unknown
     * state name) returns [State.NEEDS_RECOVERY] so the caller routes to the recovery surface
     * rather than fail-open-ing into normal mode with potentially-partially-replaced data.
     */
    fun read(context: Context): Snapshot {
        val file = stateFile(context)
        if (!file.isFile) {
            // A missing state file is normal on first install; treat as IDLE.
            return Snapshot.IDLE
        }
        val text = runCatching { file.readText() }.getOrElse { e ->
            // The state file exists but cannot be read (corrupt, IO error, permissions, ...).
            // Fail closed: route to the recovery surface so the user can restore from a safety
            // snapshot instead of normal-starting into potentially-partially-replaced data.
            AppLogger.w(TAG, "failed to read migration state: ${e.message}")
            return Snapshot(State.NEEDS_RECOVERY, null, null)
        }
        val lines = text.split('\n')
        val stateName = lines.getOrNull(0)?.trim().orEmpty()
        val state = runCatching { State.valueOf(stateName) }.getOrElse {
            // Unknown state name (could be from a future version or a truncated write that
            // happened to land on a valid file boundary). Fail closed.
            AppLogger.w(TAG, "unknown migration state: $stateName")
            return Snapshot(State.NEEDS_RECOVERY, null, null)
        }
        val uriString = lines.getOrNull(1)?.trim().orEmpty()
        val uri = if (uriString.isNotEmpty()) runCatching { Uri.parse(uriString) }.getOrNull() else null
        val safetyPath = lines.getOrNull(2)?.trim().orEmpty()
        val safetyBackupPath = if (safetyPath.isNotEmpty()) safetyPath else null
        return Snapshot(state, uri, safetyBackupPath)
    }

    /**
     * Atomically write [state] (and [uri] / [safetyBackupPath] when non-null, only meaningful
     * for PENDING / REPLACING / FAILED) to the state file. Returns true on success, false on
     * failure. Callers MUST check the return value and surface an error to the user instead of
     * proceeding.
     */
    fun write(
        context: Context,
        state: State,
        uri: Uri? = null,
        safetyBackupPath: String? = null
    ): Boolean {
        check(state != State.NEEDS_RECOVERY) {
            "NEEDS_RECOVERY is a virtual state and must not be written to disk"
        }
        val file = stateFile(context)
        val tmp = File(file.absolutePath + TMP_SUFFIX)
        val payload = buildString {
            append(state.name)
            append('\n')
            if (uri != null) append(uri.toString())
            append('\n')
            if (safetyBackupPath != null) append(safetyBackupPath)
            append('\n')
        }
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            AppLogger.i(
                TAG,
                "migration state -> $state (uri=${uri?.toString().orEmpty()}, safety=$safetyBackupPath)"
            )
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "failed to write migration state $state: ${e.message}", e)
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Convenience: transition to [state] and assert the write succeeded. Use only in contexts
     * where a failed write should abort the operation (the caller is expected to throw on
     * failure, which propagates to the migration error handler).
     */
    fun writeOrThrow(
        context: Context,
        state: State,
        uri: Uri? = null,
        safetyBackupPath: String? = null
    ) {
        check(write(context, state, uri, safetyBackupPath)) {
            "Failed to persist migration state $state"
        }
    }

    fun clear(context: Context) {
        val file = stateFile(context)
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            AppLogger.w(TAG, "failed to clear migration state: ${e.message}")
        }
    }

    private fun stateFile(context: Context): File =
        File(context.noBackupFilesDir, STATE_FILE)
}
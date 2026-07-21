package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
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
 * Normal migration states transition forward, with explicit safe reset transitions:
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
 * [State.NEEDS_RECOVERY] is a virtual state returned by [read] when an existing state file is
 * unreadable or contains an unknown state name. It exists only in memory and is NEVER written
 * to disk; it forces the caller to treat the situation as recoverable (show the recovery
 * surface) instead of fail-open-ing into normal mode with potentially-partially-replaced data.
 * The recovery surface requires the user to restore a safety snapshot before recording IDLE.
 *
 * The state file is a multi-line text file:
 *
 *     STATE
 *     URI
 *     SAFETY_BACKUP_PATH
 *
 * where URI is only meaningful in PENDING and SAFETY_BACKUP_PATH is only meaningful in
 * REPLACING / FAILED (the absolute path to the safety snapshot created right before directory
 * replacement). Writes use Android [AtomicFile], so a crash mid-write leaves either the previous
 * or the new state, never a truncated hybrid.
 */
object MigrationStateStore {
    private const val TAG = "MigrationStateStore"
    private const val STATE_FILE = "official_operit_migration_state.txt"

    enum class State {
        IDLE,
        PENDING,
        PREPARING,
        REPLACING,
        COMPLETED,
        FAILED,
        /**
         * Virtual state, never persisted. Returned by [read] when an existing state file is
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
     * Read the current migration state. A missing file is the normal [State.IDLE] state. Any
     * failure reading an existing atomic file, or an unknown state name, returns
     * [State.NEEDS_RECOVERY] so the caller cannot fail open with partially-replaced data.
     */
    fun read(context: Context): Snapshot {
        val file = stateFile(context)
        val atomicFile = AtomicFile(file)
        if (!atomicFile.exists()) {
            // A missing state file is normal on first install; treat as IDLE.
            return Snapshot.IDLE
        }
        val text = runCatching {
            atomicFile.openRead().bufferedReader().use { it.readText() }
        }.getOrElse { e ->
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
        val payload = buildString {
            append(state.name)
            append('\n')
            if (uri != null) append(uri.toString())
            append('\n')
            if (safetyBackupPath != null) append(safetyBackupPath)
            append('\n')
        }
        val atomicFile = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        return try {
            val parent = file.parentFile
                ?: throw IllegalStateException("Migration state file has no parent directory")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("Failed to create migration state directory")
            }
            output = atomicFile.startWrite()
            output.write(payload.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            output = null
            AppLogger.i(
                TAG,
                "migration state -> $state (uri=${uri?.toString().orEmpty()}, safety=$safetyBackupPath)"
            )
            true
        } catch (e: Exception) {
            try {
                output?.let { atomicFile.failWrite(it) }
            } catch (rollbackError: Exception) {
                AppLogger.e(TAG, "failed to roll back migration state write", rollbackError)
            }
            AppLogger.e(TAG, "failed to write migration state $state: ${e.message}", e)
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

    private fun stateFile(context: Context): File =
        File(context.noBackupFilesDir, STATE_FILE)
}

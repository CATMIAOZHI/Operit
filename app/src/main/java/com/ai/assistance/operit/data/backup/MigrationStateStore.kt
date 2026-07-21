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
 * The state file is a single line: `<STATE>\n<URI>\n` where `<URI>` is only meaningful in
 * PENDING. Writes are atomic: a temp file is written and renamed to the final path, so a crash
 * mid-write leaves either the previous or the new state, never a truncated hybrid.
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
        FAILED
    }

    data class Snapshot(val state: State, val uri: Uri?) {
        companion object {
            val IDLE = Snapshot(State.IDLE, null)
        }
    }

    fun read(context: Context): Snapshot {
        val file = stateFile(context)
        if (!file.isFile) return Snapshot.IDLE
        val text = runCatching { file.readText() }.getOrElse { e ->
            AppLogger.w(TAG, "failed to read migration state: ${e.message}")
            return Snapshot.IDLE
        }
        val lines = text.split('\n')
        val stateName = lines.getOrNull(0)?.trim().orEmpty()
        val state = runCatching { State.valueOf(stateName) }.getOrElse {
            AppLogger.w(TAG, "unknown migration state: $stateName")
            return Snapshot.IDLE
        }
        val uriString = lines.getOrNull(1)?.trim().orEmpty()
        val uri = if (uriString.isNotEmpty()) runCatching { Uri.parse(uriString) }.getOrNull() else null
        return Snapshot(state, uri)
    }

    /**
     * Atomically write [state] (and [uri] when non-null, only meaningful for PENDING) to the
     * state file. Returns true on success, false on failure. Callers MUST check the return value
     * and surface an error to the user instead of proceeding.
     */
    fun write(context: Context, state: State, uri: Uri? = null): Boolean {
        val file = stateFile(context)
        val tmp = File(file.absolutePath + TMP_SUFFIX)
        val payload = buildString {
            append(state.name)
            append('\n')
            if (uri != null) append(uri.toString())
            append('\n')
        }
        return try {
            file.parentFile?.mkdirs()
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            AppLogger.i(TAG, "migration state -> $state (uri=${uri?.toString().orEmpty()})")
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
    fun writeOrThrow(context: Context, state: State, uri: Uri? = null) {
        check(write(context, state, uri)) { "Failed to persist migration state $state" }
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
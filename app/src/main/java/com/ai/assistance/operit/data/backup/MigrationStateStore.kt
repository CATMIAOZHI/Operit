package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream

/**
 * Persistent state machine for the one-shot official Operit -> Operit Ry migration
 * and for the destructive raw-snapshot restore flow (restoreFromBackupUri /
 * restoreFromBackupFile).
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
 * Raw-snapshot restore adds one state to the same file so that a crash between "directories
 * replaced" and "generation registered" never forces the user to redo the restore:
 *
 * (IDLE | COMPLETED | REPLACING | FAILED) -> REPLACING -> RESTORE_REPLACED -> (IDLE | COMPLETED)
 *
 * - REPLACING is persisted BEFORE any destructive directory replacement starts; a cold start
 *   observing it shows the recovery surface (data may be partially replaced).
 * - RESTORE_REPLACED means every directory was replaced successfully but the controlled
 *   re-import generation was not yet registered. A cold start observing it must NOT restore
 *   again; it auto-registers a fresh generation and completes the state
 *   ([RawSnapshotBackupManager.completePendingRestoreRegistration]).
 * - The final state after registration is the state that preceded the restore when it was
 *   COMPLETED (official migration marker preserved), otherwise IDLE. It is persisted in the
 *   state file as the fourth line so a crashed process can complete registration the same way.
 *
 * Any process entry point (Activity, Service, Receiver, Worker) that observes PENDING must run
 * or resume the migration. PREPARING blocks normal initialization; the process-owned run keeps
 * its progress surface across Activity recreation, while a cold process can safely reset it
 * because replacement has not started. REPLACING / FAILED require the recovery surface.
 * RESTORE_REPLACED requires the auto-registration surface. COMPLETED means the migration
 * succeeded and the entry point can initialize normally.
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
 *     FINAL_STATE (only meaningful for RESTORE_REPLACED)
 *
 * where URI is only meaningful in PENDING and SAFETY_BACKUP_PATH is only meaningful in
 * REPLACING / FAILED (the absolute path to the safety snapshot created right before directory
 * replacement). Writes use Android [AtomicFile], so a crash mid-write leaves either the previous
 * or the new state, never a truncated hybrid.
 */
object MigrationStateStore {
    private const val TAG = "MigrationStateStore"
    private const val STATE_FILE = "official_operit_migration_state.txt"
    private val processStateLock = Any()

    enum class State {
        IDLE,
        PENDING,
        PREPARING,
        REPLACING,
        COMPLETED,
        FAILED,
        /**
         * 原始快照恢复（restoreFromBackupUri/File）已完成全部目录替换、受控补导
         * generation 尚未登记。冷启动必须自动补登记（新 generation）并完成状态，
         * 不得重新执行目录替换。
         */
        RESTORE_REPLACED,
        /**
         * Virtual state, never persisted. Returned by [read] when an existing state file is
         * unreadable or contains an unknown state name. Forces the caller into the recovery
         * surface instead of fail-open-ing into normal mode.
         */
        NEEDS_RECOVERY;

        fun allowsMainDataAccess(): Boolean = this == IDLE || this == COMPLETED
    }

    data class Snapshot(
        val state: State,
        val uri: Uri?,
        val safetyBackupPath: String?,
        /**
         * 仅 RESTORE_REPLACED 有意义：登记完成后应落回的目标状态
         * （恢复前为 COMPLETED 则保持 COMPLETED，否则 IDLE）。
         */
        val finalState: State? = null,
    ) {
        companion object {
            val IDLE = Snapshot(State.IDLE, null, null)
        }
    }

    /**
     * 测试注入缝：null 时用 Android AtomicFile 实现（生产）。测试注入真实文件
     * 实现后，[read]/[write] 的状态机逻辑可在纯 JVM 上做真实状态转换测试。
     */
    internal var fileIoProvider: ((Context) -> MigrationStateFileIo)? = null

    /**
     * Read the current migration state. A missing file is the normal [State.IDLE] state. Any
     * failure reading an existing atomic file, or an unknown state name, returns
     * [State.NEEDS_RECOVERY] so the caller cannot fail open with partially-replaced data.
     */
    fun read(context: Context): Snapshot {
        val text = try {
            fileIo(context).read() ?: return Snapshot.IDLE
        } catch (e: Exception) {
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
        val finalStateName = lines.getOrNull(3)?.trim().orEmpty()
        val finalState = if (finalStateName.isNotEmpty()) {
            runCatching { State.valueOf(finalStateName) }.getOrNull()
        } else {
            null
        }
        return Snapshot(state, uri, safetyBackupPath, finalState)
    }

    fun isMainDataAccessAllowed(context: Context): Boolean =
        MigrationStatePolicy.isMainDataAccessAllowed(
            state = read(context).state,
            processRestartRequired = RawSnapshotBackupManager.isProcessRestartRequired()
        )

    internal fun <T> withProcessStateLock(block: () -> T): T =
        synchronized(processStateLock, block)

    /**
     * Atomically write [state] (and [uri] / [safetyBackupPath] / [finalState] when non-null,
     * only meaningful for PENDING / REPLACING / FAILED / RESTORE_REPLACED respectively) to the
     * state file. Returns true on success, false on failure. Callers MUST check the return
     * value and surface an error to the user instead of proceeding.
     */
    fun write(
        context: Context,
        state: State,
        uri: Uri? = null,
        safetyBackupPath: String? = null,
        finalState: State? = null
    ): Boolean {
        check(state != State.NEEDS_RECOVERY) {
            "NEEDS_RECOVERY is a virtual state and must not be written to disk"
        }
        check(finalState == null || state == State.RESTORE_REPLACED) {
            "finalState is only meaningful for RESTORE_REPLACED"
        }
        check(finalState == null || finalState == State.IDLE || finalState == State.COMPLETED) {
            "finalState must be IDLE or COMPLETED"
        }
        val payload = buildString {
            append(state.name)
            append('\n')
            if (uri != null) append(uri.toString())
            append('\n')
            if (safetyBackupPath != null) append(safetyBackupPath)
            append('\n')
            if (finalState != null) append(finalState.name)
            append('\n')
        }
        return try {
            fileIo(context).write(payload)
            AppLogger.i(
                TAG,
                "migration state -> $state (uri=${uri?.toString().orEmpty()}, " +
                    "safety=$safetyBackupPath, final=$finalState)"
            )
            true
        } catch (e: Exception) {
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
        safetyBackupPath: String? = null,
        finalState: State? = null
    ) {
        check(write(context, state, uri, safetyBackupPath, finalState)) {
            "Failed to persist migration state $state"
        }
    }

    private fun stateFile(context: Context): File =
        File(context.noBackupFilesDir, STATE_FILE)

    private fun fileIo(context: Context): MigrationStateFileIo =
        fileIoProvider?.invoke(context) ?: AtomicFileStateIo(stateFile(context))
}

/**
 * 迁移状态文件 IO 边界：生产用 Android AtomicFile（原子写 + .bak 回退），
 * JVM 测试注入真实文件实现（不依赖 android.util）。
 */
internal interface MigrationStateFileIo {
    /** 读取状态文件全文；文件不存在返回 null；IO 失败抛异常（调用方 fail-closed）。 */
    fun read(): String?

    /** 原子写入状态文件全文；失败抛异常。 */
    fun write(payload: String)
}

/** 生产实现：Android [AtomicFile]（等价的原子替换 + 崩溃回退语义）。 */
private class AtomicFileStateIo(private val file: File) : MigrationStateFileIo {

    private val atomicFile = AtomicFile(file)

    override fun read(): String? {
        if (!file.isFile && !File(file.absolutePath + ".bak").isFile) {
            // A missing state file is normal on first install; treat as IDLE.
            return null
        }
        return atomicFile.openRead().bufferedReader().use { it.readText() }
    }

    override fun write(payload: String) {
        val parent = file.parentFile
            ?: throw IllegalStateException("Migration state file has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create migration state directory")
        }
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(payload.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            output = null
        } catch (e: Exception) {
            try {
                output?.let { atomicFile.failWrite(it) }
            } catch (rollbackError: Exception) {
                AppLogger.e(TAG, "failed to roll back migration state write", rollbackError)
            }
            throw e
        }
    }

    private companion object {
        const val TAG = "MigrationStateStore"
    }
}

internal object MigrationStatePolicy {
    enum class MainInitializationAction {
        INITIALIZE,
        MIGRATION_IN_PROGRESS,
        SHOW_RECOVERY
    }

    enum class StartupAction {
        INITIALIZE,
        RUN_PENDING,
        SHOW_IN_PROGRESS,
        RESET_PREPARING,
        SHOW_RECOVERY,
        /** RESTORE_REPLACED：目录已替换完成，冷启动自动补登记 generation 后退出。 */
        COMPLETE_RESTORE_REGISTRATION
    }

    fun isSafelyCancellable(state: MigrationStateStore.State): Boolean =
        state == MigrationStateStore.State.PENDING ||
            state == MigrationStateStore.State.PREPARING

    fun isMainDataAccessAllowed(
        state: MigrationStateStore.State,
        processRestartRequired: Boolean
    ): Boolean = !processRestartRequired && state.allowsMainDataAccess()

    fun stateAfterFailure(
        replacementStarted: Boolean,
        processRestartRequired: Boolean
    ): MigrationStateStore.State =
        when {
            replacementStarted -> MigrationStateStore.State.FAILED
            processRestartRequired -> MigrationStateStore.State.PREPARING
            else -> MigrationStateStore.State.IDLE
        }

    fun mainInitializationAction(
        state: MigrationStateStore.State,
        processRestartRequired: Boolean,
        migrationRunningInProcess: Boolean
    ): MainInitializationAction =
        when {
            migrationRunningInProcess -> MainInitializationAction.MIGRATION_IN_PROGRESS
            processRestartRequired -> MainInitializationAction.SHOW_RECOVERY
            state == MigrationStateStore.State.PENDING ->
                MainInitializationAction.MIGRATION_IN_PROGRESS
            state.allowsMainDataAccess() -> MainInitializationAction.INITIALIZE
            else -> MainInitializationAction.SHOW_RECOVERY
        }

    fun startupAction(
        state: MigrationStateStore.State,
        migrationRunningInProcess: Boolean = false
    ): StartupAction =
        when (state) {
            MigrationStateStore.State.IDLE,
            MigrationStateStore.State.COMPLETED -> StartupAction.INITIALIZE
            MigrationStateStore.State.PENDING ->
                if (migrationRunningInProcess) StartupAction.SHOW_IN_PROGRESS
                else StartupAction.RUN_PENDING
            MigrationStateStore.State.PREPARING ->
                if (migrationRunningInProcess) StartupAction.SHOW_IN_PROGRESS
                else StartupAction.RESET_PREPARING
            MigrationStateStore.State.REPLACING,
            MigrationStateStore.State.FAILED,
            MigrationStateStore.State.NEEDS_RECOVERY -> StartupAction.SHOW_RECOVERY
            MigrationStateStore.State.RESTORE_REPLACED ->
                StartupAction.COMPLETE_RESTORE_REGISTRATION
        }
}

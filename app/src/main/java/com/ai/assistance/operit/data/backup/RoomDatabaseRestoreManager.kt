package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.db.AppDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object RoomDatabaseRestoreManager {

    private const val TAG = "RoomDbRestore"
    private const val DB_NAME = "app_database"

    private const val AUTO_BACKUP_FILE_PREFIX = "room_db_backup_"
    private const val MANUAL_BACKUP_FILE_PREFIX = "room_db_manual_backup_"

    fun listRecentAutoBackups(context: Context, limit: Int = 3): List<File> {
        val newDir = OperitBackupDirs.roomDbDir()
        val legacyDir = OperitBackupDirs.operitRootDir()

        val backups = sequenceOf(newDir, legacyDir)
            .flatMap { dir ->
                (dir.listFiles { f ->
                    f.isFile && f.name.startsWith(AUTO_BACKUP_FILE_PREFIX) && f.name.endsWith(".zip")
                }?.asSequence() ?: emptySequence())
            }
            .distinctBy { it.name }
            .toList()

        return backups.sortedByDescending { it.name }.take(limit)
    }

    fun listRecentBackups(context: Context, limit: Int = 3): List<File> {
        val newDir = OperitBackupDirs.roomDbDir()
        val legacyDir = OperitBackupDirs.operitRootDir()

        val backups = sequenceOf(newDir, legacyDir)
            .flatMap { dir ->
                (dir.listFiles { f ->
                    f.isFile && isRoomDatabaseBackupFile(f.name)
                }?.asSequence() ?: emptySequence())
            }
            .distinctBy { it.name }
            .toList()

        return backups
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
            .take(limit)
    }

    fun isRoomDatabaseBackupFile(name: String): Boolean {
        return (name.startsWith(AUTO_BACKUP_FILE_PREFIX) || name.startsWith(MANUAL_BACKUP_FILE_PREFIX)) &&
            name.endsWith(".zip")
    }

    /** Stage a private copy; the UI must exit immediately after this returns. */
    suspend fun stageRestoreFromUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        stage(context) {
            context.contentResolver.openInputStream(uri)
                ?: error("Failed to open restore archive")
        }
    }

    suspend fun stageRestoreFromFile(context: Context, file: File) = withContext(Dispatchers.IO) {
        stage(context) { file.inputStream() }
    }

    private suspend fun stage(context: Context, open: () -> java.io.InputStream) {
        RoomDatabaseBackupRestoreLock.mutex.withLock {
            val workspace = File(context.noBackupFilesDir, "room-restore-${java.util.UUID.randomUUID()}")
            check(workspace.mkdirs()) { "Cannot prepare database restore" }
            val archive = File(workspace, "source.zip")
            var staged = false
            try {
                open().use { input ->
                    FileOutputStream(archive).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                extractArchive(archive, workspace)
                syncRestoreDirectory(workspace)
                syncRestoreDirectory(context.noBackupFilesDir)
                MigrationStateStore.withProcessStateLock {
                    val previous = MigrationStateStore.read(context)
                    check(MigrationStateStore.isMainDataAccessAllowed(context)) {
                        "Another restore operation is pending"
                    }
                    staged = true
                    try {
                        MigrationStateStore.writeOrThrow(
                            context, MigrationStateStore.State.PENDING,
                            uri = Uri.fromFile(archive), finalState = previous.state,
                            operation = "ROOM_RESTORE",
                        )
                    } catch (error: Exception) {
                        try {
                            MigrationStateStore.writeOrThrow(context, previous.state)
                            staged = false
                        } catch (rollbackError: Exception) {
                            error.addSuppressed(rollbackError)
                            RawSnapshotBackupManager.requireProcessRestart()
                        }
                        throw error
                    }
                }
            } finally {
                if (!staged) workspace.deleteRecursively()
            }
        }
    }

    internal fun hasInterruptedReplacement(context: Context): Boolean {
        val pending = MigrationStateStore.read(context)
        if (pending.operation != "ROOM_RESTORE") return false
        return RoomRestoreFileSet(context.getDatabasePath(DB_NAME), workspace(context, pending)).hasRollback
    }

    /** Only the existing pre-initialization dispatcher may enter here. */
    internal suspend fun runPending(context: Context) {
        val pending = MigrationStateStore.read(context)
        check(pending.state == MigrationStateStore.State.PENDING && pending.operation == "ROOM_RESTORE")
        val finalState = checkNotNull(pending.finalState)
        val workspace = workspace(context, pending)
        val files = RoomRestoreFileSet(
            context.getDatabasePath(DB_NAME), workspace,
            spoolDirectory = File(context.filesDir, "token_stats_spool"),
        )
        var finalStateDurable = false
        try {
            if (files.hasRollback) {
                files.rollback()
                MigrationStateStore.writeOrThrow(context, finalState)
                finalStateDurable = true
                error("Interrupted database restore was rolled back; select the backup again")
            }
            extractArchive(File(workspace, "source.zip"), workspace)
            RawSnapshotBackupManager.withTokenStatsRestoreIsolation(
                context,
                prepareBeforeCommit = { AppDatabase.closeDatabase() },
                commitReplacement = {},
                block = { files.replace() },
            )
            // Room-only restore must not register a raw-snapshot legacy-import generation.
            MigrationStateStore.writeOrThrow(context, finalState)
            finalStateDurable = true
        } catch (error: Exception) {
            if (!finalStateDurable) {
                try {
                    // A failed final-state sync may already be visible. Re-close the durable gate
                    // before rollback can itself produce a partially restored file set.
                    MigrationStateStore.writeOrThrow(
                        context, MigrationStateStore.State.PENDING,
                        uri = pending.uri, finalState = finalState, operation = "ROOM_RESTORE",
                    )
                    if (files.hasRollback) files.rollback()
                    MigrationStateStore.writeOrThrow(context, finalState)
                    finalStateDurable = true
                } catch (rollbackError: Exception) {
                    error.addSuppressed(rollbackError)
                    // Keep PENDING and its journal: a cold start retries rollback before any DAO opens.
                }
            }
            throw error
        } finally {
            if (finalStateDurable) {
                workspace.deleteRecursively()
            }
        }
    }

    private fun workspace(context: Context, pending: MigrationStateStore.Snapshot): File {
        val archive = File(checkNotNull(pending.uri?.path))
        val workspace = checkNotNull(archive.parentFile).canonicalFile
        check(workspace.parentFile == context.noBackupFilesDir.canonicalFile &&
            workspace.name.startsWith("room-restore-") && archive.name == "source.zip") {
            "Invalid staged database archive"
        }
        return workspace
    }

    private fun extractArchive(archive: File, workspace: File) {
        val expected = setOf(DB_NAME, "$DB_NAME-wal", "$DB_NAME-shm")
        expected.forEach { java.nio.file.Files.deleteIfExists(File(workspace, it).toPath()) }
        val seen = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name in expected) {
                    check(!entry.isDirectory && seen.add(entry.name)) { "Duplicate database archive entry" }
                    FileOutputStream(File(workspace, entry.name)).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }
                input.closeEntry()
            }
        }
        check(DB_NAME in seen) { "Invalid backup zip: missing $DB_NAME" }
        val header = ByteArray(16)
        java.io.DataInputStream(File(workspace, DB_NAME).inputStream()).use { it.readFully(header) }
        check(header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Invalid SQLite backup" }
    }
}

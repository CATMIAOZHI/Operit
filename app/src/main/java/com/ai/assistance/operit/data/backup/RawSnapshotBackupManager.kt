package com.ai.assistance.operit.data.backup

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.db.ObjectBoxManager
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object RawSnapshotBackupManager {

    private const val TAG = "RawSnapshotBackup"
    private const val FORMAT_VERSION = 1

    private const val ZIP_PREFIX = "operit_raw_snapshot_"

    private const val MIGRATION_PREFERENCES = "operit_ry_official_migration"
    private const val MIGRATION_COMPLETED = "completed"
    private const val MIGRATION_PENDING = "pending"
    private const val MIGRATION_PENDING_URI = "pending_uri"

    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_PAYLOAD_PREFIX = "payload/"

    private const val ENTRY_FILES = "payload/files/"
    private const val ENTRY_EXTERNAL_FILES = "payload/external_files/"
    private const val ENTRY_SHARED_PREFS = "payload/shared_prefs/"
    private const val ENTRY_DATASTORE = "payload/datastore/"
    private const val ENTRY_DATABASES = "payload/databases/"

    private val terminalTopLevelDirNames = setOf("usr", "tmp", "bin")

    private val mutex = Mutex()

    @Volatile
    private var processRestartRequired = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @Serializable
    data class Manifest(
        val formatVersion: Int,
        val packageName: String,
        val createdAt: Long,
        val includes: List<String>,
        val includeTerminalData: Boolean = true
    )

    data class SnapshotOptions(
        val includeTerminalData: Boolean = false
    )

    enum class ExportProgress {
        PREPARING,
        SCANNING_FILES,
        ZIPPING_FILES,
        ZIPPING_EXTERNAL_FILES,
        ZIPPING_SHARED_PREFS,
        ZIPPING_DATASTORE,
        ZIPPING_DATABASES,
        FINALIZING
    }

    data class ExportProgressInfo(
        val stage: ExportProgress,
        val percent: Int? = null,
        val scannedFiles: Int? = null
    )

    enum class RestoreProgress {
        PREPARING,
        READING_ZIP,
        EXTRACTING,
        REPLACING_FILES,
        REPLACING_EXTERNAL_FILES,
        REPLACING_SHARED_PREFS,
        REPLACING_DATASTORE,
        REPLACING_DATABASES,
        FINALIZING
    }

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun exportToBackupDir(
        context: Context,
        options: SnapshotOptions = SnapshotOptions(),
        onProgress: ((ExportProgressInfo) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!processRestartRequired) { "Application restart required before another snapshot operation" }
            exportToBackupDirLocked(context, options, onProgress)
        }
    }

    private suspend fun exportToBackupDirLocked(
        context: Context,
        options: SnapshotOptions,
        onProgress: ((ExportProgressInfo) -> Unit)?,
        performDatabaseCheckpoint: Boolean = true,
        requireDatabaseCheckpoint: Boolean = false
    ): File {
            AppLogger.i(TAG, "export start (includeTerminalData=${options.includeTerminalData})")
            withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.PREPARING)) }
            val exportDir = OperitBackupDirs.rawSnapshotDir()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
            val outFile = File(exportDir, "$ZIP_PREFIX$timestamp.zip")
            val tmpFile = File(exportDir, "${outFile.name}.tmp")

            if (tmpFile.exists()) {
                tmpFile.delete()
            }

            val dataDir = context.dataDir
            val externalFilesDir = requireNotNull(context.getExternalFilesDir(null)) {
                "External files dir is unavailable"
            }
            val sharedPrefsDir = File(dataDir, "shared_prefs")
            val datastoreDir = File(dataDir, "datastore")
            val databasesDir = File(dataDir, "databases")

            if (performDatabaseCheckpoint) {
                try {
                    checkpointRoomDatabase(context)
                } catch (e: Exception) {
                    AppLogger.w(TAG, "wal_checkpoint failed", e)
                    if (requireDatabaseCheckpoint) throw e
                }
            }

            val includes = listOf(
                ENTRY_FILES,
                ENTRY_EXTERNAL_FILES,
                ENTRY_SHARED_PREFS,
                ENTRY_DATASTORE,
                ENTRY_DATABASES
            )
            val manifest = Manifest(
                formatVersion = FORMAT_VERSION,
                packageName = context.packageName,
                createdAt = System.currentTimeMillis(),
                includes = includes,
                includeTerminalData = options.includeTerminalData
            )

            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmpFile))).use { zos ->
                zos.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                zos.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val alwaysExcluded = OperitPaths.rawSnapshotExcludedFilesTopLevelDirNames()
                val excludedNames = if (options.includeTerminalData) {
                    alwaysExcluded
                } else {
                    alwaysExcluded + terminalTopLevelDirNames
                }
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(ExportProgressInfo(stage = ExportProgress.SCANNING_FILES, scannedFiles = 0))
                }
                val filesTotalCount = totalFilesForZip(
                    dir = context.filesDir,
                    entryPrefix = ENTRY_FILES,
                    excludedTopLevelDirNames = excludedNames,
                    onScannedCountChanged = { scanned ->
                        if (onProgress != null) {
                            mainHandler.post {
                                onProgress.invoke(
                                    ExportProgressInfo(stage = ExportProgress.SCANNING_FILES, scannedFiles = scanned)
                                )
                            }
                        }
                    }
                )
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        ExportProgressInfo(stage = ExportProgress.SCANNING_FILES, scannedFiles = filesTotalCount)
                    )
                }
                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_FILES, 0)) }
                val filesMs = measureTimeMillis {
                    addDirToZip(
                        zos = zos,
                        dir = context.filesDir,
                        entryPrefix = ENTRY_FILES,
                        excludedTopLevelDirNames = excludedNames,
                        totalFiles = filesTotalCount,
                        onPercentChanged = { percent ->
                            if (onProgress != null) {
                                mainHandler.post {
                                    onProgress.invoke(ExportProgressInfo(ExportProgress.ZIPPING_FILES, percent))
                                }
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_FILES, 100)) }
                AppLogger.i(TAG, "export add files done in ${filesMs}ms (excludedTopLevel=${excludedNames.size})")

                val externalFilesTotalCount = totalFilesForZip(
                    dir = externalFilesDir,
                    entryPrefix = ENTRY_EXTERNAL_FILES,
                    excludedTopLevelDirNames = emptySet()
                )
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_EXTERNAL_FILES, 0))
                }
                val externalFilesMs = measureTimeMillis {
                    addDirToZip(
                        zos = zos,
                        dir = externalFilesDir,
                        entryPrefix = ENTRY_EXTERNAL_FILES,
                        totalFiles = externalFilesTotalCount,
                        onPercentChanged = { percent ->
                            if (onProgress != null) {
                                mainHandler.post {
                                    onProgress.invoke(
                                        ExportProgressInfo(ExportProgress.ZIPPING_EXTERNAL_FILES, percent)
                                    )
                                }
                            }
                        }
                    )
                }
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_EXTERNAL_FILES, 100))
                }
                AppLogger.i(TAG, "export add external_files done in ${externalFilesMs}ms")

                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_SHARED_PREFS)) }
                val sharedPrefsMs = measureTimeMillis { addDirToZip(zos, sharedPrefsDir, ENTRY_SHARED_PREFS) }
                AppLogger.i(TAG, "export add shared_prefs done in ${sharedPrefsMs}ms")

                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_DATASTORE)) }
                val datastoreMs = measureTimeMillis { addDirToZip(zos, datastoreDir, ENTRY_DATASTORE) }
                AppLogger.i(TAG, "export add datastore done in ${datastoreMs}ms")

                withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.ZIPPING_DATABASES)) }
                val databasesMs = measureTimeMillis { addDirToZip(zos, databasesDir, ENTRY_DATABASES) }
                AppLogger.i(TAG, "export add databases done in ${databasesMs}ms")
            }

            withContext(Dispatchers.Main) { onProgress?.invoke(ExportProgressInfo(ExportProgress.FINALIZING)) }
            if (outFile.exists()) {
                outFile.delete()
            }

            if (!tmpFile.renameTo(outFile)) {
                tmpFile.copyTo(outFile, overwrite = true)
                tmpFile.delete()
            }

            AppLogger.i(TAG, "export done: ${outFile.absolutePath} (${outFile.length()} bytes)")
            return outFile
    }

    fun isOfficialOperitMigrationCompleted(context: Context): Boolean =
        context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(MIGRATION_COMPLETED, false)

    fun isOfficialOperitMigrationAvailable(context: Context): Boolean =
        context.packageName == RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE &&
            !isOfficialOperitMigrationCompleted(context) &&
            !isOfficialOperitMigrationPending(context)

    fun isOfficialOperitMigrationPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MIGRATION_PENDING, false)) return false
        // If the migration already completed but the pending flag is still set, the process was
        // killed between markOfficialMigrationCompleted (commit #1) and the pending clear
        // (commit #2). In the previous design this was a permanent boot loop: MainActivity would
        // keep entering the migration path, runPendingOfficialOperitMigration would throw on
        // the completion check, and the activity would exit on every launch. Clear the stale
        // pending flag and report "not pending" so the app enters normal mode.
        if (prefs.getBoolean(MIGRATION_COMPLETED, false)) {
            prefs.edit().remove(MIGRATION_PENDING).remove(MIGRATION_PENDING_URI).commit()
            return false
        }
        return true
    }

    /**
     * Persist a pending official Operit migration URI so the migration can run in a dedicated
     * cold-start phase before [com.ai.assistance.operit.core.application.OperitApplication.initializeMainApplication]
     * initializes WorkManager, foreground services, schedulers, repositories and DataStore writers.
     *
     * The caller must already hold a persistable read URI permission for [uri]. After persisting,
     * the caller should exit the process so the next cold start observes the pending flag.
     */
    fun setPendingOfficialOperitMigration(context: Context, uri: Uri) {
        check(context.packageName == RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE) {
            "Official Operit migration is only available in Operit Ry"
        }
        check(!isOfficialOperitMigrationCompleted(context)) {
            "Official Operit migration has already completed"
        }
        context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MIGRATION_PENDING, true)
            .putString(MIGRATION_PENDING_URI, uri.toString())
            .commit()
    }

    fun clearPendingOfficialOperitMigration(context: Context) {
        context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(MIGRATION_PENDING)
            .remove(MIGRATION_PENDING_URI)
            .commit()
    }

    private fun pendingOfficialOperitMigrationUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MIGRATION_PENDING, false)) return null
        val uriString = prefs.getString(MIGRATION_PENDING_URI, null) ?: return null
        return runCatching { Uri.parse(uriString) }.getOrNull()
    }

    fun isProcessRestartRequired(): Boolean = processRestartRequired

    suspend fun restoreFromBackupUri(
        context: Context,
        uri: Uri,
        onProgress: ((RestoreProgress) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!processRestartRequired) { "Application restart required before another snapshot operation" }
            restoreFromBackupUriLocked(
                context = context,
                uri = uri,
                expectedPackageName = context.packageName,
                markOfficialMigrationCompleted = false,
                prepareForReplacement = {
                    AppDatabase.closeDatabase()
                    ObjectBoxManager.closeAll()
                },
                onProgress = onProgress
            )
        }
    }

    /**
     * Run the pending official Operit migration in a dedicated cold-start phase.
     *
     * This must be called from [com.ai.assistance.operit.ui.main.MainActivity.onCreate] BEFORE
     * [com.ai.assistance.operit.core.application.OperitApplication.initializeMainApplication]
     * so that WorkManager, foreground services, schedulers, repositories and DataStore writers are
     * not running. At this point no background writer has been started, so the safety snapshot is
     * consistent and file replacement is not racing with concurrent writes.
     *
     * On success, the pending flag is cleared, the completion flag is committed, and the caller
     * should exit the process and let the next cold start enter normal mode.
     *
     * On failure, the pending flag is cleared to avoid a restart loop; the pre-migration safety
     * snapshot is retained so the user can recover manually. The caller should still exit the
     * process so the next cold start enters normal mode without the pending migration.
     *
     * Returns the safety backup file on success. Throws on failure.
     */
    suspend fun runPendingOfficialOperitMigration(
        context: Context,
        onProgress: ((RestoreProgress) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(context.packageName == RawSnapshotPackagePolicy.OPERIT_RY_PACKAGE) {
                "Official Operit migration is only available in Operit Ry"
            }
            check(!isOfficialOperitMigrationCompleted(context)) {
                "Official Operit migration has already completed"
            }
            val uri = pendingOfficialOperitMigrationUri(context)
                ?: throw IllegalStateException("No pending official Operit migration URI")

            lateinit var safetyBackup: File
            try {
                restoreFromBackupUriLocked(
                    context = context,
                    uri = uri,
                    expectedPackageName = RawSnapshotPackagePolicy.OFFICIAL_OPERIT_PACKAGE,
                    markOfficialMigrationCompleted = true,
                    prepareForReplacement = {
                        // In the dedicated cold-start phase no background writer is running yet,
                        // so a Room checkpoint is safe and the safety snapshot is consistent.
                        checkpointRoomDatabase(context)
                        processRestartRequired = true
                        AppDatabase.closeDatabase()
                        ObjectBoxManager.closeAll()
                        // Clear the pending state BEFORE taking the safety snapshot. Otherwise the
                        // safety snapshot (which captures shared_prefs) would contain pending=true
                        // and the source URI. A later restore of this safety snapshot for rollback
                        // would re-enter the cold-start migration path and overwrite the rolled-
                        // back data. The completion flag is committed only after replacement
                        // succeeds, so at this point neither pending nor completed is set, and a
                        // rollback restores the pre-migration state cleanly.
                        clearPendingOfficialOperitMigration(context)
                        // Safety snapshot must preserve terminal data because legacy v1 manifests
                        // default to includeTerminalData=true and migration may overwrite
                        // usr/tmp/bin. Without this, a failed migration or manual rollback could
                        // not restore pre-migration terminal data from the safety snapshot.
                        safetyBackup = exportToBackupDirLocked(
                            context = context,
                            options = SnapshotOptions(includeTerminalData = true),
                            onProgress = null,
                            performDatabaseCheckpoint = false
                        )
                    },
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                // The pending flag was already cleared in prepareForReplacement before the
                // safety snapshot was taken, so a rollback from the safety snapshot will not
                // re-enter the migration path. If the failure happened before
                // prepareForReplacement ran (e.g. invalid zip, package mismatch), clear the
                // pending flag here so the next cold start enters normal mode instead of
                // retrying the migration and looping. The safety snapshot (if created) is kept.
                clearPendingOfficialOperitMigration(context)
                throw e
            }
            // Success: the pending flag was already cleared in prepareForReplacement, and the
            // completion flag was committed inside restoreFromBackupUriLocked via
            // markOfficialMigrationCompleted. No further state update is needed.
            safetyBackup
        }
    }

    private suspend fun restoreFromBackupUriLocked(
        context: Context,
        uri: Uri,
        expectedPackageName: String,
        markOfficialMigrationCompleted: Boolean,
        prepareForReplacement: suspend () -> Unit,
        onProgress: ((RestoreProgress) -> Unit)?
    ) {
            val cacheZip = File.createTempFile("raw_snapshot_restore_", ".zip", context.cacheDir)
            val workDir = File(context.cacheDir, "raw_snapshot_restore_work").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            try {
                AppLogger.i(TAG, "restore start uri=$uri")
                withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.PREPARING) }
                withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.READING_ZIP) }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheZip).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Failed to open uri")

                AppLogger.i(TAG, "restore cached zip: ${cacheZip.absolutePath} (${cacheZip.length()} bytes)")

                withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.EXTRACTING) }
                val manifest = extractZipToWorkDir(cacheZip, workDir, expectedPackageName)

                val payloadDir = File(workDir, "payload")
                val externalFilesPayloadDir = File(payloadDir, "external_files")

                val alwaysExcluded = OperitPaths.rawSnapshotExcludedFilesTopLevelDirNames()

                val preserveTerminal = !manifest.includeTerminalData
                val preservedTerminalNames = if (preserveTerminal) terminalTopLevelDirNames else emptySet()
                val preservedAlwaysExcludedNames = alwaysExcluded.filterNot { dirName ->
                    File(payloadDir, "files/$dirName").exists()
                }.toSet()
                val preservedNames = preservedTerminalNames + preservedAlwaysExcludedNames

                AppLogger.i(
                    TAG,
                    "restore manifest ok (formatVersion=${manifest.formatVersion}, includeTerminalData=${manifest.includeTerminalData})"
                )

                withContext(NonCancellable) {
                    prepareForReplacement()

                    AppLogger.i(TAG, "restore closed databases (room + objectbox)")
                    AppLogger.i(TAG, "restore replace dirs (preserveTerminalTopLevel=${preservedNames.isNotEmpty()})")

                    withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_FILES) }
                    replaceDirContents(File(payloadDir, "files"), context.filesDir, preservedTopLevelDirNames = preservedNames)
                    if (externalFilesPayloadDir.exists()) {
                        val externalFilesDir = requireNotNull(context.getExternalFilesDir(null)) {
                            "External files dir is unavailable"
                        }
                        withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_EXTERNAL_FILES) }
                        replaceDirContents(externalFilesPayloadDir, externalFilesDir)
                    }
                    withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_SHARED_PREFS) }
                    replaceDirContents(File(payloadDir, "shared_prefs"), File(context.dataDir, "shared_prefs"))
                    withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_DATASTORE) }
                    replaceDirContents(File(payloadDir, "datastore"), File(context.dataDir, "datastore"))
                    withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.REPLACING_DATABASES) }
                    replaceDirContents(File(payloadDir, "databases"), File(context.dataDir, "databases"))

                    if (markOfficialMigrationCompleted) {
                        val recorded = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean(MIGRATION_COMPLETED, true)
                            .commit()
                        check(recorded) { "Failed to record official Operit migration completion" }
                    }

                    withContext(Dispatchers.Main) { onProgress?.invoke(RestoreProgress.FINALIZING) }
                }

                AppLogger.i(TAG, "restore done: ${manifest.packageName}")
            } catch (e: Exception) {
                AppLogger.e(TAG, "restore failed", e)
                throw e
            } finally {
                try {
                    cacheZip.delete()
                } catch (_: Exception) {
                }
                try {
                    workDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
    }

    private fun extractZipToWorkDir(zipFile: File, workDir: File, expectedPackageName: String): Manifest {
        val payloadRoot = File(workDir, "payload")
        payloadRoot.mkdirs()

        var manifestText: String? = null
        var extractedPayloadFiles = 0
        val supportedPayloadPrefixes = listOf(
            ENTRY_FILES,
            ENTRY_EXTERNAL_FILES,
            ENTRY_SHARED_PREFS,
            ENTRY_DATASTORE,
            ENTRY_DATABASES
        )

        val buffer = ByteArray(64 * 1024)
        val extractMs = measureTimeMillis {
            ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name

                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }

                    if (name == ENTRY_MANIFEST) {
                        val bytes = zis.readBytesSafely(maxBytes = 512 * 1024)
                        manifestText = bytes.toString(Charsets.UTF_8)
                        zis.closeEntry()
                        continue
                    }

                    if (!name.startsWith(ENTRY_PAYLOAD_PREFIX)) {
                        zis.closeEntry()
                        continue
                    }

                    val payloadPrefix = supportedPayloadPrefixes.firstOrNull { prefix ->
                        name.startsWith(prefix)
                    }
                    if (payloadPrefix == null) {
                        zis.closeEntry()
                        throw IllegalArgumentException("Unsupported payload entry: $name")
                    }

                    val target = File(workDir, name)
                    val workCanonical = workDir.canonicalFile
                    val targetCanonical = target.canonicalFile
                    if (!targetCanonical.path.startsWith(workCanonical.path + File.separator)) {
                        zis.closeEntry()
                        throw IllegalArgumentException("Invalid zip entry path: $name")
                    }
                    val payloadRootCanonical = File(workDir, payloadPrefix).canonicalFile
                    if (!targetCanonical.path.startsWith(payloadRootCanonical.path + File.separator)) {
                        zis.closeEntry()
                        throw IllegalArgumentException("Invalid payload entry path: $name")
                    }

                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        while (true) {
                            val read = zis.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                    }

                    extractedPayloadFiles++

                    zis.closeEntry()
                }
            }
        }

        AppLogger.i(TAG, "restore extract done in ${extractMs}ms (payloadFiles=$extractedPayloadFiles)")

        val manifest = manifestText?.let { json.decodeFromString(Manifest.serializer(), it) }
            ?: throw IllegalArgumentException("Invalid backup zip: missing $ENTRY_MANIFEST")

        if (manifest.formatVersion != FORMAT_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: ${manifest.formatVersion}")
        }

        RawSnapshotPackagePolicy.requireSourcePackage(manifest.packageName, expectedPackageName)

        val requiredIncludes = listOf(
            ENTRY_FILES,
            ENTRY_EXTERNAL_FILES,
            ENTRY_SHARED_PREFS,
            ENTRY_DATASTORE,
            ENTRY_DATABASES
        )
        val legacyIncludes = requiredIncludes - ENTRY_EXTERNAL_FILES
        RawSnapshotPackagePolicy.requireArchiveContents(
            actualIncludes = manifest.includes,
            supportedIncludes = listOf(requiredIncludes, legacyIncludes),
            payloadFileCount = extractedPayloadFiles
        )

        return manifest
    }

    private fun checkpointRoomDatabase(context: Context) {
        val sqliteDb = AppDatabase.getDatabase(context).openHelper.writableDatabase
        sqliteDb.query("PRAGMA wal_checkpoint(FULL)").close()
    }

    private fun addDirToZip(
        zos: ZipOutputStream,
        dir: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String> = emptySet(),
        totalFiles: Int = 0,
        onPercentChanged: ((Int) -> Unit)? = null
    ) {
        if (!dir.exists() || !dir.isDirectory) return

        val baseCanonical = dir.canonicalFile
        val buffer = ByteArray(64 * 1024)
        val writtenEntryNames = HashSet<String>()

        var processedFiles = 0
        var lastPercent = -1

        dir.walkTopDown().onEnter { currentDir ->
            !shouldPruneDirForZip(currentDir, dir, entryPrefix, excludedTopLevelDirNames)
        }.forEach { f ->
            if (!f.isFile) return@forEach

            val canonical = f.canonicalFile
            if (shouldSkipForZip(canonical, baseCanonical, entryPrefix, excludedTopLevelDirNames)) {
                if (canonical.name == "lock.mdb" && canonical.parentFile?.name?.startsWith("objectbox") == true) {
                    AppLogger.w(TAG, "export skip objectbox lock file: ${canonical.absolutePath}")
                }
                return@forEach
            }

            val rel = canonical.path.substring(baseCanonical.path.length + 1)
            val entryName = entryPrefix + rel.replace(File.separatorChar, '/')

            if (!writtenEntryNames.add(entryName)) {
                AppLogger.w(TAG, "export skip duplicate entry: $entryName")
                return@forEach
            }

            zos.putNextEntry(ZipEntry(entryName))
            BufferedInputStream(FileInputStream(canonical)).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    zos.write(buffer, 0, read)
                }
            }
            zos.closeEntry()

            if (totalFiles > 0 && onPercentChanged != null) {
                processedFiles++
                val percent = ((processedFiles * 100) / totalFiles).coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onPercentChanged(percent)
                }
            }
        }
    }

    private fun shouldPruneDirForZip(
        currentDir: File,
        baseDir: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String>
    ): Boolean {
        if (currentDir == baseDir) return false
        val parent = currentDir.parentFile ?: return false
        if (parent != baseDir) return false

        val name = currentDir.name
        if (excludedTopLevelDirNames.contains(name)) return true

        if (entryPrefix == ENTRY_FILES) {
            if (name.startsWith("sherpa-ncnn-")) return true
        }

        return false
    }

    private fun shouldSkipForZip(
        canonical: File,
        baseCanonical: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String>
    ): Boolean {
        if (!canonical.path.startsWith(baseCanonical.path + File.separator)) return true

        if (canonical.name == "lock.mdb" && canonical.parentFile?.name?.startsWith("objectbox") == true) {
            return true
        }

        val rel = canonical.path.substring(baseCanonical.path.length + 1)
        val relNormalized = rel.replace(File.separatorChar, '/')
        val top = relNormalized.substringBefore('/', missingDelimiterValue = relNormalized)
        if (excludedTopLevelDirNames.isNotEmpty() && excludedTopLevelDirNames.contains(top)) {
            return true
        }

        if (entryPrefix == ENTRY_FILES) {
            if (top.startsWith("sherpa-ncnn-")) {
                return true
            }

            // Exclude Ubuntu rootfs package (very large). Stored as a top-level file in filesDir.
            if (!relNormalized.contains('/')) {
                val name = relNormalized
                if (name.startsWith("ubuntu-", ignoreCase = true) && name.endsWith(".tar.xz", ignoreCase = true)) {
                    return true
                }
            }

            if (!relNormalized.contains('/')) {
                if (relNormalized.startsWith("memory_hnsw_") && relNormalized.endsWith(".idx")) {
                    return true
                }
                if (relNormalized.startsWith("doc_index_") && relNormalized.endsWith(".hnsw")) {
                    return true
                }
            }
        }

        return false
    }

    private fun totalFilesForZip(
        dir: File,
        entryPrefix: String,
        excludedTopLevelDirNames: Set<String>,
        onScannedCountChanged: ((Int) -> Unit)? = null
    ): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        val baseCanonical = dir.canonicalFile
        var total = 0

        var lastReported = 0
        var lastReportAtMs = 0L
        dir.walkTopDown().onEnter { currentDir ->
            !shouldPruneDirForZip(currentDir, dir, entryPrefix, excludedTopLevelDirNames)
        }.forEach { f ->
            if (!f.isFile) return@forEach
            val canonical = f.canonicalFile
            if (shouldSkipForZip(canonical, baseCanonical, entryPrefix, excludedTopLevelDirNames)) return@forEach
            total++

            if (onScannedCountChanged != null) {
                val now = System.currentTimeMillis()
                if (total == 1 || total - lastReported >= 200 || now - lastReportAtMs >= 250L) {
                    lastReported = total
                    lastReportAtMs = now
                    onScannedCountChanged(total)
                }
            }
        }
        return total
    }

    private fun replaceDirContents(
        fromDir: File,
        toDir: File,
        preservedTopLevelDirNames: Set<String> = emptySet()
    ) {
        if (!toDir.exists()) {
            toDir.mkdirs()
        }

        if (!fromDir.exists() || !fromDir.isDirectory) return

        // Non-destructive restore: only overwrite files present in the backup.
        // Files not present in the backup are preserved.
        copyDir(fromDir, toDir, preservedTopLevelDirNames)
    }

    private fun copyDir(
        fromDir: File,
        toDir: File,
        preservedTopLevelDirNames: Set<String>
    ) {
        val baseCanonical = fromDir.canonicalFile
        fromDir.walkTopDown().forEach { f ->
            val canonical = f.canonicalFile
            if (!canonical.path.startsWith(baseCanonical.path + File.separator) && canonical != baseCanonical) {
                return@forEach
            }

            if (canonical == baseCanonical) return@forEach

            val rel = canonical.path.substring(baseCanonical.path.length + 1)
            if (preservedTopLevelDirNames.isNotEmpty()) {
                val relNormalized = rel.replace(File.separatorChar, '/')
                val top = relNormalized.substringBefore('/', missingDelimiterValue = relNormalized)
                if (preservedTopLevelDirNames.contains(top)) {
                    return@forEach
                }
            }
            val target = File(toDir, rel)

            if (canonical.isDirectory) {
                target.mkdirs()
            } else if (canonical.isFile) {
                target.parentFile?.mkdirs()
                canonical.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun ZipInputStream.readBytesSafely(maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            if (out.size() + read > maxBytes) {
                throw IllegalArgumentException("Zip entry too large")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

package com.ai.assistance.operit.data.preferences

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import java.io.File

private val Context.legacyStorageDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "legacy_storage_settings")

/**
 * Preferences for the legacy `Download/Operit` read-only compatibility layer.
 *
 * Kept in its own DataStore instead of [UserPreferencesManager] (which already holds 90+ keys)
 * because these flags are conceptually a self-contained storage-migration concern.
 *
 * Three independent read switches (Skill / MCP / workflow) gate whether the corresponding
 * legacy directory under `Download/Operit` is scanned at load time. Default values are seeded
 * once by [LegacyStorageInitializer]: on if a non-empty legacy directory existed at first run,
 * off otherwise (including fresh installs). After initialization the user's choice is final and
 * the app never re-flips the switches automatically.
 *
 * Three hidden-lists record legacy entries the user has deleted in-app so that they do not
 * reappear on the next scan:
 * - [hiddenLegacySkillPaths] keys by relative skill directory path (the YAML `name` can change,
 *   so the directory path is the stable identity).
 * - [hiddenLegacyMcpServerIds] keys MCP servers and metadata by stable server id.
 * - [hiddenLegacyWorkflowIds] keys by workflow id (the JSON filename stem).
 *
 * The one-shot initialization guard deliberately lives in an [AtomicFile] under
 * [Context.noBackupFilesDir] (excluded from raw snapshots), while the switches themselves live
 * in DataStore and are included in snapshots. Restoring a snapshot created before the switches
 * existed can therefore leave the guard present but the keys absent; [LegacyStorageInitializer]
 * repairs only those missing keys from the current legacy-directory defaults and never
 * overwrites an explicit restored choice. A process-wide lock serializes both seed and repair.
 */
class LegacyStoragePreferences private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: LegacyStoragePreferences? = null

        fun getInstance(context: Context): LegacyStoragePreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LegacyStoragePreferences(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        private const val TAG = "LegacyStoragePreferences"

        private val KEY_READ_LEGACY_SKILLS = booleanPreferencesKey("read_legacy_download_skills")
        private val KEY_READ_LEGACY_MCP = booleanPreferencesKey("read_legacy_download_mcp")
        private val KEY_READ_LEGACY_WORKFLOWS =
            booleanPreferencesKey("read_legacy_download_workflows")

        private val KEY_HIDDEN_LEGACY_SKILL_PATHS =
            stringSetPreferencesKey("hidden_legacy_skill_paths")
        private val KEY_HIDDEN_LEGACY_MCP_SERVER_IDS =
            stringSetPreferencesKey("hidden_legacy_mcp_server_ids")
        private val KEY_HIDDEN_LEGACY_WORKFLOW_IDS =
            stringSetPreferencesKey("hidden_legacy_workflow_ids")

        private const val INIT_FILE = "legacy_storage_initialized.txt"
        private const val INITIALIZER_DONE = "INITIALIZED"

        // Serializes check-and-seed across concurrent initializeIfNeeded() callers within one
        // process. The initialized flag on disk is the durable guard; this lock prevents two
        // coroutines from both observing "uninitialized" and seeding twice.
        private val initMutex = kotlinx.coroutines.sync.Mutex()
    }

    private val appContext: Context get() = context.applicationContext

    // ------------------------------------------------------------------
    // Read switches (flow + suspend snapshot)
    // ------------------------------------------------------------------

    fun readLegacySkillsFlow(): Flow<Boolean> =
        context.legacyStorageDataStore.data.map { it[KEY_READ_LEGACY_SKILLS] ?: false }

    fun readLegacyMcpFlow(): Flow<Boolean> =
        context.legacyStorageDataStore.data.map { it[KEY_READ_LEGACY_MCP] ?: false }

    fun readLegacyWorkflowsFlow(): Flow<Boolean> =
        context.legacyStorageDataStore.data.map { it[KEY_READ_LEGACY_WORKFLOWS] ?: false }

    suspend fun isReadLegacySkills(): Boolean = readLegacySkillsFlow().first()
    suspend fun isReadLegacyMcp(): Boolean = readLegacyMcpFlow().first()
    suspend fun isReadLegacyWorkflows(): Boolean = readLegacyWorkflowsFlow().first()

    suspend fun setReadLegacySkills(value: Boolean) {
        context.legacyStorageDataStore.edit { it[KEY_READ_LEGACY_SKILLS] = value }
    }

    suspend fun setReadLegacyMcp(value: Boolean) {
        context.legacyStorageDataStore.edit { it[KEY_READ_LEGACY_MCP] = value }
    }

    suspend fun setReadLegacyWorkflows(value: Boolean) {
        context.legacyStorageDataStore.edit { it[KEY_READ_LEGACY_WORKFLOWS] = value }
    }

    /**
     * True only when all three switch keys exist in the current DataStore snapshot. A raw
     * snapshot created before these keys existed can replace the DataStore while leaving the
     * no-backup initialization marker intact, so the marker alone is not sufficient evidence
     * that the switches are still present.
     */
    suspend fun hasCompleteReadSwitchState(): Boolean {
        val prefs = context.legacyStorageDataStore.data.first()
        return prefs[KEY_READ_LEGACY_SKILLS] != null &&
            prefs[KEY_READ_LEGACY_MCP] != null &&
            prefs[KEY_READ_LEGACY_WORKFLOWS] != null
    }

    /**
     * Restores only missing switch keys after a pre-feature raw snapshot restore. Existing keys
     * are never overwritten, preserving every explicit user choice (including `false`).
     */
    suspend fun restoreMissingReadSwitches(
        readSkills: Boolean,
        readMcp: Boolean,
        readWorkflows: Boolean,
    ): Boolean = initMutex.withLock {
        var changed = false
        context.legacyStorageDataStore.edit { prefs ->
            val repaired = repairMissingLegacyReadSwitches(
                current = LegacyReadSwitchValues(
                    skills = prefs[KEY_READ_LEGACY_SKILLS],
                    mcp = prefs[KEY_READ_LEGACY_MCP],
                    workflows = prefs[KEY_READ_LEGACY_WORKFLOWS],
                ),
                defaults = LegacyReadSwitchValues(readSkills, readMcp, readWorkflows),
            )
            if (prefs[KEY_READ_LEGACY_SKILLS] == null) {
                prefs[KEY_READ_LEGACY_SKILLS] = requireNotNull(repaired.skills)
                changed = true
            }
            if (prefs[KEY_READ_LEGACY_MCP] == null) {
                prefs[KEY_READ_LEGACY_MCP] = requireNotNull(repaired.mcp)
                changed = true
            }
            if (prefs[KEY_READ_LEGACY_WORKFLOWS] == null) {
                prefs[KEY_READ_LEGACY_WORKFLOWS] = requireNotNull(repaired.workflows)
                changed = true
            }
        }
        if (changed) {
            AppLogger.i(
                TAG,
                "restored missing legacy storage flags: " +
                    "skills=$readSkills mcp=$readMcp workflows=$readWorkflows",
            )
        }
        changed
    }

    // ------------------------------------------------------------------
    // One-shot initialization guard (AtomicFile in noBackupFilesDir).
    // ------------------------------------------------------------------

    /**
     * True once [seed] has completed. Backed by an [AtomicFile] under [Context.noBackupFilesDir]
     * so raw-snapshot restore cannot roll it back and re-trigger auto-seeding.
     */
    fun isInitialized(): Boolean {
        val file = initFile()
        // AtomicFile.startWrite would create the file; we only probe existing state here.
        return file.isFile || File(file.absolutePath + ".bak").isFile
    }

    /**
     * Atomically seeds all three read switches and marks initialization complete. Returns true
     * if this call performed the seed, false if initialization had already completed (either on
     * disk before this call, or concurrently by another caller that won the [initMutex]).
     *
     * The check-and-seed is serialized by [initMutex] so concurrent callers cannot both seed.
     * The durable guard is the [AtomicFile] written last: if the process dies between the
     * DataStore edit and the AtomicFile write, the next launch re-runs seed, which is safe
     * (seed overwrites the same defaults). The AtomicFile write itself is atomic, so a crash
     * mid-write leaves the previous (absent) state, not a corrupt hybrid.
     */
    suspend fun seedOnce(
        readSkills: Boolean,
        readMcp: Boolean,
        readWorkflows: Boolean
    ): Boolean = initMutex.withLock {
        if (isInitialized()) {
            return@withLock false
        }
        context.legacyStorageDataStore.edit { prefs ->
            prefs[KEY_READ_LEGACY_SKILLS] = readSkills
            prefs[KEY_READ_LEGACY_MCP] = readMcp
            prefs[KEY_READ_LEGACY_WORKFLOWS] = readWorkflows
        }
        writeInitializedMarker()
        AppLogger.i(
            TAG,
            "seeded legacy storage flags: skills=$readSkills mcp=$readMcp workflows=$readWorkflows"
        )
        true
    }

    private fun initFile(): File = File(appContext.noBackupFilesDir, INIT_FILE)

    private fun writeInitializedMarker() {
        val file = initFile()
        val parent = file.parentFile
            ?: throw IllegalStateException("noBackupFilesDir has no parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Failed to create noBackupFilesDir for legacy storage init")
        }
        val atomicFile = AtomicFile(file)
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(INITIALIZER_DONE.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            output = null
        } catch (e: Exception) {
            try {
                output?.let { atomicFile.failWrite(it) }
            } catch (rollbackError: Exception) {
                AppLogger.e(TAG, "failed to roll back legacy storage init marker write", rollbackError)
            }
            throw e
        }
    }

    // ------------------------------------------------------------------
    // Hidden legacy entries (delete-in-app without touching the source file)
    // ------------------------------------------------------------------

    fun hiddenLegacySkillPathsFlow(): Flow<Set<String>> =
        context.legacyStorageDataStore.data.map { it[KEY_HIDDEN_LEGACY_SKILL_PATHS] ?: emptySet() }

    fun hiddenLegacyMcpServerIdsFlow(): Flow<Set<String>> =
        context.legacyStorageDataStore.data.map {
            it[KEY_HIDDEN_LEGACY_MCP_SERVER_IDS] ?: emptySet()
        }

    fun hiddenLegacyWorkflowIdsFlow(): Flow<Set<String>> =
        context.legacyStorageDataStore.data.map { it[KEY_HIDDEN_LEGACY_WORKFLOW_IDS] ?: emptySet() }

    suspend fun hiddenLegacySkillPaths(): Set<String> = hiddenLegacySkillPathsFlow().first()
    suspend fun hiddenLegacyMcpServerIds(): Set<String> = hiddenLegacyMcpServerIdsFlow().first()
    suspend fun hiddenLegacyWorkflowIds(): Set<String> = hiddenLegacyWorkflowIdsFlow().first()

    suspend fun hideLegacySkillPath(relativePath: String) {
        if (relativePath.isBlank()) return
        context.legacyStorageDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_LEGACY_SKILL_PATHS] ?: emptySet()
            prefs[KEY_HIDDEN_LEGACY_SKILL_PATHS] = current + relativePath
        }
    }

    suspend fun hideLegacyWorkflowId(workflowId: String) {
        if (workflowId.isBlank()) return
        context.legacyStorageDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_LEGACY_WORKFLOW_IDS] ?: emptySet()
            prefs[KEY_HIDDEN_LEGACY_WORKFLOW_IDS] = current + workflowId
        }
    }

    suspend fun hideLegacyMcpServerId(serverId: String) {
        if (serverId.isBlank()) return
        context.legacyStorageDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_LEGACY_MCP_SERVER_IDS] ?: emptySet()
            prefs[KEY_HIDDEN_LEGACY_MCP_SERVER_IDS] = current + serverId
        }
    }

    suspend fun unhideLegacySkillPath(relativePath: String) {
        context.legacyStorageDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_LEGACY_SKILL_PATHS] ?: emptySet()
            if (relativePath in current) {
                prefs[KEY_HIDDEN_LEGACY_SKILL_PATHS] = current - relativePath
            }
        }
    }

    suspend fun unhideLegacyWorkflowId(workflowId: String) {
        context.legacyStorageDataStore.edit { prefs ->
            val current = prefs[KEY_HIDDEN_LEGACY_WORKFLOW_IDS] ?: emptySet()
            if (workflowId in current) {
                prefs[KEY_HIDDEN_LEGACY_WORKFLOW_IDS] = current - workflowId
            }
        }
    }

    /** Clears the workflow hidden-list, used by the "restore all Download workflows" action. */
    suspend fun clearHiddenLegacyWorkflowIds() {
        context.legacyStorageDataStore.edit { prefs ->
            prefs.remove(KEY_HIDDEN_LEGACY_WORKFLOW_IDS)
        }
    }

    /** Clears the skill hidden-list, used by the "restore all Download skills" action. */
    suspend fun clearHiddenLegacySkillPaths() {
        context.legacyStorageDataStore.edit { prefs ->
            prefs.remove(KEY_HIDDEN_LEGACY_SKILL_PATHS)
        }
    }
}

internal data class LegacyReadSwitchValues(
    val skills: Boolean?,
    val mcp: Boolean?,
    val workflows: Boolean?,
)

/** Fills only missing restored keys; explicit true/false values always win over defaults. */
internal fun repairMissingLegacyReadSwitches(
    current: LegacyReadSwitchValues,
    defaults: LegacyReadSwitchValues,
): LegacyReadSwitchValues =
    LegacyReadSwitchValues(
        skills = current.skills ?: defaults.skills,
        mcp = current.mcp ?: defaults.mcp,
        workflows = current.workflows ?: defaults.workflows,
    )

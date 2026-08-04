package com.ai.assistance.operit.util

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Central path layer for Operit-managed user content (Skills, MCP, workflows).
 *
 * Phase 1 of the storage migration introduces this class as the single source of truth for the
 * three content roots. It is purely additive: existing call sites in [SkillManager],
 * [WorkflowRepository], [MCPLocalServer] and [MCPRepository] still resolve the legacy
 * `Download/Operit/...` paths themselves, and their behavior is unchanged. Later phases will
 * retarget those call sites at the internal roots declared here.
 *
 * Design rules (enforced across all migration phases):
 *
 * - The app-internal private directory is the primary store and the only default write target.
 * - The public `Download/Operit` tree is an optional, read-only compatibility source whose
 *   existence must never be recreated by the act of probing it. Legacy accessors therefore
 *   return [File] handles without calling [File.mkdirs].
 * - Runtime state (MCP server status, workflow execution logs) lives under
 *   [Context.noBackupFilesDir] so that raw-snapshot restore does not roll back a half-finished
 *   storage migration, mirroring [com.ai.assistance.operit.data.backup.MigrationStateStore].
 */
class OperitManagedPaths(private val context: Context) {

    private val appContext: Context get() = context.applicationContext

    // ------------------------------------------------------------------
    // Internal primary storage (filesDir). Writing here is always allowed.
    // ------------------------------------------------------------------

    /** `filesDir/operit` — the managed root. Created on first access. */
    val internalRoot: File
        get() = ensureDir(File(appContext.filesDir, OPERIT_ROOT_NAME))

    /** `filesDir/operit/skills`. */
    val internalSkills: File
        get() = ensureDir(File(internalRoot, SKILLS_DIR_NAME))

    /** `filesDir/operit/mcp`. */
    val internalMcpRoot: File
        get() = ensureDir(File(internalRoot, MCP_DIR_NAME))

    /** `filesDir/operit/mcp/mcp_config.json`. */
    val internalMcpConfig: File
        get() = File(internalMcpRoot, MCP_CONFIG_FILE_NAME)

    /** `filesDir/operit/mcp/packages`. */
    val internalMcpPackages: File
        get() = ensureDir(File(internalMcpRoot, MCP_PACKAGES_DIR_NAME))

    /** `filesDir/operit/workflows/definitions`. */
    val internalWorkflows: File
        get() = ensureDir(File(internalRoot, "$WORKFLOWS_DIR_NAME/$WORKFLOW_DEFINITIONS_DIR_NAME"))

    // ------------------------------------------------------------------
    // Runtime state (noBackupFilesDir). Excluded from raw snapshots so that
    // a snapshot restore cannot re-enter or revert a storage migration.
    // ------------------------------------------------------------------

    private val noBackupRoot: File
        get() = ensureDir(File(appContext.noBackupFilesDir, OPERIT_ROOT_NAME))

    /** `noBackupFilesDir/operit/mcp/server_status.json`. Regenerable runtime cache. */
    val internalMcpStatus: File
        get() = File(ensureDir(File(noBackupRoot, MCP_DIR_NAME)), MCP_STATUS_FILE_NAME)

    /** `noBackupFilesDir/operit/workflows/execution_logs`. */
    val internalWorkflowLogs: File
        get() = ensureDir(File(noBackupRoot, "$WORKFLOWS_DIR_NAME/$WORKFLOW_LOGS_DIR_NAME"))

    // ------------------------------------------------------------------
    // Legacy compatibility sources (Download/Operit).
    // IMPORTANT: these accessors MUST NOT create directories. Reading a legacy
    // path must never resurrect `Download/Operit` after the user disabled
    // compatibility and deleted the old tree. Use [legacyDirOrNull] for scans.
    // ------------------------------------------------------------------

    /**
     * `Download/Operit` without side effects. Returns the [File] regardless of existence;
     * callers must check [File.exists] / [File.isDirectory] before scanning and must never
     * call [File.mkdirs] on this tree.
     */
    val legacyRoot: File
        get() = File(legacyDownloadsRoot(), LEGACY_OPERIT_DIR_NAME)

    /** `Download/Operit/skills` (matches `SkillManager` hardcode). Non-creating. */
    val legacySkills: File
        get() = File(legacyRoot, LEGACY_SKILLS_DIR_NAME)

    /** `Download/Operit/mcp_plugins` (matches `OperitPaths.mcpPluginsDir`). Non-creating. */
    val legacyMcp: File
        get() = File(legacyRoot, LEGACY_MCP_PLUGINS_DIR_NAME)

    /**
     * `Download/Operit/workflow` (singular — matches `WorkflowRepository.WORKFLOW_DIR`).
     * Non-creating.
     */
    val legacyWorkflows: File
        get() = File(legacyRoot, LEGACY_WORKFLOW_DIR_NAME)

    /**
     * Returns [legacyRoot] only when it already exists as a directory, else null. Use this for
     * scans so a missing legacy tree never triggers accidental creation downstream.
     */
    fun legacyRootOrNull(): File? = legacyRoot.takeIf { it.isDirectory }

    /** Convenience: true when [legacyRoot] exists and contains at least one entry. */
    fun legacyRootHasContent(): Boolean = legacyRootOrNull()?.listFiles()?.isNotEmpty() == true

    private fun legacyDownloadsRoot(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    private fun ensureDir(dir: File): File {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    companion object {
        private const val OPERIT_ROOT_NAME = "operit"
        private const val SKILLS_DIR_NAME = "skills"
        private const val MCP_DIR_NAME = "mcp"
        private const val MCP_CONFIG_FILE_NAME = "mcp_config.json"
        private const val MCP_PACKAGES_DIR_NAME = "packages"
        private const val MCP_STATUS_FILE_NAME = "server_status.json"
        private const val WORKFLOWS_DIR_NAME = "workflows"
        private const val WORKFLOW_DEFINITIONS_DIR_NAME = "definitions"
        private const val WORKFLOW_LOGS_DIR_NAME = "execution_logs"

        // Legacy directory names must exactly match the historical hardcodes:
        //   SkillManager     -> Download/Operit/skills
        //   OperitPaths       -> Download/Operit/mcp_plugins
        //   WorkflowRepository -> Download/Operit/workflow   (singular)
        const val LEGACY_OPERIT_DIR_NAME = "Operit"
        const val LEGACY_SKILLS_DIR_NAME = "skills"
        const val LEGACY_MCP_PLUGINS_DIR_NAME = "mcp_plugins"
        const val LEGACY_WORKFLOW_DIR_NAME = "workflow"
    }
}
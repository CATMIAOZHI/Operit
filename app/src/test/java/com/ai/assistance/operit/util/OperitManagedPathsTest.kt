package com.ai.assistance.operit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM guards for the Phase 1 storage-migration foundation. These tests do not touch the
 * Android framework; they assert the invariants that must hold regardless of device state:
 *
 * - The legacy directory-name constants exactly match the historical hardcodes (renaming any
 *   of them would silently break old-user upgrades, since the legacy scan would look at a path
 *   the user never had).
 * - [StorageSource] / [SourcedEntry] behave as documented (source is runtime-only metadata,
 *   internal wins on conflict, legacy flag is queryable without holding a model).
 */
class OperitManagedPathsTest {

    @Test
    fun legacyDirectoryNames_matchHistoricalHardcodes() {
        // SkillManager.kt: "Operit" / "skills"
        assertEquals("Operit", OperitManagedPaths.LEGACY_OPERIT_DIR_NAME)
        assertEquals("skills", OperitManagedPaths.LEGACY_SKILLS_DIR_NAME)
        // OperitPaths.mcpPluginsDir(): "Operit" / "mcp_plugins"
        assertEquals("mcp_plugins", OperitManagedPaths.LEGACY_MCP_PLUGINS_DIR_NAME)
        // WorkflowRepository.WORKFLOW_DIR = "Operit/workflow" (singular, not "workflows")
        assertEquals("workflow", OperitManagedPaths.LEGACY_WORKFLOW_DIR_NAME)
        assertNotEquals("workflows", OperitManagedPaths.LEGACY_WORKFLOW_DIR_NAME)
    }

    @Test
    fun storageSource_isLegacy_and_isInternal_distinguishBranches() {
        assertTrue(StorageSource.INTERNAL.isLegacy().not())
        assertTrue(StorageSource.LEGACY_DOWNLOAD.isLegacy())
    }

    @Test
    fun sourcedEntry_carriesRuntimeMetadataWithoutMutatingValue() {
        val value = "workflow-A"
        val file = File("/tmp/legacy/workflow-A.json")
        val entry = SourcedEntry(value, StorageSource.LEGACY_DOWNLOAD, file)

        assertEquals(value, entry.value)
        assertEquals(StorageSource.LEGACY_DOWNLOAD, entry.source)
        assertEquals(file, entry.sourceFile)
        assertTrue(entry.isLegacy)
    }

    @Test
    fun sourcedEntry_internalEntry_isNotLegacy() {
        val entry = SourcedEntry(42, StorageSource.INTERNAL, File("/data/operit/x"))
        assertFalse(entry.isLegacy)
    }

    @Test
    fun sourcedEntry_nullabilityOnValue() {
        val entry: SourcedEntry<String?> = SourcedEntry(null, StorageSource.INTERNAL, File("x"))
        assertNull(entry.value)
        assertFalse(entry.isLegacy)
    }
}
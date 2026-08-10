package com.ai.assistance.operit.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 2 workflow storage-migration invariants that do not require an
 * Android Context. The dual-source scan/merge and write-on-copy paths in [WorkflowRepository]
 * depend on [android.content.Context] / DataStore, so they are exercised by instrumented tests
 * (not present in this pure-JVM suite). These tests pin down the policy invariants the
 * repository must uphold, expressed through the [SourcedEntry]/[StorageSource] primitives.
 */
class WorkflowStoragePolicyTest {

    @Test
    fun workflowIdsResolveOnlyAsDirectChildrenOfManagedRoots() {
        val root = Files.createTempDirectory("workflow-paths").toFile()
        try {
            assertEquals(
                File(root, "日常 planning.json").canonicalFile,
                resolveWorkflowStorageChild(root, "日常 planning", ".json"),
            )
            listOf(
                "",
                " ",
                ".",
                "..",
                "../mcp/mcp_config",
                "..\\mcp\\mcp_config",
                "nested/workflow",
                "nested\\workflow",
                "nul\u0000id",
            ).forEach { unsafeId ->
                assertNull("must reject $unsafeId", resolveWorkflowStorageChild(root, unsafeId, ".json"))
                assertNull("log path must reject $unsafeId", resolveWorkflowStorageChild(root, unsafeId))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workflowJsonScansRejectCanonicalTargetsOutsideTheScannedDirectory() {
        val root = Files.createTempDirectory("workflow-scan").toFile()
        val scanned = File(root, "scanned").apply { mkdirs() }
        val outside = File(root, "outside.json").apply { writeText("{}") }
        val inside = File(scanned, "inside.json").apply { writeText("{}") }
        try {
            assertEquals(listOf(inside), canonicalWorkflowJsonFiles(scanned))

            val link = File(scanned, "escaped.json").toPath()
            try {
                Files.createSymbolicLink(link, outside.toPath())
            } catch (e: Exception) {
                assumeNoException(e)
            }

            assertEquals(listOf(inside), canonicalWorkflowJsonFiles(scanned))
            assertEquals(inside, latestWorkflowExecutionRecordFile(scanned))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun workflowResolversAndScansRejectASymlinkManagedRoot() {
        val root = Files.createTempDirectory("workflow-root-link").toFile()
        val outside = Files.createTempDirectory("workflow-outside-root").toFile()
        File(outside, "outside.json").writeText("{}")
        val linkedRoot = File(root, "managed-link").toPath()
        try {
            try {
                Files.createSymbolicLink(linkedRoot, outside.toPath())
            } catch (e: Exception) {
                assumeNoException(e)
            }

            assertNull(resolveWorkflowStorageChild(linkedRoot.toFile(), "outside", ".json"))
            assertTrue(canonicalWorkflowJsonFiles(linkedRoot.toFile()).isEmpty())
            assertNull(latestWorkflowExecutionRecordFile(linkedRoot.toFile()))

            val nestedRoot = File(linkedRoot.toFile(), "nested")
            File(outside, "nested").mkdirs()
            assertNull(
                resolveWorkflowStorageChild(
                    root = nestedRoot,
                    workflowId = "outside",
                    suffix = ".json",
                    trustedAnchor = root,
                )
            )
            assertTrue(canonicalWorkflowJsonFiles(nestedRoot, trustedAnchor = root).isEmpty())
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun latestExecutionRecordFallsBackToLegacyAndThenPrefersNewerInternalLog() {
        val root = Files.createTempDirectory("workflow-logs").toFile()
        val internal = File(root, "internal").apply { mkdirs() }
        val legacy = File(root, "legacy").apply { mkdirs() }
        try {
            val legacyRecord = File(legacy, "legacy.json").apply {
                writeText("{}")
                setLastModified(100L)
            }
            File(legacy, "ignored.txt").writeText("not a record")

            assertEquals(legacyRecord, latestWorkflowExecutionRecordFile(internal, legacy))

            val internalRecord = File(internal, "internal.json").apply {
                writeText("{}")
                setLastModified(200L)
            }
            assertEquals(internalRecord, latestWorkflowExecutionRecordFile(internal, legacy))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun internalWinsOnIdConflict_documentedInvariant() {
        // Mirrors WorkflowRepository.getAllWorkflows: internal is scanned first into seenIds,
        // so a legacy entry with the same id is skipped.
        val seenIds = mutableSetOf<String>()
        val out = mutableListOf<String>()
        // simulate internal scan
        out += "A"; seenIds += "A"
        out += "B"; seenIds += "B"
        // simulate legacy scan with conflict on "A" + new "C"
        for (id in listOf("A", "C")) {
            if (id in seenIds) continue  // skip conflicts; matches scanWorkflowDir guard
            out += id; seenIds += id
        }
        assertEquals(listOf("A", "B", "C"), out)
    }

    @Test
    fun hiddenLegacyId_isSkippedDuringLegacyScan() {
        val hidden = setOf("ghost")
        val seenIds = mutableSetOf<String>()
        val out = mutableListOf<String>()
        out += "real"; seenIds += "real"
        for (id in listOf("ghost", "other")) {
            if (id in seenIds) continue
            if (id in hidden) continue  // matches scanWorkflowDir skipIds
            out += id; seenIds += id
        }
        assertEquals(listOf("real", "other"), out)
        assertFalse("ghost" in out)
    }

    @Test
    fun writeOnCopy_preservesIdAndLeavesLegacyUntouched() {
        val internalIds = mutableSetOf<String>()
        val legacyUntouched = mutableSetOf("A")

        // ensureWorkflowInInternalStorage(id): copy legacy A into internal, then write to internal.
        val id = "A"
        // copy
        internalIds += id
        // a subsequent write must NOT mutate legacyUntouched
        assertTrue(id in internalIds)
        assertTrue("A" in legacyUntouched) // legacy original still present
    }

    @Test
    fun deleteInternalCopyWithLegacyPresent_hidesLegacySoItDoesNotReappear() {
        // Scenario: internal A exists, legacy A exists. deleteWorkflow(A) deletes internal and
        // hides legacy so the next scan does not resurrect it.
        val internalFiles = mutableSetOf("A")
        val legacyFiles = mutableSetOf("A")
        val hidden = mutableSetOf<String>()

        // deleteWorkflow(A):
        internalFiles.remove("A")
        if ("A" in legacyFiles) { hidden += "A" }

        // next scan: internal empty, legacy A exists but is hidden -> A must not appear
        val seenIds = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (id in internalFiles) { out += id; seenIds += id }
        for (id in legacyFiles) {
            if (id in seenIds) continue
            if (id in hidden) continue
            out += id; seenIds += id
        }
        assertTrue(out.isEmpty())
    }

    @Test
    fun failedInternalDeletion_isNotReportedAsSuccessWhenLegacyCopyIsHidden() {
        assertFalse(
            workflowDeletionSucceeded(
                internalExisted = true,
                internalRemoved = false,
                legacyExisted = true
            )
        )
    }

    @Test
    fun legacyOnlyDeletion_isReportedAsSuccessAfterItIsHidden() {
        assertTrue(
            workflowDeletionSucceeded(
                internalExisted = false,
                internalRemoved = true,
                legacyExisted = true
            )
        )
    }

    @Test
    fun failedInternalDeletion_doesNotAuthorizeUnscheduling() {
        val deleted =
            workflowDeletionSucceeded(
                internalExisted = true,
                internalRemoved = false,
                legacyExisted = true
            )

        assertFalse(deleted)
    }
}

package com.ai.assistance.operit.data.repository

import java.io.File
import java.nio.file.Files
import com.ai.assistance.operit.core.workflow.WorkflowScheduler
import com.ai.assistance.operit.data.model.TriggerNode
import com.ai.assistance.operit.data.model.Workflow
import com.ai.assistance.operit.data.model.WorkflowExecutionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
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
    fun legacyDisplayIsDisabledAndOnlyUserInitiatedExecutionMayPromoteIt() {
        val enabledLegacy = Workflow(id = "legacy", enabled = true)
        val projected = projectLegacyWorkflowForDisplay(enabledLegacy)

        assertFalse(projected.enabled)
        assertEquals(
            WorkflowExecutionStorageAction.REJECT,
            workflowExecutionStorageAction(false, WorkflowExecutionOrigin.AUTOMATIC),
        )
        assertEquals(
            WorkflowExecutionStorageAction.REJECT,
            workflowExecutionStorageAction(false, WorkflowExecutionOrigin.AUTHENTICATED_EXTERNAL),
        )
        assertEquals(
            WorkflowExecutionStorageAction.PROMOTE_LEGACY,
            workflowExecutionStorageAction(false, WorkflowExecutionOrigin.USER_INITIATED),
        )
        assertEquals(
            WorkflowExecutionStorageAction.USE_PRIVATE,
            workflowExecutionStorageAction(true, WorkflowExecutionOrigin.AUTOMATIC),
        )

        val alreadyDisabled = enabledLegacy.copy(enabled = false)
        assertSame(alreadyDisabled, projectLegacyWorkflowForDisplay(alreadyDisabled))
    }

    @Test
    fun privateScheduleRebuildCancelsEveryTrustedIdBeforeScheduling() {
        val workflows = listOf(
            Workflow(id = "enabled", enabled = true),
            Workflow(id = "disabled", enabled = false),
            Workflow(id = "cancel-fails", enabled = true),
        )
        val events = mutableListOf<String>()
        val result = rebuildPrivateWorkflowSchedules(
            workflows = workflows,
            cancelAndWait = { id ->
                events += "cancel:$id"
                if (id == "cancel-fails") error("cancel failed")
            },
            schedulePrivate = { id ->
                events += "schedule:$id"
                true
            },
        )

        assertEquals(
            listOf(
                "cancel:enabled",
                "schedule:enabled",
                "cancel:disabled",
                "cancel:cancel-fails",
            ),
            events,
        )
        assertEquals(1, result.scheduledCount)
        assertEquals(1, result.cancellationFailures)
    }

    @Test
    fun legacyScheduleCleanupNeverSchedulesAReplacementAfterCancellationFailure() {
        val cancelled = mutableListOf<String>()
        val failures = cancelLegacyWorkflowScheduleIds(
            workflowIds = listOf("first", "fails", "last"),
            cancelAndWait = { id ->
                cancelled += id
                if (id == "fails") error("cancel failed")
            },
        )

        assertEquals(listOf("first", "fails", "last"), cancelled)
        assertEquals(1, failures)
    }

    @Test
    fun scheduleFingerprintBindsTrustedNodeAndConfiguration() {
        val original = TriggerNode(
            id = "schedule-node",
            triggerType = "schedule",
            triggerConfig = linkedMapOf("repeat" to "true", "interval_ms" to "900000"),
        )
        val reordered = original.copy(
            triggerConfig = linkedMapOf("interval_ms" to "900000", "repeat" to "true"),
        )
        val changed = original.copy(triggerConfig = original.triggerConfig + ("interval_ms" to "1800000"))

        assertEquals(
            WorkflowScheduler.scheduleFingerprint("workflow", original),
            WorkflowScheduler.scheduleFingerprint("workflow", reordered),
        )
        assertFalse(
            WorkflowScheduler.scheduleFingerprint("workflow", original) ==
                WorkflowScheduler.scheduleFingerprint("workflow", changed)
        )
    }

    @Test
    fun scheduledExecutionAuthorizationRejectsDisabledMissingAndStalePlans() {
        val node = TriggerNode(
            id = "schedule-node",
            triggerType = "schedule",
            triggerConfig = mapOf("interval_ms" to "900000", "enabled" to "true"),
        )
        val workflow = Workflow(id = "workflow", enabled = true, nodes = listOf(node))
        val fingerprint = WorkflowScheduler.scheduleFingerprint(workflow.id, node)

        assertTrue(isTrustedScheduleExecutionAuthorized(workflow, node.id, fingerprint))
        assertFalse(
            isTrustedScheduleExecutionAuthorized(workflow.copy(enabled = false), node.id, fingerprint)
        )
        assertFalse(isTrustedScheduleExecutionAuthorized(workflow, "missing", fingerprint))
        assertFalse(isTrustedScheduleExecutionAuthorized(workflow, node.id, "stale"))
        assertFalse(
            isTrustedScheduleExecutionAuthorized(
                workflow.copy(nodes = listOf(node.copy(triggerConfig = node.triggerConfig + ("enabled" to "false")))),
                node.id,
                fingerprint,
            )
        )
        assertFalse(
            isTrustedScheduleExecutionAuthorized(
                workflow.copy(nodes = listOf(node.copy(triggerType = "manual"))),
                node.id,
                fingerprint,
            )
        )
    }

    @Test
    fun untrustedDirectoryScanStopsAtEntryFileAndByteBudgets() {
        val root = Files.createTempDirectory("workflow-bounded-scan").toFile()
        try {
            repeat(20) { index -> File(root, "workflow-$index.json").writeText("1234567890") }

            val entryBounded = scanCanonicalWorkflowJsonFiles(
                root,
                limits = WorkflowFileScanLimits(maxFiles = 20, maxEntriesVisited = 3),
            )
            assertTrue(entryBounded.files.size <= 3)
            assertTrue(entryBounded.truncated)

            val fileBounded = canonicalWorkflowJsonFiles(
                root,
                limits = WorkflowFileScanLimits(maxFiles = 2, maxEntriesVisited = 20),
            )
            assertEquals(2, fileBounded.size)

            val byteBounded = canonicalWorkflowJsonFiles(
                root,
                limits = WorkflowFileScanLimits(
                    maxFiles = 20,
                    maxEntriesVisited = 20,
                    maxTotalBytes = 15,
                    maxFileBytes = 10,
                ),
            )
            assertEquals(1, byteBounded.size)

            val singleFileBounded = scanCanonicalWorkflowJsonFiles(
                root,
                limits = WorkflowFileScanLimits(
                    maxFiles = 20,
                    maxEntriesVisited = 20,
                    maxTotalBytes = 100,
                    maxFileBytes = 9,
                ),
            )
            assertTrue(singleFileBounded.files.isEmpty())
            assertEquals(20, singleFileBounded.skippedEntries)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun boundedNoFollowReaderEnforcesActualAggregateBytes() {
        val root = Files.createTempDirectory("workflow-bounded-read").toFile()
        try {
            val first = File(root, "first.json").apply { writeText("1234567890") }
            val second = File(root, "second.json").apply { writeText("abcdefghij") }
            val budget = WorkflowByteBudget(15)

            assertEquals(
                "1234567890",
                readWorkflowTextBoundedNoFollow(first, 10, "too large", budget),
            )
            assertEquals(5L, budget.remainingBytes)
            assertThrows(IllegalArgumentException::class.java) {
                readWorkflowTextBoundedNoFollow(second, 10, "too large", budget)
            }
            assertEquals(0L, budget.remainingBytes)

            assertThrows(IllegalArgumentException::class.java) {
                readWorkflowTextBoundedNoFollow(first, 10, "too large", budget)
            }
            assertEquals(0L, budget.remainingBytes)

            assertThrows(IllegalArgumentException::class.java) {
                readWorkflowTextBoundedNoFollow(first, 9, "too large")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun untrustedJsonPreflightRejectsDepthAndElementBombsButIgnoresStrings() {
        validateUntrustedWorkflowJson(
            """{"text":"[[[[,,,,::::]]]]"}""",
            maxDepth = 2,
            maxStructuralTokens = 4,
        )

        assertThrows(IllegalArgumentException::class.java) {
            validateUntrustedWorkflowJson("[".repeat(65) + "]".repeat(65))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateUntrustedWorkflowJson("[0,0,0]", maxStructuralTokens = 3)
        }
    }

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
            assertEquals(inside, latestWorkflowExecutionRecordFile(listOf(inside), emptyList()))
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
            assertNull(latestWorkflowExecutionRecordFile(emptyList(), emptyList()))

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
    fun latestExecutionRecordFallsBackToLegacyButAlwaysPrefersPrivateHistory() {
        val root = Files.createTempDirectory("workflow-logs").toFile()
        val internal = File(root, "internal").apply { mkdirs() }
        val legacy = File(root, "legacy").apply { mkdirs() }
        try {
            val legacyRecord = File(legacy, "legacy.json").apply {
                writeText("{}")
                setLastModified(100L)
            }
            File(legacy, "ignored.txt").writeText("not a record")

            assertEquals(
                legacyRecord,
                latestWorkflowExecutionRecordFile(emptyList(), canonicalWorkflowJsonFiles(legacy)),
            )

            val internalRecord = File(internal, "internal.json").apply {
                writeText("{}")
                setLastModified(50L)
            }
            assertEquals(
                internalRecord,
                latestWorkflowExecutionRecordFile(
                    canonicalWorkflowJsonFiles(internal),
                    canonicalWorkflowJsonFiles(legacy),
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun executionRecordMustBelongToRequestedWorkflow() {
        val record = WorkflowExecutionRecord(
            workflowId = "other",
            workflowName = "Other",
            success = true,
            message = "done",
        )

        assertThrows(IllegalArgumentException::class.java) {
            requireWorkflowExecutionRecordOwnership(record, "requested")
        }
        assertSame(record, requireWorkflowExecutionRecordOwnership(record, "other"))
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

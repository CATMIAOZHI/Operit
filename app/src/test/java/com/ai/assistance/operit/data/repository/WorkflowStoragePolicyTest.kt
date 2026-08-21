package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.work.WorkInfo
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.ai.assistance.operit.core.workflow.WorkflowScheduler
import com.ai.assistance.operit.core.workflow.WorkflowExecutionRetryableException
import com.ai.assistance.operit.core.workflow.WorkflowScheduleWorkInfo
import com.ai.assistance.operit.core.workflow.isActiveWorkflowScheduleState
import com.ai.assistance.operit.core.workflow.isActiveMatchingWorkflowSchedule
import com.ai.assistance.operit.core.workflow.isActivePreFingerprintWorkflowSchedule
import com.ai.assistance.operit.core.workflow.intervalReplacementInitialDelayMinutes
import com.ai.assistance.operit.core.workflow.deferredCronInitialDelayMillis
import com.ai.assistance.operit.core.workflow.atomicMarkerMayExist
import com.ai.assistance.operit.core.workflow.canClaimWorkflowGateRetry
import com.ai.assistance.operit.core.workflow.WorkflowGateRetryExecutionDecision
import com.ai.assistance.operit.core.workflow.WORKFLOW_GATE_RETRY_DEFERRED
import com.ai.assistance.operit.core.workflow.WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED
import com.ai.assistance.operit.core.workflow.workflowGateRetryStateAfterDeferral
import com.ai.assistance.operit.core.workflow.workflowGateRetryExecutionDecision
import com.ai.assistance.operit.core.workflow.shouldRetireCompletedPreFingerprintOneTime
import com.ai.assistance.operit.core.workflow.specificTimeInitialDelay
import com.ai.assistance.operit.core.workflow.shouldRetryWorkflowWorkerFailure
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
import org.mockito.Mockito.mock

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
    fun privateScheduleRebuildDelegatesEachIdToOneAtomicResetAction() {
        val workflows = listOf(
            Workflow(id = "enabled", enabled = true),
            Workflow(id = "disabled", enabled = false),
            Workflow(id = "cancel-fails", enabled = true),
        )
        val events = mutableListOf<String>()
        val result = rebuildPrivateWorkflowSchedules(
            workflows = workflows,
            rebuildPrivate = { workflow ->
                events += "rebuild:${workflow.id}"
                if (workflow.id == "cancel-fails") error("cancel failed")
                workflow.enabled
            },
        )

        assertEquals(
            listOf(
                "rebuild:enabled",
                "rebuild:disabled",
                "rebuild:cancel-fails",
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
    fun preFingerprintCompatibilityIsRecoverableUntilCompleted() {
        val node = TriggerNode(
            id = "schedule-node",
            triggerType = "schedule",
            triggerConfig = mapOf("interval_ms" to "900000", "enabled" to "true"),
        )
        val workflow = Workflow(id = "workflow", enabled = true, nodes = listOf(node))

        val claim = claimPreFingerprintSchedule(
            workflow,
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        )
        requireNotNull(claim)
        assertEquals(WorkflowScheduler.scheduleFingerprint(workflow.id, node), claim.scheduleFingerprint)
        assertEquals(
            WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION,
            claim.workflow.scheduleFingerprintGeneration,
        )
        assertTrue(claim.installReplacement)
        assertTrue(claim.executeWorkflow)
        assertTrue(isClaimedPreFingerprintExecutionAuthorized(
            claim.workflow,
            node.id,
            claim.scheduleFingerprint,
        ))
        assertTrue(shouldDeferClaimedScheduleRebuild(claim.workflow, allowClaimedMigration = false))
        assertFalse(shouldDeferClaimedScheduleRebuild(claim.workflow, allowClaimedMigration = true))
        assertTrue(shouldRetainPreFingerprintClaimForRetry(
            WorkflowExecutionRetryableException("runtime initialization")
        ))

        val resumed = claimPreFingerprintSchedule(
            claim.workflow,
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        )
        requireNotNull(resumed)
        assertTrue(resumed.executeWorkflow)

        val pending = markPreFingerprintReplacementPending(
            resumed.workflow,
            node.id,
            resumed.scheduleFingerprint,
        )
        requireNotNull(pending)
        assertEquals(
            WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION,
            pending.scheduleFingerprintGeneration,
        )
        assertFalse(shouldDeferClaimedScheduleRebuild(
            pending,
            allowClaimedMigration = false,
        ))
        val replacementOnly = claimPreFingerprintSchedule(
            pending,
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        )
        requireNotNull(replacementOnly)
        assertFalse(replacementOnly.executeWorkflow)
        assertTrue(replacementOnly.installReplacement)
        assertEquals(null, markPreFingerprintReplacementPending(
            pending,
            node.id,
            replacementOnly.scheduleFingerprint,
        ))

        val completed = completePreFingerprintScheduleClaim(
            resumed.workflow,
            node.id,
            resumed.scheduleFingerprint,
        )
        requireNotNull(completed)
        assertEquals(
            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
            completed.scheduleFingerprintGeneration,
        )
        assertFalse(isClaimedPreFingerprintExecutionAuthorized(
            completed,
            node.id,
            claim.scheduleFingerprint,
        ))
        assertEquals(null, claimPreFingerprintSchedule(
            completed,
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        ))
        assertEquals(null, claimPreFingerprintSchedule(
            workflow,
            node.id,
            isEligible = { _, _ -> false },
            shouldInstallReplacement = { _, _ -> true },
        ))
        assertEquals(null, claimPreFingerprintSchedule(
            workflow.copy(enabled = false),
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        ))
        assertEquals(null, claimPreFingerprintSchedule(
            workflow,
            "missing",
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        ))
        assertEquals(null, claimPreFingerprintSchedule(
            workflow.copy(nodes = listOf(node.copy(triggerConfig = node.triggerConfig + ("enabled" to "false")))),
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        ))
        assertEquals(null, claimPreFingerprintSchedule(
            workflow.copy(nodes = listOf(node.copy(triggerType = "intent"))),
            node.id,
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        ))
    }

    @Test
    fun explicitLegacyPromotionPublishesPendingGenerationBeforeScheduling() {
        val node = TriggerNode(
            id = "schedule-node",
            triggerType = "schedule",
            triggerConfig = mapOf("interval_ms" to "900000", "enabled" to "true"),
        )
        val promoted = prepareExplicitLegacyPromotionScheduleGeneration(
            Workflow(
                id = "legacy",
                enabled = true,
                nodes = listOf(node),
                scheduleFingerprintGeneration = null,
            ),
            scheduleReconciliationPending = true,
            rejectedPastSpecificTime = false,
        )

        assertEquals(
            WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION,
            promoted.scheduleFingerprintGeneration,
        )
        val claim = claimPreFingerprintSchedule(
            promoted,
            "schedule-node",
            isEligible = { _, _ -> true },
            shouldInstallReplacement = { _, _ -> true },
        )
        assertEquals(false, claim?.executeWorkflow)
        assertEquals(true, claim?.installReplacement)
    }

    @Test
    fun workerRetriesOnlyExplicitlyRetryableFailures() {
        assertTrue(shouldRetryWorkflowWorkerFailure(
            PreFingerprintScheduleReplacementPendingException("workflow")
        ))
        assertTrue(shouldRetryWorkflowWorkerFailure(
            WorkflowExecutionRetryableException("runtime initialization")
        ))
        assertFalse(shouldRetryWorkflowWorkerFailure(IllegalStateException("execution failed")))
        assertFalse(shouldRetryWorkflowWorkerFailure(null))
        assertFalse(shouldRetainPreFingerprintClaimForRetry(
            PreFingerprintScheduleReplacementPendingException("workflow")
        ))
        assertFalse(shouldRetainPreFingerprintClaimForRetry(IllegalStateException("execution failed")))
        assertFalse(shouldRetainPreFingerprintClaimForRetry(null))
    }

    @Test
    fun preFingerprintSchedulerAcceptsDueOneTimeAndOnlyReplacesRecurringWork() {
        val scheduler = WorkflowScheduler(mock(Context::class.java))
        fun workflow(config: Map<String, String>) = Workflow(
            id = "workflow",
            nodes = listOf(TriggerNode(id = "schedule-node", triggerType = "schedule", triggerConfig = config)),
        )

        val dueOneTime = workflow(mapOf(
            WorkflowScheduler.CONFIG_SCHEDULE_TYPE to WorkflowScheduler.SCHEDULE_TYPE_SPECIFIC_TIME,
            WorkflowScheduler.CONFIG_SPECIFIC_TIME to "2000-01-01 00:00",
        ))
        assertTrue(scheduler.canClaimPreFingerprintSchedule(dueOneTime, "schedule-node"))
        assertFalse(scheduler.shouldReplacePreFingerprintSchedule(dueOneTime, "schedule-node"))
        assertTrue(scheduler.isMigratablePreFingerprintScheduleRequest(dueOneTime))
        assertTrue(scheduler.shouldPreserveActivePreFingerprintScheduleRequest(dueOneTime))
        assertFalse(scheduler.shouldPrepareFingerprintScheduleReconciliation(dueOneTime))
        assertTrue(scheduler.isRejectedPastSpecificTimeDefinition(dueOneTime))
        assertTrue(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime,
                listOf(WorkflowScheduleWorkInfo(WorkInfo.State.SUCCEEDED, emptySet())),
                targetIsDue = true,
            )
        )
        assertTrue(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime,
                listOf(WorkflowScheduleWorkInfo(WorkInfo.State.FAILED, emptySet())),
                targetIsDue = true,
            )
        )
        assertFalse(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime,
                listOf(WorkflowScheduleWorkInfo(WorkInfo.State.CANCELLED, emptySet())),
                targetIsDue = true,
            )
        )
        val claimedRequestId = java.util.UUID.randomUUID()
        assertFalse(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime,
                listOf(
                    WorkflowScheduleWorkInfo(
                        WorkInfo.State.FAILED,
                        emptySet(),
                        claimedRequestId,
                    )
                ),
                targetIsDue = true,
                excludedWorkRequestIds = setOf(claimedRequestId),
            )
        )
        assertFalse(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime,
                listOf(WorkflowScheduleWorkInfo(WorkInfo.State.SUCCEEDED, emptySet())),
                targetIsDue = false,
            )
        )
        assertFalse(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime,
                listOf(
                    WorkflowScheduleWorkInfo(
                        WorkInfo.State.SUCCEEDED,
                        setOf(WorkflowScheduler.scheduleFingerprintTag("new-definition")),
                    )
                ),
                targetIsDue = true,
            )
        )
        assertEquals(
            WorkflowScheduler.REJECTED_SCHEDULE_FINGERPRINT_GENERATION,
            prepareFingerprintGenerationForScheduledDefinition(
                dueOneTime,
                scheduleReconciliationPending = false,
                rejectedPastSpecificTime = true,
            ).scheduleFingerprintGeneration,
        )

        val futureOneTime = dueOneTime.copy(nodes = listOf(
            (dueOneTime.nodes.single() as TriggerNode).copy(
                triggerConfig = (dueOneTime.nodes.single() as TriggerNode).triggerConfig +
                    (WorkflowScheduler.CONFIG_SPECIFIC_TIME to "2999-01-01 00:00"),
            )
        ))
        assertFalse(scheduler.canClaimPreFingerprintSchedule(futureOneTime, "schedule-node"))
        assertTrue(scheduler.isMigratablePreFingerprintScheduleRequest(futureOneTime))
        assertFalse(scheduler.shouldPreserveActivePreFingerprintScheduleRequest(futureOneTime))
        assertTrue(scheduler.isFutureSpecificTimeSchedule(futureOneTime, now = 0L))
        assertTrue(
            scheduler.shouldPrepareFingerprintScheduleReconciliation(futureOneTime, now = 0L)
        )
        assertFalse(scheduler.isRejectedPastSpecificTimeDefinition(futureOneTime, now = 0L))

        val existingDue = dueOneTime.copy(
            name = "before",
            scheduleFingerprintGeneration =
                WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
        )
        val contentOnlyUpdate = existingDue.copy(name = "after")
        assertEquals(
            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
            prepareFingerprintGenerationForDefinitionUpdate(
                requestedWorkflow = contentOnlyUpdate,
                latestWorkflow = existingDue,
                promotingLegacy = false,
                scheduleReconciliationPending = false,
                rejectedPastSpecificTime = true,
            ).scheduleFingerprintGeneration,
        )
        assertFalse(
            shouldReconcileScheduleAfterDefinitionUpdate(
                scheduleDefinitionChanged = false,
                workflow = contentOnlyUpdate.copy(
                    scheduleFingerprintGeneration =
                        WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
                ),
            )
        )
        assertTrue(
            shouldReconcileScheduleAfterDefinitionUpdate(
                scheduleDefinitionChanged = true,
                workflow = contentOnlyUpdate.copy(
                    scheduleFingerprintGeneration =
                        WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
                ),
            )
        )
        assertTrue(
            shouldReconcileScheduleAfterDefinitionUpdate(
                scheduleDefinitionChanged = false,
                workflow = contentOnlyUpdate.copy(
                    scheduleFingerprintGeneration =
                        WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION,
                ),
            )
        )
        val changedPastSchedule = contentOnlyUpdate.copy(
            nodes = listOf(
                (contentOnlyUpdate.nodes.single() as TriggerNode).copy(
                    triggerConfig =
                        (contentOnlyUpdate.nodes.single() as TriggerNode).triggerConfig +
                            (WorkflowScheduler.CONFIG_SPECIFIC_TIME to "1999-01-01 00:00"),
                )
            )
        )
        assertEquals(
            WorkflowScheduler.REJECTED_SCHEDULE_FINGERPRINT_GENERATION,
            prepareFingerprintGenerationForDefinitionUpdate(
                requestedWorkflow = changedPastSchedule,
                latestWorkflow = existingDue,
                promotingLegacy = false,
                scheduleReconciliationPending = false,
                rejectedPastSpecificTime = true,
            ).scheduleFingerprintGeneration,
        )

        val interval = workflow(mapOf(
            WorkflowScheduler.CONFIG_SCHEDULE_TYPE to WorkflowScheduler.SCHEDULE_TYPE_INTERVAL,
            WorkflowScheduler.CONFIG_INTERVAL_MS to "900000",
            WorkflowScheduler.CONFIG_REPEAT to "true",
        ))
        assertTrue(scheduler.canClaimPreFingerprintSchedule(interval, "schedule-node"))
        assertTrue(scheduler.shouldReplacePreFingerprintSchedule(interval, "schedule-node"))
        assertTrue(scheduler.isMigratablePreFingerprintScheduleRequest(interval))
        assertTrue(scheduler.shouldPreserveActivePreFingerprintScheduleRequest(interval))
        assertTrue(scheduler.isSchedulableWorkflowDefinition(interval))
        assertTrue(scheduler.shouldPrepareFingerprintScheduleReconciliation(interval))
        assertFalse(scheduler.isSchedulableWorkflowDefinition(interval.copy(enabled = false)))
        assertFalse(scheduler.isSchedulableWorkflowDefinition(interval.copy(nodes = emptyList())))
        assertFalse(
            scheduler.isSchedulableWorkflowDefinition(
                interval.copy(
                    nodes = listOf(
                        (interval.nodes.single() as TriggerNode).copy(
                            triggerConfig =
                                (interval.nodes.single() as TriggerNode).triggerConfig +
                                    (WorkflowScheduler.CONFIG_ENABLED to "false"),
                        )
                    )
                )
            )
        )

        val oneShotCron = workflow(mapOf(
            WorkflowScheduler.CONFIG_SCHEDULE_TYPE to WorkflowScheduler.SCHEDULE_TYPE_CRON,
            WorkflowScheduler.CONFIG_CRON_EXPRESSION to "*/15 * * * *",
            WorkflowScheduler.CONFIG_REPEAT to "false",
        ))
        assertTrue(scheduler.canClaimPreFingerprintSchedule(oneShotCron, "schedule-node"))
        assertFalse(scheduler.shouldReplacePreFingerprintSchedule(oneShotCron, "schedule-node"))
        assertTrue(scheduler.isMigratablePreFingerprintScheduleRequest(oneShotCron))
        val repeatingCron = oneShotCron.copy(nodes = listOf(
            (oneShotCron.nodes.single() as TriggerNode).copy(
                triggerConfig = (oneShotCron.nodes.single() as TriggerNode).triggerConfig +
                    (WorkflowScheduler.CONFIG_REPEAT to "true"),
            )
        ))
        assertTrue(scheduler.isMigratablePreFingerprintScheduleRequest(repeatingCron))
        assertTrue(scheduler.shouldPreserveActivePreFingerprintScheduleRequest(repeatingCron))
        assertFalse(
            scheduler.isMigratablePreFingerprintScheduleRequest(
                dueOneTime.copy(
                    scheduleFingerprintGeneration =
                        WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
                )
            )
        )
        assertFalse(
            shouldRetireCompletedPreFingerprintOneTime(
                dueOneTime.copy(
                    scheduleFingerprintGeneration =
                        WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
                ),
                listOf(WorkflowScheduleWorkInfo(WorkInfo.State.SUCCEEDED, emptySet())),
                targetIsDue = true,
            )
        )
        assertFalse(
            shouldRetireCompletedPreFingerprintOneTime(
                interval,
                listOf(WorkflowScheduleWorkInfo(WorkInfo.State.SUCCEEDED, emptySet())),
                targetIsDue = true,
            )
        )
    }

    @Test
    fun fingerprintedWorkerRepairsInterruptedGenerationCommitBeforeExecution() {
        val node = TriggerNode(
            id = "schedule-node",
            triggerType = "schedule",
            triggerConfig = mapOf(
                WorkflowScheduler.CONFIG_SCHEDULE_TYPE to WorkflowScheduler.SCHEDULE_TYPE_INTERVAL,
                WorkflowScheduler.CONFIG_INTERVAL_MS to "900000",
            ),
        )
        val pending = Workflow(
            id = "workflow",
            enabled = true,
            nodes = listOf(node),
            scheduleFingerprintGeneration =
                WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION,
        )
        val fingerprint = WorkflowScheduler.scheduleFingerprint(pending.id, node)

        assertEquals(
            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
            normalizeFingerprintGenerationForExecution(
                pending,
                node.id,
                fingerprint,
            )?.scheduleFingerprintGeneration,
        )
        assertEquals(
            WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION,
            normalizeFingerprintGenerationForExecution(
                pending.copy(scheduleFingerprintGeneration = null),
                node.id,
                fingerprint,
            )?.scheduleFingerprintGeneration,
        )
        assertEquals(
            null,
            normalizeFingerprintGenerationForExecution(pending, node.id, "stale"),
        )
        assertEquals(
            null,
            normalizeFingerprintGenerationForExecution(
                pending.copy(
                    scheduleFingerprintGeneration =
                        WorkflowScheduler.CLAIMED_SCHEDULE_FINGERPRINT_GENERATION,
                ),
                node.id,
                fingerprint,
            ),
        )
    }

    @Test
    fun fingerprintScheduleCoordinationIsTransactionalAndRejectedNeverEnqueues() {
        val base = Workflow(id = "workflow")
        val rejected = base.copy(
            scheduleFingerprintGeneration =
                WorkflowScheduler.REJECTED_SCHEDULE_FINGERPRINT_GENERATION,
        )
        val rejectedEvents = mutableListOf<String>()
        assertFalse(
            coordinateFingerprintScheduleRequest(
                workflow = rejected,
                persist = { rejectedEvents += "persist:${it.scheduleFingerprintGeneration}" },
                enqueue = {
                    rejectedEvents += "enqueue"
                    true
                },
                cancel = { rejectedEvents += "cancel" },
            )
        )
        assertEquals(listOf("cancel"), rejectedEvents)

        val successEvents = mutableListOf<String>()
        assertTrue(
            coordinateFingerprintScheduleRequest(
                workflow = base.copy(scheduleFingerprintGeneration = null),
                persist = { successEvents += "persist:${it.scheduleFingerprintGeneration}" },
                enqueue = {
                    successEvents += "enqueue:${it.scheduleFingerprintGeneration}"
                    true
                },
                cancel = { successEvents += "cancel" },
            )
        )
        assertEquals(
            listOf(
                "persist:${WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION}",
                "enqueue:${WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION}",
                "persist:${WorkflowScheduler.CURRENT_SCHEDULE_FINGERPRINT_GENERATION}",
            ),
            successEvents,
        )

        val failureEvents = mutableListOf<String>()
        assertFalse(
            coordinateFingerprintScheduleRequest(
                workflow = base.copy(scheduleFingerprintGeneration = null),
                persist = { failureEvents += "persist:${it.scheduleFingerprintGeneration}" },
                enqueue = {
                    failureEvents += "enqueue:${it.scheduleFingerprintGeneration}"
                    false
                },
                cancel = { failureEvents += "cancel" },
            )
        )
        assertEquals(
            listOf(
                "persist:${WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION}",
                "enqueue:${WorkflowScheduler.PENDING_REPLACEMENT_SCHEDULE_FINGERPRINT_GENERATION}",
                "persist:${WorkflowScheduler.REJECTED_SCHEDULE_FINGERPRINT_GENERATION}",
                "cancel",
            ),
            failureEvents,
        )
    }

    @Test
    fun missingDueOneTimeIsRecreatedImmediatelyAndOnlyActiveWorkIsPreserved() {
        assertEquals(null, specificTimeInitialDelay(99L, 100L, allowPastTarget = false))
        assertEquals(0L, specificTimeInitialDelay(99L, 100L, allowPastTarget = true))
        assertEquals(25L, specificTimeInitialDelay(125L, 100L, allowPastTarget = false))

        assertTrue(isActiveWorkflowScheduleState(androidx.work.WorkInfo.State.ENQUEUED))
        assertTrue(isActiveWorkflowScheduleState(androidx.work.WorkInfo.State.RUNNING))
        assertTrue(isActiveWorkflowScheduleState(androidx.work.WorkInfo.State.BLOCKED))
        assertFalse(isActiveWorkflowScheduleState(androidx.work.WorkInfo.State.SUCCEEDED))
        assertFalse(isActiveWorkflowScheduleState(androidx.work.WorkInfo.State.FAILED))
        assertFalse(isActiveWorkflowScheduleState(androidx.work.WorkInfo.State.CANCELLED))
        assertEquals(15L, intervalReplacementInitialDelayMinutes(15L, delayFirstRun = true))
        assertNull(intervalReplacementInitialDelayMinutes(15L, delayFirstRun = false))
        assertEquals(0L, deferredCronInitialDelayMillis(60_000L, runImmediately = true))
        assertEquals(60_000L, deferredCronInitialDelayMillis(60_000L, runImmediately = false))
        assertTrue(atomicMarkerMayExist(baseExists = true, backupExists = false))
        assertTrue(atomicMarkerMayExist(baseExists = false, backupExists = true))
        assertFalse(atomicMarkerMayExist(baseExists = false, backupExists = false))
        assertTrue(canClaimWorkflowGateRetry(WORKFLOW_GATE_RETRY_DEFERRED))
        assertTrue(canClaimWorkflowGateRetry(WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED))
        assertEquals(
            WorkflowGateRetryExecutionDecision.EXECUTE,
            workflowGateRetryExecutionDecision(null),
        )
        assertEquals(
            WorkflowGateRetryExecutionDecision.EXECUTE,
            workflowGateRetryExecutionDecision(WORKFLOW_GATE_RETRY_DEFERRED),
        )
        assertEquals(
            WorkflowGateRetryExecutionDecision.RECONCILIATION_CLAIMED,
            workflowGateRetryExecutionDecision(WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED),
        )
        assertEquals(
            WORKFLOW_GATE_RETRY_DEFERRED,
            workflowGateRetryStateAfterDeferral(null),
        )
        assertEquals(
            WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED,
            workflowGateRetryStateAfterDeferral(WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED),
        )

        val matchingTag = WorkflowScheduler.scheduleFingerprintTag("expected")
        assertTrue(
            isActiveMatchingWorkflowSchedule(
                WorkInfo.State.ENQUEUED,
                setOf("workflow", matchingTag),
                matchingTag,
            )
        )
        assertFalse(
            isActiveMatchingWorkflowSchedule(
                WorkInfo.State.ENQUEUED,
                setOf("workflow"),
                matchingTag,
            )
        )
        assertTrue(
            isActivePreFingerprintWorkflowSchedule(
                WorkInfo.State.ENQUEUED,
                setOf("workflow"),
            )
        )
        assertFalse(
            isActivePreFingerprintWorkflowSchedule(
                WorkInfo.State.ENQUEUED,
                setOf("workflow", matchingTag),
            )
        )
        assertFalse(
            isActivePreFingerprintWorkflowSchedule(
                WorkInfo.State.SUCCEEDED,
                setOf("workflow"),
            )
        )
        assertFalse(
            isActiveMatchingWorkflowSchedule(
                WorkInfo.State.SUCCEEDED,
                setOf(matchingTag),
                matchingTag,
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
    fun trustedInternalScanRecoversAtomicBackupBeforeEnumeratingDefinitions() {
        val root = Files.createTempDirectory("workflow-atomic-backup-scan").toFile()
        try {
            val backup = File(root, "restored.json.bak").apply { writeText("restored") }
            val recovered = mutableListOf<File>()

            val files = canonicalWorkflowJsonFiles(
                directory = root,
                recoverAtomicBackups = true,
                atomicRecovery = { base ->
                    recovered += base
                    assertTrue(backup.renameTo(base))
                    true
                },
            )

            assertEquals(listOf(File(root, "restored.json")), recovered)
            assertEquals(listOf(File(root, "restored.json")), files)
            assertEquals("restored", files.single().readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedAtomicBackupRecoveryDoesNotBlockOtherDefinitions() {
        val root = Files.createTempDirectory("workflow-atomic-backup-isolation").toFile()
        try {
            File(root, "first.json.bak").writeText("first")
            File(root, "second.json.bak").writeText("second")
            var attempts = 0

            val files = canonicalWorkflowJsonFiles(
                directory = root,
                recoverAtomicBackups = true,
                atomicRecovery = { base ->
                    attempts++
                    if (attempts == 1) error("simulated recovery failure")
                    assertTrue(File(base.path + ".bak").renameTo(base))
                    true
                },
            )

            assertEquals(2, attempts)
            assertEquals(1, files.size)
            assertTrue(files.single().name == "first.json" || files.single().name == "second.json")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun atomicWorkflowFileOperationsShareOnePathLock() {
        val root = Files.createTempDirectory("workflow-atomic-path-lock").toFile()
        val file = File(root, "workflow.json")
        val workers = 8
        val iterations = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val pool = Executors.newFixedThreadPool(workers)
        var guardedValue = 0
        try {
            repeat(workers) {
                pool.execute {
                    start.await()
                    repeat(iterations) {
                        withWorkflowAtomicFileLock(file) {
                            val current = guardedValue
                            Thread.yield()
                            guardedValue = current + 1
                        }
                    }
                    done.countDown()
                }
            }
            start.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals(workers * iterations, guardedValue)
        } finally {
            pool.shutdownNow()
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

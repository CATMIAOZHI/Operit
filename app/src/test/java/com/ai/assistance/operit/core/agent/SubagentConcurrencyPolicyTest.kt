package com.ai.assistance.operit.core.agent

import com.ai.assistance.operit.core.tools.PermissionReviewSubmissionTool
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class SubagentConcurrencyPolicyTest {
    @Test
    fun localNativeProvidersAlwaysRunOneAtATime() {
        assertEquals(1, SubagentConcurrencyPolicy.modelLimit(ApiProviderType.MNN, 0))
        assertEquals(1, SubagentConcurrencyPolicy.modelLimit(ApiProviderType.LLAMA_CPP, 8))
    }

    @Test
    fun remoteProviderUsesConfiguredLimitAndZeroMeansUnlimited() {
        assertEquals(3, SubagentConcurrencyPolicy.modelLimit(ApiProviderType.OPENAI, 3))
        assertEquals(0, SubagentConcurrencyPolicy.modelLimit(ApiProviderType.OPENAI, 0))
    }

    @Test
    fun isolatedResultToolPermissionReviewerReusesItsRunningParentModelLease() {
        val parentRun =
            SubagentRunEntity(
                id = "parent-run",
                parentChatId = "root-chat",
                childChatId = "parent-child-chat",
                agentProfileId = "general",
                title = "Parent",
                status = SubagentRunStatus.RUNNING.name,
                modelConfigIdSnapshot = "local-model",
            )

        assertTrue(
            PermissionReviewerModelLeasePolicy.canReuse(
                parentRun = parentRun,
                reviewerModelConfigId = "local-model",
                reentrantParentModelConfigId = "local-model",
                activeParentLeaseModelConfigId = "local-model",
                reviewerProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                toolsEnabled = true,
                isolatedToolPrompts = PermissionReviewInternalTools.prompts,
                terminalToolNames = setOf(PermissionReviewSubmissionTool.NAME),
            )
        )
        assertFalse(
            PermissionReviewerModelLeasePolicy.canReuse(
                parentRun = parentRun.copy(status = SubagentRunStatus.COMPLETED.name),
                reviewerModelConfigId = "local-model",
                reentrantParentModelConfigId = "local-model",
                activeParentLeaseModelConfigId = "local-model",
                reviewerProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                toolsEnabled = true,
                isolatedToolPrompts = PermissionReviewInternalTools.prompts,
                terminalToolNames = setOf(PermissionReviewSubmissionTool.NAME),
            )
        )
        assertFalse(
            PermissionReviewerModelLeasePolicy.canReuse(
                parentRun = parentRun,
                reviewerModelConfigId = "different-model",
                reentrantParentModelConfigId = "local-model",
                activeParentLeaseModelConfigId = "local-model",
                reviewerProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                toolsEnabled = true,
                isolatedToolPrompts = PermissionReviewInternalTools.prompts,
                terminalToolNames = setOf(PermissionReviewSubmissionTool.NAME),
            )
        )
    }

    @Test
    fun permissionReviewerUsesActualFallbackModelLeaseInsteadOfInitialSnapshot() {
        val parentRun =
            SubagentRunEntity(
                id = "parent-run",
                parentChatId = "root-chat",
                childChatId = "parent-child-chat",
                agentProfileId = "general",
                title = "Parent",
                status = SubagentRunStatus.RUNNING.name,
                modelConfigIdSnapshot = "unavailable-fixed-model",
            )

        assertTrue(
            PermissionReviewerModelLeasePolicy.canReuse(
                parentRun = parentRun,
                reviewerModelConfigId = "actual-fallback-model",
                reentrantParentModelConfigId = "actual-fallback-model",
                activeParentLeaseModelConfigId = "actual-fallback-model",
                reviewerProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                toolsEnabled = true,
                isolatedToolPrompts = PermissionReviewInternalTools.prompts,
                terminalToolNames = setOf(PermissionReviewSubmissionTool.NAME),
            )
        )
    }

    @Test
    fun permissionReviewerCannotReuseStatusWithoutAnActiveCoordinatorLease() {
        val parentRun =
            SubagentRunEntity(
                id = "debug-slice-run",
                parentChatId = "root-chat",
                childChatId = "debug-child-chat",
                agentProfileId = "general",
                title = "Debug slice",
                status = SubagentRunStatus.RUNNING.name,
                modelConfigIdSnapshot = "local-model",
            )

        assertFalse(
            PermissionReviewerModelLeasePolicy.canReuse(
                parentRun = parentRun,
                reviewerModelConfigId = "local-model",
                reentrantParentModelConfigId = "local-model",
                activeParentLeaseModelConfigId = null,
                reviewerProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                toolsEnabled = true,
                isolatedToolPrompts = PermissionReviewInternalTools.prompts,
                terminalToolNames = setOf(PermissionReviewSubmissionTool.NAME),
            )
        )
    }

    @Test
    fun permissionReviewerCannotReuseLeaseWithAnUntrustedToolSurface() {
        val parentRun =
            SubagentRunEntity(
                id = "parent-run",
                parentChatId = "root-chat",
                childChatId = "parent-child-chat",
                agentProfileId = "general",
                title = "Parent",
                status = SubagentRunStatus.RUNNING.name,
                modelConfigIdSnapshot = "local-model",
            )

        assertFalse(
            PermissionReviewerModelLeasePolicy.canReuse(
                parentRun = parentRun,
                reviewerModelConfigId = "local-model",
                reentrantParentModelConfigId = "local-model",
                activeParentLeaseModelConfigId = "local-model",
                reviewerProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                toolsEnabled = true,
                isolatedToolPrompts = emptyList(),
                terminalToolNames = emptySet(),
            )
        )
    }

    @Test
    fun loweringLimitKeepsOneStableGateUntilOlderHoldersDrain() = runBlocking {
        val gate = AdjustableConcurrencyGate(initialLimit = 2)
        gate.acquire()
        gate.acquire()
        gate.updateLimit(1)

        val waiting = async { gate.acquire() }
        yield()
        assertFalse(waiting.isCompleted)

        gate.release()
        yield()
        assertFalse(waiting.isCompleted)

        gate.release()
        waiting.await()
        gate.release()
    }

    @Test
    fun cancelledWaiterDoesNotConsumeOrLeakAPermit() = runBlocking {
        val gate = AdjustableConcurrencyGate(initialLimit = 1)
        gate.acquire()
        val waiting =
            async(start = CoroutineStart.UNDISPATCHED) {
                gate.acquire()
            }

        waiting.cancelAndJoin()
        gate.release()

        assertTrue(gate.tryAcquire())
        gate.release()
    }

    @Test
    fun queuedWaitersArePromotedInFifoOrder() = runBlocking {
        val gate = AdjustableConcurrencyGate(initialLimit = 1)
        gate.acquire()
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { gate.acquire() }

        gate.release()
        first.await()
        assertFalse(second.isCompleted)

        gate.release()
        second.await()
        gate.release()
    }
}

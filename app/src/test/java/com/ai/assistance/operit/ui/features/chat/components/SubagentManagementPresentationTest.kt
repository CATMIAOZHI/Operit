package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.data.model.SubagentRunEntity
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.StreamLogger
import com.ai.assistance.operit.ui.permissions.PermissionReviewAction
import com.ai.assistance.operit.ui.permissions.PermissionReviewEvent
import com.ai.assistance.operit.ui.permissions.PermissionReviewFailureKind
import com.ai.assistance.operit.ui.permissions.PermissionReviewStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import kotlinx.coroutines.runBlocking

class SubagentManagementPresentationTest {
    @Test
    fun allFilter_hidesArchivedAndPrioritizesActiveRuns() {
        val completed = run(id = "completed", status = SubagentRunStatus.COMPLETED, createdAt = 40)
        val running = run(id = "running", status = SubagentRunStatus.RUNNING, createdAt = 10)
        val queued = run(id = "queued", status = SubagentRunStatus.QUEUED, createdAt = 30)
        val archived =
            run(
                id = "archived",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 50,
                archivedAt = 60,
            )

        val visible =
            filterAndSortSubagentRuns(
                listOf(completed, running, queued, archived),
                SubagentListFilter.ALL,
            )

        assertEquals(listOf("running", "queued", "completed"), visible.map { it.id })
    }

    @Test
    fun archivedFilter_ordersByMostRecentArchiveTime() {
        val older =
            run(
                id = "older",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 100,
                archivedAt = 200,
            )
        val newer =
            run(
                id = "newer",
                status = SubagentRunStatus.FAILED,
                createdAt = 50,
                archivedAt = 300,
            )

        val visible =
            filterAndSortSubagentRuns(
                listOf(older, newer),
                SubagentListFilter.ARCHIVED,
            )

        assertEquals(listOf("newer", "older"), visible.map { it.id })
        assertTrue(visible.all { it.archivedAt != null })
    }

    @Test
    fun autoReviewFilter_isSeparateFromOrdinarySubagentFilters() {
        val ordinary = run(id = "ordinary", status = SubagentRunStatus.COMPLETED, createdAt = 20)
        val review =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )

        assertEquals(
            listOf("ordinary"),
            filterAndSortSubagentRuns(listOf(ordinary, review), SubagentListFilter.ALL).map { it.id },
        )
        assertEquals(
            listOf("ordinary"),
            filterAndSortSubagentRuns(listOf(ordinary, review), SubagentListFilter.COMPLETED)
                .map { it.id },
        )
        assertEquals(
            listOf("review"),
            filterAndSortSubagentRuns(listOf(ordinary, review), SubagentListFilter.AUTO_REVIEW)
                .map { it.id },
        )
    }

    @Test
    fun managerOpensOnAutoReviewWhenNoOrdinaryRunsExist() {
        val review =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )

        assertEquals(
            SubagentListFilter.AUTO_REVIEW,
            initialSubagentListFilter(listOf(review), hasPermissionReviewEvents = true),
        )
        assertEquals(
            SubagentListFilter.AUTO_REVIEW,
            initialSubagentListFilter(emptyList(), hasPermissionReviewEvents = true),
        )
        assertEquals(
            SubagentListFilter.ALL,
            initialSubagentListFilter(
                listOf(run("ordinary", SubagentRunStatus.COMPLETED, 20)),
                hasPermissionReviewEvents = true,
            ),
        )
    }

    @Test
    fun managerOpensArchivedWhenOnlyArchivedRunsExist() {
        val archived =
            run(
                id = "archived",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 20,
                archivedAt = 30,
            )

        assertEquals(
            SubagentListFilter.ARCHIVED,
            initialSubagentListFilter(listOf(archived), hasPermissionReviewEvents = false),
        )
    }

    @Test
    fun activeReviewEventsKeepOrphansButExcludeArchivedRuns() {
        val archivedReview =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 20,
                archivedAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )
        val archivedEvent = reviewEvent(PermissionReviewStatus.DENIED)
        val orphanEvent =
            archivedEvent.copy(
                id = "orphan-event",
                reviewerTaskId = "missing-review",
            )

        assertEquals(
            listOf("orphan-event"),
            visiblePermissionReviewEvents(
                    runs = listOf(archivedReview),
                    events = listOf(archivedEvent, orphanEvent),
                )
                .map { it.id },
        )
    }

    @Test
    fun errorFilterShowsOnlyFailedOrInterruptedOrdinaryRuns() {
        val failed = run(id = "failed", status = SubagentRunStatus.FAILED, createdAt = 30)
        val interrupted =
            run(id = "interrupted", status = SubagentRunStatus.INTERRUPTED, createdAt = 20)
        val cancelled = run(id = "cancelled", status = SubagentRunStatus.CANCELLED, createdAt = 10)
        val failedReview =
            run(
                id = "failed-review",
                status = SubagentRunStatus.FAILED,
                createdAt = 40,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )

        assertEquals(
            listOf("failed", "interrupted"),
            filterAndSortSubagentRuns(
                    listOf(failed, interrupted, cancelled, failedReview),
                    SubagentListFilter.ERROR,
                )
                .map { it.id },
        )
    }

    @Test
    fun search_matchesAgentNameOrTaskTitleWithinSelectedFilter() {
        val byAgent =
            run(
                id = "agent-match",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = "Explore",
                title = "认证调用链",
            )
        val byTitle =
            run(
                id = "title-match",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 20,
                agentProfileId = "general",
                title = "Explore login flow",
            )
        val running =
            run(
                id = "wrong-filter",
                status = SubagentRunStatus.RUNNING,
                createdAt = 40,
                agentProfileId = "explore",
                title = "running",
            )

        val visible =
            filterAndSortSubagentRuns(
                listOf(byAgent, byTitle, running),
                SubagentListFilter.COMPLETED,
                query = "EXPLORE",
            )

        assertEquals(listOf("agent-match", "title-match"), visible.map { it.id })
    }

    @Test
    fun search_matchesLocalizedAutoReviewDisplayName() {
        val review =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                title = "shell_exec",
            )

        val visible =
            filterAndSortSubagentRuns(
                listOf(review),
                SubagentListFilter.AUTO_REVIEW,
                query = "审批员",
                autoReviewDisplayName = "权限审批员",
            )

        assertEquals(listOf("review"), visible.map { it.id })
    }

    @Test
    fun autoReviewBusinessStatusDistinguishesAllowInvalidAndTimeout() = runBlocking {
        val allowed =
            """
            <tool name="submit_permission_review">
              <param name="outcome">allow</param>
              <param name="risk_level">low</param>
              <param name="user_authorization">low</param>
              <param name="rationale">Routine operation</param>
            </tool>
            """.trimIndent()

        withoutAndroidLogging {
            assertEquals(
                PermissionReviewRunDisplayState.ALLOWED,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.COMPLETED, allowed),
            )
            assertEquals(
                PermissionReviewRunDisplayState.INVALID_OUTPUT,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.COMPLETED, "not structured"),
            )
            assertEquals(
                PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.CANCELLED, null),
            )
        }
    }

    @Test
    fun autoReviewBusinessStatusUsesAuthoritativeLifecycleEvent() = runBlocking {
        val base = reviewEvent(PermissionReviewStatus.APPROVED)

        assertEquals(
            PermissionReviewRunDisplayState.ALLOWED,
            resolvePermissionReviewRunDisplayState(
                SubagentRunStatus.COMPLETED,
                finalAssistantText = "aggregate transcript is not reparsed",
                reviewEvent = base,
            ),
        )
        assertEquals(
            PermissionReviewRunDisplayState.INVALID_OUTPUT,
            resolvePermissionReviewRunDisplayState(
                SubagentRunStatus.COMPLETED,
                finalAssistantText = null,
                reviewEvent =
                    base.copy(
                        status = PermissionReviewStatus.FAILED,
                        failureKind = PermissionReviewFailureKind.INVALID_OUTPUT,
                    ),
            ),
        )
    }

    @Test
    fun deniedReviewOpensItsReviewerRunByTaskId() {
        val reviewRun =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )
        val unrelated = run(id = "ordinary", status = SubagentRunStatus.COMPLETED, createdAt = 20)

        assertEquals(
            reviewRun,
            findSubagentRunForPermissionReviewEvent(
                listOf(unrelated, reviewRun),
                reviewEvent(PermissionReviewStatus.DENIED),
            ),
        )
    }

    private fun run(
        id: String,
        status: SubagentRunStatus,
        createdAt: Long,
        archivedAt: Long? = null,
        agentProfileId: String = "explore",
        title: String = id,
    ): SubagentRunEntity =
        SubagentRunEntity(
            id = id,
            parentChatId = "parent",
            childChatId = "child-$id",
            agentProfileId = agentProfileId,
            title = title,
            status = status.name,
            createdAt = createdAt,
            archivedAt = archivedAt,
        )

    private fun reviewEvent(status: PermissionReviewStatus): PermissionReviewEvent =
        PermissionReviewEvent(
            id = "review-event",
            parentChatId = "parent",
            timingScopeId = "turn",
            invocationIndex = 0,
            batchPosition = 1,
            batchSize = 1,
            action =
                PermissionReviewAction(
                    targetId = "call",
                    kind = "command",
                    toolName = "execute_shell",
                    summary = "echo test",
                ),
            actionFingerprint = "fingerprint",
            status = status,
            startedAt = 1L,
            reviewerTaskId = "review",
        )

    private suspend fun <T> withoutAndroidLogging(block: suspend () -> T): T =
        Mockito.mockStatic(AppLogger::class.java).use {
            try {
                StreamLogger.setEnabled(false)
                block()
            } finally {
                StreamLogger.setEnabled(true)
            }
        }
}

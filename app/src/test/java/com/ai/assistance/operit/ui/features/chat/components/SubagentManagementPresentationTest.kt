package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.core.agent.AgentProfileRepository
import com.ai.assistance.operit.api.chat.library.MemoryLearningCoordinator
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
    fun foregroundKind_hidesArchivedAndPrioritizesActiveRuns() {
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
                SubagentTaskKind.FOREGROUND,
                SubagentRunStatusFilter.ALL,
            )

        assertEquals(listOf("running", "queued", "completed"), visible.map { it.id })
    }

    @Test
    fun archivedStatus_belongsToTheCategoryItWasArchivedIn() {
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
        val archivedReview =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 20,
                archivedAt = 400,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )

        val visible =
            filterAndSortSubagentRuns(
                listOf(older, newer, archivedReview),
                SubagentTaskKind.FOREGROUND,
                SubagentRunStatusFilter.ARCHIVED,
            )

        assertEquals(listOf("newer", "older"), visible.map { it.id })
        assertTrue(visible.all { it.archivedAt != null })
        assertEquals(
            listOf("review"),
            filterAndSortSubagentRuns(
                    listOf(older, newer, archivedReview),
                    SubagentTaskKind.BACKGROUND,
                    SubagentRunStatusFilter.ARCHIVED,
                )
                .map { it.id },
        )
    }

    @Test
    fun backgroundKindSeparatesTheRunsTheAppStartsOnItsOwn() {
        val ordinary = run(id = "ordinary", status = SubagentRunStatus.COMPLETED, createdAt = 20)
        val review =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )
        val extraction =
            run(
                id = "extraction",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 40,
                agentProfileId = "memory-learning",
                externalOwnerType = MemoryLearningCoordinator.OWNER_TYPE,
            )

        val allRuns = listOf(ordinary, review, extraction)
        assertEquals(
            listOf("ordinary"),
            filterAndSortSubagentRuns(
                    allRuns,
                    SubagentTaskKind.FOREGROUND,
                    SubagentRunStatusFilter.ALL,
                )
                .map { it.id },
        )
        assertEquals(
            listOf("ordinary"),
            filterAndSortSubagentRuns(
                    allRuns,
                    SubagentTaskKind.FOREGROUND,
                    SubagentRunStatusFilter.COMPLETED,
                )
                .map { it.id },
        )
        assertEquals(
            listOf("extraction", "review"),
            filterAndSortSubagentRuns(
                    allRuns,
                    SubagentTaskKind.BACKGROUND,
                    SubagentRunStatusFilter.ALL,
                )
                .map { it.id },
        )
        // A v2 agent carries an owner type too, so only the extraction's own owner type separates them.
        val agent =
            run(
                id = "agent",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 50,
                externalOwnerType = "subagent_v2",
            )
        assertEquals(
            listOf("agent"),
            filterAndSortSubagentRuns(
                    listOf(agent),
                    SubagentTaskKind.FOREGROUND,
                    SubagentRunStatusFilter.ALL,
                )
                .map { it.id },
        )
    }

    @Test
    fun managerOpensOnBackgroundWhenTheChatHasNoForegroundRuns() {
        val review =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
            )
        val extraction =
            run(
                id = "extraction",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 40,
                agentProfileId = "memory-learning",
                externalOwnerType = MemoryLearningCoordinator.OWNER_TYPE,
            )

        assertEquals(
            SubagentTaskKind.BACKGROUND,
            initialSubagentListSelection(listOf(review), hasPermissionReviewEvents = true).kind,
        )
        assertEquals(
            SubagentTaskKind.BACKGROUND,
            initialSubagentListSelection(emptyList(), hasPermissionReviewEvents = true).kind,
        )
        assertEquals(
            SubagentTaskKind.BACKGROUND,
            initialSubagentListSelection(listOf(extraction), hasPermissionReviewEvents = false).kind,
        )
        assertEquals(
            SubagentTaskKind.FOREGROUND,
            initialSubagentListSelection(
                    listOf(run("ordinary", SubagentRunStatus.COMPLETED, 20), review),
                    hasPermissionReviewEvents = true,
                )
                .kind,
        )
        assertEquals(
            SubagentRunStatusFilter.ALL,
            initialSubagentListSelection(listOf(review), hasPermissionReviewEvents = true).status,
        )
    }

    @Test
    fun search_matchesLocalizedBackgroundDisplayNames() {
        val review =
            run(
                id = "review",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 30,
                agentProfileId = AgentProfileRepository.PERMISSION_REVIEWER_ID,
                title = "shell_exec",
            )
        val extraction =
            run(
                id = "extraction",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 40,
                agentProfileId = "memory-learning",
                title = "background learning",
                externalOwnerType = MemoryLearningCoordinator.OWNER_TYPE,
            )

        assertEquals(
            listOf("review"),
            filterAndSortSubagentRuns(
                    listOf(review, extraction),
                    SubagentTaskKind.BACKGROUND,
                    SubagentRunStatusFilter.ALL,
                    query = "审批员",
                    autoReviewDisplayName = "权限审批员",
                )
                .map { it.id },
        )
        assertEquals(
            listOf("extraction"),
            filterAndSortSubagentRuns(
                    listOf(review, extraction),
                    SubagentTaskKind.BACKGROUND,
                    SubagentRunStatusFilter.ALL,
                    query = "记忆提取",
                    memoryExtractionDisplayName = "记忆提取",
                )
                .map { it.id },
        )
    }

    @Test
    fun managerOpensOnTheArchiveWhenTheCategoryOnlyHoldsArchivedRuns() {
        val archived =
            run(
                id = "archived",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 20,
                archivedAt = 30,
            )
        val archivedExtraction =
            run(
                id = "archived-extraction",
                status = SubagentRunStatus.COMPLETED,
                createdAt = 25,
                archivedAt = 35,
                agentProfileId = "memory-learning",
                externalOwnerType = MemoryLearningCoordinator.OWNER_TYPE,
            )

        assertEquals(
            SubagentListSelection(
                kind = SubagentTaskKind.FOREGROUND,
                status = SubagentRunStatusFilter.ARCHIVED,
            ),
            initialSubagentListSelection(listOf(archived), hasPermissionReviewEvents = false),
        )
        // The archive of the category that actually has history is the one worth opening.
        assertEquals(
            SubagentListSelection(
                kind = SubagentTaskKind.BACKGROUND,
                status = SubagentRunStatusFilter.ARCHIVED,
            ),
            initialSubagentListSelection(
                listOf(archivedExtraction),
                hasPermissionReviewEvents = false,
            ),
        )
    }

    @Test
    fun orphanReviewEventsOnlyShowInTheFullBackgroundList() {
        assertTrue(
            showsOrphanPermissionReviewEvents(
                SubagentTaskKind.BACKGROUND,
                SubagentRunStatusFilter.ALL,
            )
        )
        // A review event without a run row has no status of its own, so a status filter hides it.
        assertEquals(
            false,
            showsOrphanPermissionReviewEvents(
                SubagentTaskKind.BACKGROUND,
                SubagentRunStatusFilter.ARCHIVED,
            ),
        )
        assertEquals(
            false,
            showsOrphanPermissionReviewEvents(
                SubagentTaskKind.FOREGROUND,
                SubagentRunStatusFilter.ALL,
            ),
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
    fun errorAndInterruptedFiltersStaySeparate() {
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
            listOf("failed"),
            filterAndSortSubagentRuns(
                    listOf(failed, interrupted, cancelled, failedReview),
                    SubagentTaskKind.FOREGROUND,
                    SubagentRunStatusFilter.ERROR,
                )
                .map { it.id },
        )
        // A run the app stopped is not an error, so it is looked up where it is named.
        assertEquals(
            listOf("interrupted"),
            filterAndSortSubagentRuns(
                    listOf(failed, interrupted, cancelled, failedReview),
                    SubagentTaskKind.FOREGROUND,
                    SubagentRunStatusFilter.INTERRUPTED,
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
                SubagentTaskKind.FOREGROUND,
                SubagentRunStatusFilter.COMPLETED,
                query = "EXPLORE",
            )

        assertEquals(listOf("agent-match", "title-match"), visible.map { it.id })
    }

    @Test
    fun autoReviewBusinessStatusDistinguishesAllowInvalidAndTimeout() = runBlocking {
        val allowed =
            """
            <tool name="submit_permission_review">
              <param name="review_id">review-1</param>
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
                PermissionReviewRunDisplayState.INVALID_OUTPUT,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.COMPLETED, allowed.replace("review-1", "")),
            )
            assertEquals(
                PermissionReviewRunDisplayState.CANCELLED_OR_TIMED_OUT,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.CANCELLED, null),
            )
            // A review the app stopped is not a review that failed.
            assertEquals(
                PermissionReviewRunDisplayState.INTERRUPTED,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.INTERRUPTED, null),
            )
            assertEquals(
                PermissionReviewRunDisplayState.ERROR,
                resolvePermissionReviewRunDisplayState(SubagentRunStatus.FAILED, null),
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
        externalOwnerType: String? = null,
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
            externalOwnerType = externalOwnerType,
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

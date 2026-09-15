package com.ai.assistance.operit.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The page that shows the pre-classification results reads each record through [display] and adds
 * them up through [permissionRiskScoreSummary]. Both decide what the user is told the fast level did,
 * so a record that never ran must not be counted as a verdict, and vice versa.
 */
class PermissionRiskScoreRecordsTest {
    private fun action(toolName: String) =
        PermissionRiskAction(
            canonical =
                PermissionReviewAction(
                    targetId = "target",
                    kind = "shell",
                    toolName = toolName,
                    summary = "",
                ),
            scorable = true,
        )

    private fun record(
        id: String = "chat#1",
        step: Int = 1,
        verdict: PermissionRiskVerdict? = null,
        skip: PermissionRiskScoringSkip? = null,
        failedClosed: Boolean = false,
        answeredCalls: Int = 0,
    ) = PermissionRiskScoreRecord(
        id = id,
        parentChatId = "chat",
        step = step,
        startedAt = 0L,
        completedAt = null,
        verdict = verdict,
        skip = skip,
        failedClosed = failedClosed,
        answeredCalls = answeredCalls,
    )

    @Test
    fun aRecordReadsAsTheOneThingThatHappenedToIt() {
        assertEquals(PermissionRiskScoreDisplay.RUNNING, record().display())
        assertEquals(
            PermissionRiskScoreDisplay.LOW,
            record(verdict = PermissionRiskVerdict.LOW).display(),
        )
        assertEquals(
            PermissionRiskScoreDisplay.HIGH,
            record(verdict = PermissionRiskVerdict.HIGH).display(),
        )
        assertEquals(
            PermissionRiskScoreDisplay.FAILED,
            record(failedClosed = true).display(),
        )
        assertEquals(
            PermissionRiskScoreDisplay.SKIPPED,
            record(skip = PermissionRiskScoringSkip.STRICT_MODE).display(),
        )
        // A batch that was never scored cannot also count as a verdict, even if both fields were
        // somehow set: the skip is what happened.
        assertEquals(
            PermissionRiskScoreDisplay.SKIPPED,
            record(
                    skip = PermissionRiskScoringSkip.NO_MODEL,
                    verdict = PermissionRiskVerdict.LOW,
                    failedClosed = true,
                )
                .display(),
        )
        // A failure outranks a stored verdict for the same reason.
        assertEquals(
            PermissionRiskScoreDisplay.FAILED,
            record(verdict = PermissionRiskVerdict.HIGH, failedClosed = true).display(),
        )
    }

    @Test
    fun theSummaryCountsEachOutcomeOnceAndAddsUpTheSavedReviews() {
        val records =
            listOf(
                record(id = "a", verdict = PermissionRiskVerdict.LOW, answeredCalls = 2),
                record(id = "b", verdict = PermissionRiskVerdict.LOW, answeredCalls = 1),
                record(id = "c", verdict = PermissionRiskVerdict.HIGH),
                record(id = "d", failedClosed = true),
                record(id = "e", skip = PermissionRiskScoringSkip.NO_SCORABLE_ACTION),
                record(id = "f", skip = PermissionRiskScoringSkip.OFFLINE),
                record(id = "g"),
            )
        val summary = permissionRiskScoreSummary(records)
        assertEquals(7, summary.total)
        assertEquals(5, summary.scored)
        assertEquals(2, summary.low)
        assertEquals(1, summary.high)
        assertEquals(1, summary.failed)
        assertEquals(2, summary.skipped)
        assertEquals(1, summary.running)
        assertEquals(3, summary.answeredCalls)
        // Every record is counted exactly once across the outcomes.
        assertEquals(
            summary.total,
            summary.low + summary.high + summary.failed + summary.skipped + summary.running,
        )
        // Only the batches the classifier was asked about may read as scored: a batch that was never
        // scored must not be told to the user as a pre-classification.
        assertEquals(summary.total, summary.scored + summary.skipped)
        assertEquals(summary.scored, summary.low + summary.high + summary.failed + summary.running)
    }

    @Test
    fun anEmptyHistoryAddsUpToNothing() {
        val summary = permissionRiskScoreSummary(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0, summary.scored)
        assertEquals(0, summary.low)
        assertEquals(0, summary.high)
        assertEquals(0, summary.failed)
        assertEquals(0, summary.skipped)
        assertEquals(0, summary.running)
        assertEquals(0, summary.answeredCalls)
    }

    @Test
    fun onlyTheReasonsTheUserCanActOnAreWorthARow() {
        // A batch that was never going to be scored is the designed outcome, not an event.
        assertEquals(false, permissionRiskSkipIsReported(PermissionRiskScoringSkip.STRICT_MODE))
        assertEquals(
            false,
            permissionRiskSkipIsReported(PermissionRiskScoringSkip.NO_SCORABLE_ACTION),
        )
        // These explain calls that fell back to the reviewer, so the page keeps them.
        assertEquals(true, permissionRiskSkipIsReported(PermissionRiskScoringSkip.NO_MODEL))
        assertEquals(true, permissionRiskSkipIsReported(PermissionRiskScoringSkip.OFFLINE))
        assertEquals(true, permissionRiskSkipIsReported(PermissionRiskScoringSkip.COOLDOWN))
        assertEquals(
            true,
            permissionRiskSkipIsReported(
                PermissionRiskScoringSkip.RETAINED_INSTRUCTIONS_UNAVAILABLE
            ),
        )
        // Every reason is decided: a new one must be classified on purpose rather than default to a
        // row that fills the bounded history.
        PermissionRiskScoringSkip.entries.forEach { reason ->
            permissionRiskSkipIsReported(reason)
        }
    }

    @Test
    fun theRowNamesTheToolsTheBatchDispatched() {
        // The same tool twice is one name, in the order the batch dispatched them.
        assertEquals(
            listOf("shell", "read_file"),
            permissionRiskBatchToolNames(
                listOf(action("shell"), action("read_file"), action("shell"))
            ),
        )
        // A name comes from whichever package registered the tool, so the record bounds both what a
        // name may be and how many of them it keeps.
        assertEquals(emptyList<String>(), permissionRiskBatchToolNames(listOf(action("   "))))
        assertEquals(
            MAX_RECORDED_TOOL_NAME_CHARS,
            permissionRiskBatchToolNames(listOf(action("t".repeat(200)))).single().length,
        )
        val many = (1..MAX_RECORDED_BATCH_TOOLS + 3).map { index -> action("tool_$index") }
        val recorded = permissionRiskBatchToolNames(many)
        assertEquals(MAX_RECORDED_BATCH_TOOLS, recorded.size)
        assertEquals("tool_1", recorded.first())
        assertEquals("tool_$MAX_RECORDED_BATCH_TOOLS", recorded.last())
    }
}

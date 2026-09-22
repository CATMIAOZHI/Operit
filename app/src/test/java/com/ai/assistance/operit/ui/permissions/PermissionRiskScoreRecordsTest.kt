package com.ai.assistance.operit.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        usage: PermissionRiskScoreUsage? = null,
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
        usage = usage,
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

    @Test
    fun theRowSaysWhatTheCallSpentAndHowMuchOfItWasCached() {
        val usage =
            PermissionRiskScoreUsage(
                uncachedInputTokens = 1_000L,
                cachedInputTokens = 3_000L,
                outputTokens = 210L,
            )

        // The two parts the provider split the input into are the input when it stated no total.
        assertEquals(4_000L, usage.inputTokens())
        assertEquals(75, usage.cacheReadPercent())
        assertEquals(210L, usage.outputTokens)
        // A provider that stated a total is believed over the parts.
        assertEquals(
            4_100L,
            usage.copy(totalInputTokens = 4_100L).inputTokens(),
        )
        // A call the provider served from no cache reads as no cache hit, not as unknown.
        assertEquals(0, PermissionRiskScoreUsage(uncachedInputTokens = 500L, cachedInputTokens = 0L).cacheReadPercent())
    }

    @Test
    fun aComponentTheProviderDidNotReportIsUnknownRatherThanZero() {
        val partial = PermissionRiskScoreUsage(cachedInputTokens = 20L)

        // A cache read is a share of an input, so on its own it is not the input and there is no
        // share to compute from.
        assertNull(partial.inputTokens())
        assertNull(partial.cacheReadPercent())
        assertNull(PermissionRiskScoreUsage(totalInputTokens = 0L, outputTokens = 0L).cacheReadPercent())
        // And the log line says so instead of writing a zero a reader would trust.
        assertEquals("unreported", (null as PermissionRiskScoreUsage?).summaryForLog())
        assertEquals("in=-,cached=20,out=-", partial.summaryForLog())
        assertEquals(
            "in=4000,cached=3000,out=210",
            PermissionRiskScoreUsage(
                    uncachedInputTokens = 1_000L,
                    cachedInputTokens = 3_000L,
                    outputTokens = 210L,
                )
                .summaryForLog(),
        )
    }

    @Test
    fun aStoredRowFromBeforeTheUsageWasKeptStillDecodes() {
        // Rows an earlier version wrote have none of the fields added since, and every one of them has
        // a default, so an old row reads as a batch that reported no usage rather than being lost.
        val stored = "[{\"id\":\"chat#1\",\"parentChatId\":\"chat\",\"step\":1,\"startedAt\":0}]"

        val decoded =
            permissionRiskScoreJson.decodeFromString<List<PermissionRiskScoreRecord>>(stored)

        assertEquals(1, decoded.size)
        assertNull(decoded.single().usage)
        assertEquals(0, decoded.single().priorReviewCount)
        assertEquals("", decoded.single().priorReviewHash)
    }
}

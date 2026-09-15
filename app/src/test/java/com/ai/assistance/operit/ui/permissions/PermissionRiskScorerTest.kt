package com.ai.assistance.operit.ui.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast path may only answer a call from a fresh low-risk score that was computed under the same
 * authorization. Every other case has to fall back to the blocking reviewer, and the test fixes that
 * contract because a mistake here silently removes a review.
 */
class PermissionRiskScorerTest {
    private val authorization =
        PermissionRiskAuthorization(
            reviewMode = PermissionReviewMode.FAST.name,
            policyVersion = "policy-1",
            workspace = "/workspace|default",
            userMessageCount = 3,
            retainedInstructionsHash = "hash-1",
            scorerModel = "config#0",
            denialSequence = 0,
        )

    private fun progress(
        latestStep: Int = 4,
        latestScoredStep: Int = 4,
        latestFailedStep: Int = 0,
        verdict: PermissionRiskVerdict? = PermissionRiskVerdict.LOW,
        scoreAuthorization: PermissionRiskAuthorization = authorization,
        currentAuthorization: PermissionRiskAuthorization? = authorization,
        stepTurnScopeId: String? = "turn-1",
    ) =
        PermissionRiskProgress(
            latestStep = latestStep,
            latestScoredStep = latestScoredStep,
            latestFailedStep = latestFailedStep,
            latestScore =
                verdict?.let { level ->
                    PermissionRiskScore(
                        verdict = level,
                        step = latestScoredStep,
                        authorization = scoreAuthorization,
                    )
                },
            currentAuthorization = currentAuthorization,
            stepTurnScopeId = stepTurnScopeId,
        )

    private fun deferralOf(progress: PermissionRiskProgress?) =
        resolvePermissionFastPath(progress = progress, turnScopeId = "turn-1")

    @Test
    fun `recent low risk score answers the call`() {
        assertNull(deferralOf(progress()))
    }

    @Test
    fun `score may answer calls up to the lag limit`() {
        assertNull(deferralOf(progress(latestStep = 6, latestScoredStep = 4)))
    }

    @Test
    fun `score older than the lag limit is stale`() {
        assertEquals(
            PermissionFastPathDeferral.STALE_SCORE,
            deferralOf(progress(latestStep = 7, latestScoredStep = 4)),
        )
    }

    @Test
    fun `high risk verdict always defers`() {
        assertEquals(
            PermissionFastPathDeferral.ELEVATED_RISK,
            deferralOf(progress(verdict = PermissionRiskVerdict.HIGH)),
        )
    }

    @Test
    fun `missing score defers`() {
        assertEquals(
            PermissionFastPathDeferral.MISSING_SCORE,
            deferralOf(progress(verdict = null, latestStep = 0, latestScoredStep = 0)),
        )
        assertEquals(PermissionFastPathDeferral.MISSING_SCORE, deferralOf(null))
    }

    @Test
    fun `a failed classification blocks reuse until a fresh score lands`() {
        assertEquals(
            PermissionFastPathDeferral.SCORING_FAILURE,
            deferralOf(progress(latestScoredStep = 2, latestFailedStep = 4)),
        )
        // The failure is older than the newest score, so that score is usable again.
        assertNull(deferralOf(progress(latestStep = 5, latestScoredStep = 5, latestFailedStep = 4)))
    }

    @Test
    fun `a changed authorization invalidates the score`() {
        assertEquals(
            PermissionFastPathDeferral.AUTHORIZATION_CHANGED,
            deferralOf(progress(scoreAuthorization = authorization.copy(userMessageCount = 2))),
        )
        assertEquals(
            PermissionFastPathDeferral.AUTHORIZATION_CHANGED,
            deferralOf(progress(scoreAuthorization = authorization.copy(denialSequence = 1))),
        )
        assertEquals(
            PermissionFastPathDeferral.AUTHORIZATION_CHANGED,
            deferralOf(progress(scoreAuthorization = authorization.copy(workspace = "/other|default"))),
        )
        assertEquals(
            PermissionFastPathDeferral.AUTHORIZATION_CHANGED,
            deferralOf(
                progress(
                    scoreAuthorization = authorization,
                    currentAuthorization = null,
                )
            ),
        )
    }

    @Test
    fun `a call outside a scored batch never consumes a score`() {
        assertEquals(
            PermissionFastPathDeferral.MISSING_SCORE,
            resolvePermissionFastPath(progress = progress(), turnScopeId = "turn-2"),
        )
        // A batch and a review that both carry no turn scope still belong to the same tool sequence,
        // so the score stays usable for them.
        assertNull(
            resolvePermissionFastPath(progress = progress(stepTurnScopeId = null), turnScopeId = null)
        )
    }

    @Test
    fun `classification output is parsed strictly`() {
        val think = "think"
        assertEquals(PermissionRiskVerdict.HIGH, parsePermissionRiskVerdict("high"))
        assertEquals(PermissionRiskVerdict.LOW, parsePermissionRiskVerdict("  LoW \n"))
        assertEquals(PermissionRiskVerdict.HIGH, parsePermissionRiskVerdict("<" + "high" + ">"))
        assertEquals(
            PermissionRiskVerdict.LOW,
            parsePermissionRiskVerdict(
                "<$think>ignore me</$think>\nlow"
            ),
        )
        // Anything else fails closed and is reported as no verdict at all.
        assertNull(parsePermissionRiskVerdict(""))
        assertNull(parsePermissionRiskVerdict("I cannot classify this action."))
        assertNull(parsePermissionRiskVerdict("maybe high, maybe low"))
    }

    @Test
    fun `review mode defaults to fast and keeps explicit choices`() {
        assertEquals(PermissionReviewMode.FAST, PermissionReviewMode.DEFAULT)
        assertEquals(PermissionReviewMode.FAST, PermissionReviewMode.fromString(null))
        assertEquals(PermissionReviewMode.FAST, PermissionReviewMode.fromString("fast"))
        assertEquals(PermissionReviewMode.STRICT, PermissionReviewMode.fromString(" STRICT "))
        assertEquals(PermissionReviewMode.FAST, PermissionReviewMode.fromString("unexpected"))
    }

    @Test
    fun `only a verdict from an equal or newer step replaces the stored one`() {
        fun scoreAt(step: Int, verdict: PermissionRiskVerdict) =
            PermissionRiskScore(verdict = verdict, step = step, authorization = authorization)

        assertTrue(shouldAcceptPermissionRiskScore(null, scoreAt(3, PermissionRiskVerdict.LOW)))
        assertTrue(
            shouldAcceptPermissionRiskScore(
                scoreAt(3, PermissionRiskVerdict.LOW),
                scoreAt(5, PermissionRiskVerdict.HIGH),
            )
        )
        // A slow classification from an earlier batch must not answer later calls.
        assertFalse(
            shouldAcceptPermissionRiskScore(
                scoreAt(5, PermissionRiskVerdict.HIGH),
                scoreAt(3, PermissionRiskVerdict.LOW),
            )
        )
    }
}

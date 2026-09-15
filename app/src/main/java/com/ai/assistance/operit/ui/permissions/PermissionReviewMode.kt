package com.ai.assistance.operit.ui.permissions

/**
 * Levels for automatic (reviewer) approval.
 *
 * [STRICT] is the conservative baseline: every reviewed action is judged by the blocking reviewer
 * on its own current evidence, and no earlier verdict can answer a later call.
 *
 * [FAST] additionally runs the asynchronous risk classifier ([PermissionRiskScorer]), mirroring
 * the adaptive scoring in OpenAI Codex: a lightweight model call scores the current course of
 * action out of band, and a recent low-risk score answers the next reviewed calls without running
 * the blocking reviewer at all.
 *
 * Neither level changes the deterministic levels. `ALLOW`, `ASK` and `FORBID` are resolved before
 * automatic review is considered, so the fast level can never widen a denied action, and a
 * missing, stale, failed, or high-risk score simply falls back to the blocking reviewer. The
 * classifier can defer an approval, never deny an action on its own.
 */
enum class PermissionReviewMode {
    STRICT,
    FAST;

    companion object {
        /**
         * Fast is the default. The classifier only ever removes reviews: when it is unavailable
         * the app behaves exactly like the strict level.
         */
        val DEFAULT = FAST

        fun fromString(value: String?): PermissionReviewMode =
            when (value?.trim()?.uppercase()) {
                STRICT.name -> STRICT
                FAST.name -> FAST
                // An older build stored its strict default as an absent key, and no other value is
                // recognized, so an absent or unknown entry selects the current default.
                else -> DEFAULT
            }
    }
}

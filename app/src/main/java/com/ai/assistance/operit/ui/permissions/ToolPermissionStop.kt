package com.ai.assistance.operit.ui.permissions

import kotlin.math.roundToInt

/**
 * One stop of the tool permission slider, ordered from the most restrictive to the most permissive.
 *
 * Storage keeps two settings apart: the level a tool takes, and whether an automatic review may
 * reuse a recent verdict. The slider offers them as the single ordered choice the user makes, so
 * the automatic review level becomes two neighbouring stops that differ only in that reuse.
 */
enum class ToolPermissionStop(
    val level: PermissionLevel,
    /** The reuse level this stop selects, or null when it leaves the stored one alone. */
    val reviewMode: PermissionReviewMode?,
) {
    FORBID(PermissionLevel.FORBID, null),
    ASK(PermissionLevel.ASK, null),
    AUTO_REVIEW_STRICT(PermissionLevel.AUTO_REVIEW, PermissionReviewMode.STRICT),
    AUTO_REVIEW_FAST(PermissionLevel.AUTO_REVIEW, PermissionReviewMode.FAST),
    ALLOW(PermissionLevel.ALLOW, null);

    companion object {
        /**
         * The stop a fresh install starts on: automatic review with the fast reuse.
         *
         * It is the pair of the two stored defaults, which select it on their own when nothing is
         * stored yet: an absent level key reads as [PermissionLevel.AUTO_REVIEW] and an absent reuse
         * key reads as [PermissionReviewMode.FAST]. Naming the stop once, here, is what keeps the
         * stored default and the first frame the slider shows from drifting apart.
         */
        val DEFAULT: ToolPermissionStop = AUTO_REVIEW_FAST

        /**
         * The stop a slider position sits on. A freely dragged value carries no stop of its own, so
         * it is rounded to the nearest one and kept inside the range.
         */
        fun at(position: Float): ToolPermissionStop {
            val stops = values()
            return stops[position.roundToInt().coerceIn(stops.indices)]
        }

        /** The stop that shows the given stored settings. */
        fun of(level: PermissionLevel, reviewMode: PermissionReviewMode): ToolPermissionStop =
            when (level) {
                PermissionLevel.FORBID -> FORBID
                PermissionLevel.ASK -> ASK
                PermissionLevel.ALLOW -> ALLOW
                PermissionLevel.AUTO_REVIEW ->
                    if (reviewMode == PermissionReviewMode.FAST) AUTO_REVIEW_FAST
                    else AUTO_REVIEW_STRICT
            }
    }
}

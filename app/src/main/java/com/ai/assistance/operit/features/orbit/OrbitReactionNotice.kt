package com.ai.assistance.operit.features.orbit

/**
 * When the user puts an emoji on one of Orbit's messages, Orbit is told in a hidden turn, like
 * Muse: the chat does not show it, but it stays in the conversation. Orbit then decides by itself
 * to go on (a 👍 on "shall I draft it?"), say something, or call [NOTHING_TO_DO] and stay quiet
 * (a ❤️ on work it already finished).
 */
object OrbitReactionNotice {
    /** First line of every notice; it marks the hidden turn as a reaction for the activity list. */
    const val MARKER = "[orbit-reaction]"
    const val NOTHING_TO_DO = "orbit_nothing_to_do"
    private val markerLine = Regex(Regex.escape(MARKER) + "[ \\t]*([^\\n]+)")

    fun build(english: Boolean, emoji: String, quoted: String): String {
        // Start and end of a long message: an offer like "shall I draft it?" usually ends it.
        val quote = com.ai.assistance.operit.util.QuoteExcerpt.of(quoted)
        return if (english) {
            "$MARKER $emoji\n" +
                "The user reacted $emoji to this message of yours. This is not a new message; they typed nothing.\n" +
                "“$quote”\n" +
                "Decide how to follow up yourself: if it answers an offer or question of yours, go ahead and do it; " +
                "it picks this message among several options only when you clearly asked them to choose one; " +
                "if it only shows they are pleased, call $NOTHING_TO_DO and stay quiet; say something short only when it calls for it."
        } else {
            "$MARKER $emoji\n" +
                "用户给你的这条消息点了 $emoji。这不是新消息，用户没有打字。\n" +
                "「$quote」\n" +
                "你自己决定怎么接：如果是在回答你的提议或问题，就直接照着做；" +
                "只有你明确让用户从几条里选一个时，才算选了这一条；" +
                "如果只是表示满意、喜欢，调用 $NOTHING_TO_DO 保持安静；确实需要时才简短回一句。"
        }
    }

    /** The emoji of a stored notice (the app may have added context around it), or null. */
    fun emojiOf(content: String): String? =
        markerLine.find(content)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

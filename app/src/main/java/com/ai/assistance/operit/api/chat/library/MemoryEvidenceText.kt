package com.ai.assistance.operit.api.chat.library

import com.ai.assistance.operit.util.ChatUtils

/** Only assistant output is interpreted as reasoning markup; quoted user/tool evidence stays literal. */
internal fun memoryEvidenceText(role: String, text: String): String =
    if (role.equals("assistant", true) || role.equals("ai", true)) {
        val stripped = ChatUtils.stripGeminiThoughtSignatureMeta(ChatUtils.stripOpenAiResponsesReasoningMeta(text))
        // A bounded database prefix can end inside protocol metadata. Closed blocks are already
        // stripped; discard the tail of any remaining recognized block instead of leaking payload.
        val opening = reasoningMetaOpening.find(stripped)
        ChatUtils.removeThinkingContent(if (opening != null) stripped.take(opening.range.first) else stripped)
    } else text

private val reasoningMetaOpening = Regex(
    """<meta\b[^>]*\bprovider\s*=\s*["'](?:openai:responses_reasoning|gemini:thought_signature|gemini:content)["'][^>]*(?:>|$)""",
    RegexOption.IGNORE_CASE
)

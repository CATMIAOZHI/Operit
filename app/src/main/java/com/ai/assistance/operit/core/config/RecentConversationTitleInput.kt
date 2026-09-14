package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.util.ChatUtils

/** Keep recent dialogue bounded so an old topic or a long reply cannot dominate the title. */
internal object RecentConversationTitleInput {
    fun build(messages: List<Pair<String, String>>): String =
        messages.asReversed().asSequence()
            .filter { it.first == "user" || it.first == "ai" }
            .map { (sender, content) ->
                sender to ChatUtils.removeThinkingContent(
                    ChatUtils.stripOpenAiResponsesReasoningMeta(
                        ChatUtils.stripGeminiThoughtSignatureMeta(content),
                    ),
                ).trim()
            }
            .filter { it.second.isNotBlank() }
            .take(12)
            .toList().asReversed()
            .joinToString("\n\n") { (sender, content) ->
                "${if (sender == "user") "User" else "Assistant"}: ${content.takeLast(500)}"
            }
}

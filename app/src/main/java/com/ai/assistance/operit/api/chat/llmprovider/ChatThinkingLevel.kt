package com.ai.assistance.operit.api.chat.llmprovider

import android.content.Context
import android.content.ContextWrapper
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.stream.Stream
import kotlinx.coroutines.flow.first

/**
 * Carries one chat's own thinking level to the provider building its request. Providers read the
 * level from the context they are given, so a chat set to "high" never changes what another chat,
 * or the saved composer level, sends.
 */
internal class ChatThinkingContext(base: Context, val thinkingQualityLevel: Int) : ContextWrapper(base)

/** [this], carrying [level] for the request, or as it is when the chat has no level of its own. */
internal fun Context.withChatThinkingLevel(level: Int?): Context =
    if (level == null) this
    else ChatThinkingContext(
        (this as? ChatThinkingContext)?.baseContext ?: this,
        level.coerceIn(ApiPreferences.MIN_THINKING_QUALITY_LEVEL, ApiPreferences.MAX_THINKING_QUALITY_LEVEL),
    )

/** The thinking level a request runs at: its chat's own, else the saved composer level. */
internal suspend fun requestThinkingQualityLevel(context: Context): Int =
    (context as? ChatThinkingContext)?.thinkingQualityLevel
        ?: ApiPreferences.getInstance(context).thinkingQualityLevelFlow.first()

/** Sends every request of one chat turn at that chat's own thinking level. */
internal class ChatThinkingAIService(
    private val delegate: AIService,
    private val thinkingQualityLevel: Int,
) : AIService by delegate {
    override suspend fun sendMessage(
        context: Context,
        chatHistory: List<PromptTurn>,
        modelParameters: List<ModelParameter<*>>,
        enableThinking: Boolean,
        stream: Boolean,
        availableTools: List<ToolPrompt>?,
        preserveThinkInHistory: Boolean,
        onTokensUpdated: suspend (input: Int, cachedInput: Int, output: Int) -> Unit,
        onUsageReported: (suspend (ProviderUsageSnapshot, attempt: Int) -> Unit)?,
        onNonFatalError: suspend (error: String) -> Unit,
        enableRetry: Boolean,
        statsCategory: TokenStatCategory?,
    ): Stream<String> =
        delegate.sendMessage(
            context = context.withChatThinkingLevel(thinkingQualityLevel),
            chatHistory = chatHistory,
            modelParameters = modelParameters,
            enableThinking = enableThinking,
            stream = stream,
            availableTools = availableTools,
            preserveThinkInHistory = preserveThinkInHistory,
            onTokensUpdated = onTokensUpdated,
            onUsageReported = onUsageReported,
            onNonFatalError = onNonFatalError,
            enableRetry = enableRetry,
            statsCategory = statsCategory,
        )
}

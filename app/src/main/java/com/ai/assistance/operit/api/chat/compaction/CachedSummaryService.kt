package com.ai.assistance.operit.api.chat.compaction

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.MultiServiceManager
import com.ai.assistance.operit.api.chat.llmprovider.OpenCodeSessionContext
import com.ai.assistance.operit.api.chat.llmprovider.providerSessionIdForScope
import com.ai.assistance.operit.api.chat.prediction.ComposerPredictionService
import com.ai.assistance.operit.api.chat.prediction.ComposerPredictionSnapshot
import com.ai.assistance.operit.api.chat.prediction.predictionConfig
import com.ai.assistance.operit.api.chat.prediction.predictionParameters
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.ModelConfigData
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.util.ChatUtils
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Writes a summary by sending the request the chat's latest turn sent, unchanged, with one more
 * user message asking for the checkpoint. The provider has just processed that request, so it reads
 * the whole prefix from its cache. This only works on the model that served the turn, with the same
 * tools, parameters and thinking setting; the caller checks the summary model is that model.
 */
class CachedSummaryService internal constructor(
    private val context: Context,
    private val acquireService: suspend (ModelConfigData, List<ModelParameter<*>>) -> MultiServiceManager.ServiceLease,
) {
    constructor(context: Context) : this(
        context.applicationContext,
        MultiServiceManager(context.applicationContext)::acquireIsolatedService,
    )

    suspend fun summarize(snapshot: ComposerPredictionSnapshot, instruction: String): String {
        require(ComposerPredictionService.supports(snapshot.modelConfig)) { "Provider cannot reuse the turn's request" }
        require(snapshot.history.lastOrNull()?.kind == PromptTurnKind.ASSISTANT) { "No completed turn to continue" }
        val parameters = predictionParameters(snapshot.modelParameters)
        return withTimeout(REQUEST_TIMEOUT_MS) {
            withContext(
                Dispatchers.IO + OpenCodeSessionContext(
                    snapshot.providerSessionId ?: providerSessionIdForScope("compaction:${snapshot.chatId}"),
                    snapshot.workspacePath,
                ),
            ) {
                val lease = acquireService(predictionConfig(snapshot.modelConfig), parameters)
                try {
                    coroutineScope {
                        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { lease.service.cancelStreaming() }
                        }
                        try {
                            val output = StringBuilder()
                            var providerFailed = false
                            lease.service.sendMessage(
                                context = context,
                                chatHistory = snapshot.history + PromptTurn(kind = PromptTurnKind.USER, content = instruction),
                                modelParameters = parameters,
                                enableThinking = snapshot.enableThinking,
                                stream = true,
                                availableTools = snapshot.availableTools,
                                preserveThinkInHistory = false,
                                // A failed attempt falls back to the summary request; a retry here
                                // would report an error and have its paid result thrown away.
                                enableRetry = false,
                                statsCategory = TokenStatCategory.SUMMARY,
                                onNonFatalError = { providerFailed = true },
                            ).collect { chunk ->
                                if (output.length + chunk.length > MAX_RESPONSE_CHARS) {
                                    throw IOException("Summary exceeded the response limit")
                                }
                                output.append(chunk)
                            }
                            if (providerFailed) throw IOException("Summary provider reported an error")
                            val text = ChatUtils.removeThinkingContent(output.toString()).trim()
                            // A reply that calls a tool instead of writing the checkpoint is no summary.
                            if (text.isEmpty() || toolMarkup.containsMatchIn(text)) {
                                throw IOException("The reply was not a checkpoint")
                            }
                            text
                        } finally {
                            cancellationWatcher.cancel()
                        }
                    }
                } finally {
                    try { lease.service.cancelStreaming() } finally { lease.close() }
                }
            }
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 600_000L
        /** A buffering bound on the stream, which also carries the reasoning block. */
        const val MAX_RESPONSE_CHARS = 1_048_576
        val toolMarkup = Regex("<tool_[A-Za-z0-9]*\\s+name=|<tool\\s+name=|tool_call|function_call", RegexOption.IGNORE_CASE)
    }
}

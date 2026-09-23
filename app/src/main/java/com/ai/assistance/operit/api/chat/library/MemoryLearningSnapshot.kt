package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ApiProviderType
import com.ai.assistance.operit.data.model.ModelConfigData

/** Detached, bounded evidence: never rewrites the foreground transcript or its cached prefix. */
internal object MemoryLearningSnapshot {
    data class Snapshot(val text: String, val contextWindow: Int)
    suspend fun build(context: Context, messages: List<Pair<String, String>>, instructionBytes: Int, includeThinking: Boolean = false): Snapshot {
        val config = EnhancedAIService.getModelConfigForFunction(context, FunctionType.MEMORY)
        val window = modelWindowTokens(config)
        // UTF-8 bytes are deliberately conservative, leaving most of the window for instructions,
        // tools and responses. A character limit alone badly underestimates Chinese and code.
        val sourceBudget = sourceBudget(window, instructionBytes)
        require(sourceBudget >= 256) { "Memory review instructions leave insufficient context for source evidence" }
        return Snapshot(digest(messages, sourceBudget, includeThinking), window)
    }

    /**
     * The model's usable context window in tokens for one review batch.
     *
     * A batch is a single detached prompt that is never summarized or compacted, so it is bounded by
     * what the model can actually hold in one request rather than by the window the chat manages the
     * live conversation to. Those are two different fields: [ModelConfigData.contextLength] is the
     * management length the chat summarizes against, [ModelConfigData.maxContextLength] is what the
     * API supports. Both are user-entered and neither is validated against the other, so the larger
     * usable one wins; the chat's own mode switch does not apply here.
     *
     * A length that cannot even hold the instructions and a response is not a candidate, and then the
     * conservative fallback applies instead of an error, so no configuration a review used to run
     * under can start failing.
     */
    internal fun modelWindowTokens(config: ModelConfigData): Int {
        fun positive(value: Float) = value.takeIf { it.isFinite() && it > 0f } ?: 0f
        val length = listOf(positive(config.contextLength), positive(config.maxContextLength))
            .filter { it * 1000f >= MIN_WINDOW_TOKENS }
            .maxOrNull() ?: 0f
        var window = if (length > 0f) (length.toDouble() * 1000).coerceAtMost(2_000_000.0).toInt()
            else FALLBACK_WINDOW_TOKENS
        val provider = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId) ?: config.apiProviderType
        if (provider == ApiProviderType.LLAMA_CPP) window = minOf(window, config.llamaContextSize)
        if (provider == ApiProviderType.MNN) window = minOf(window, 2048)
        // A review needs room for instructions, evidence and a response.
        require(window >= MIN_WINDOW_TOKENS) { "Memory review requires a configured context window of at least $MIN_WINDOW_TOKENS tokens" }
        return window
    }

    /** Smallest window a review can work in. */
    private const val MIN_WINDOW_TOKENS = 4096
    /** Used instead of an error when no configured length can hold a review. */
    private const val FALLBACK_WINDOW_TOKENS = 8192

    /**
     * Bytes of conversation evidence one review batch may carry. The reader accounts in UTF-8 bytes,
     * which is deliberately conservative: a token is never smaller than a byte for real text, so a
     * byte budget can never overrun the model's token window. A third of the window is the source and
     * the rest carries instructions, lookups and the response.
     *
     * There is no small fixed ceiling: the old 24 KB cap bound the batch long before the model's own
     * window did, so one long conversation became a dozen small reviews that each re-read the skill
     * catalog, could not see what a neighbouring batch had decided, and spent their round budget
     * before covering their own source. [SOURCE_CEILING_BYTES] still bounds a single request.
     */
    internal fun sourceBudget(window: Int, instructionBytes: Int): Int =
        minOf(SOURCE_CEILING_BYTES, window / SOURCE_WINDOW_SHARE, evidenceBudget(window) - instructionBytes)
            .coerceAtLeast(0)

    /**
     * Total evidence one batch may carry, source and lookups together. [sourceBudget] deducts its
     * source and instructions from this same number, so the two must never drift apart: a source that
     * exceeds the batch's own budget is discarded on its first request without doing any work.
     */
    internal fun evidenceBudget(window: Int): Int = (window * EVIDENCE_WINDOW_FRACTION).toInt()

    /** Share of the model window one batch's source may take. */
    private const val SOURCE_WINDOW_SHARE = 3
    /** Upper bound for one batch's source, so a huge advertised window cannot ask for one giant prompt. */
    private const val SOURCE_CEILING_BYTES = 96_000
    /** Share of the model window a batch may spend in total before it is stopped. */
    internal const val EVIDENCE_WINDOW_FRACTION = 0.75

    fun digest(messages: List<Pair<String, String>>, byteBudget: Int, includeThinking: Boolean = false): String {
        val header = "[Bounded evidence excerpts; omissions are not evidence of absence. Use history for details.]\n"
        val available = (byteBudget - header.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        // Strip before truncation: a page/excerpt starting inside a think block has no opening tag.
        val visible = messages.map { (role, text) -> role to memoryEvidenceText(role, text, includeThinking) }
            .filter { it.second.isNotBlank() }
        val summaries = visible.filter { it.first == "SUMMARY" }
        val other = visible.filter { it.first != "SUMMARY" }
        val older = other.dropLast(24).filter { it.first != "TOOL_RESULT" }
        val summaryBudget = if (summaries.isEmpty()) 0 else available / 4
        val olderBudget = if (older.isEmpty()) 0 else available / 4
        val recentBudget = available - summaryBudget - olderBudget
        fun excerpts(items: List<Pair<String, String>>, budget: Int, perMessage: Int, recent: Boolean): String {
            if (budget <= 0 || items.isEmpty()) return ""
            val capacity = (budget / 80).coerceAtLeast(1)
            val sampled = if (!recent && items.size > capacity) {
                (0 until capacity).map { index ->
                    items[if (capacity == 1) 0 else (index.toLong() * (items.size - 1) / (capacity - 1)).toInt()]
                }
            } else items
            val lines = if (recent) sampled.asReversed() else sampled
            val chunks = mutableListOf<String>()
            var remaining = budget
            val share = if (recent) perMessage else minOf(perMessage, budget / sampled.size)
            for ((role, text) in lines) {
                if (remaining < 32 || share < 32) break
                val chunk = fit("$role: ${text.take(6000)}\n", minOf(remaining, share))
                chunks += chunk
                remaining -= chunk.toByteArray(Charsets.UTF_8).size
            }
            return (if (recent) chunks.asReversed() else chunks).joinToString("")
        }
        return fit(header, byteBudget) +
            excerpts(summaries, summaryBudget, summaryBudget, true) +
            excerpts(older, olderBudget, 400, false) +
            excerpts(other.takeLast(24), recentBudget, 2400, true)
    }

    private fun fit(text: String, bytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= bytes) return text
        if (bytes < 4) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (text.substring(0, mid).toByteArray(Charsets.UTF_8).size <= bytes - 4) lo = mid else hi = mid - 1
        }
        if (lo > 0 && text[lo - 1].isHighSurrogate()) lo--
        return text.take(lo) + "...\n"
    }
}

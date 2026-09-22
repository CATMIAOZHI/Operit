package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.model.ApiProviderType

/** Detached, bounded evidence: never rewrites the foreground transcript or its cached prefix. */
internal object MemoryLearningSnapshot {
    data class Snapshot(val text: String, val contextWindow: Int)
    suspend fun build(context: Context, messages: List<Pair<String, String>>, instructionBytes: Int, includeThinking: Boolean = false): Snapshot {
        val config = EnhancedAIService.getModelConfigForFunction(context, FunctionType.MEMORY)
        val length = if (config.enableMaxContextMode) config.maxContextLength else config.contextLength
        var window = if (length.isFinite() && length > 0) (length.toDouble() * 1000).coerceAtMost(2_000_000.0).toInt() else 8192
        val provider = ApiProviderType.fromProviderTypeId(config.apiProviderTypeId) ?: config.apiProviderType
        if (provider == ApiProviderType.LLAMA_CPP) window = minOf(window, config.llamaContextSize)
        if (provider == ApiProviderType.MNN) window = minOf(window, 2048)
        require(window >= 4096) { "Memory review requires a configured context window of at least 4096 tokens" }
        // UTF-8 bytes are deliberately conservative, leaving most of the window for instructions,
        // tools and responses. A character limit alone badly underestimates Chinese and code.
        val sourceBudget = sourceBudget(window, instructionBytes)
        require(sourceBudget >= 256) { "Memory review instructions leave insufficient context for source evidence" }
        return Snapshot(digest(messages, sourceBudget, includeThinking), window)
    }

    internal fun sourceBudget(window: Int, instructionBytes: Int): Int =
        minOf(24_000, window / 3, (window * 0.75).toInt() - instructionBytes).coerceAtLeast(0)

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

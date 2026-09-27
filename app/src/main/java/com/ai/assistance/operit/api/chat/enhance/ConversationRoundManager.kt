package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChunkedTextBuffer
import com.ai.assistance.operit.util.SegmentedText
import com.ai.assistance.operit.util.MemoryCounters
import com.ai.assistance.operit.util.MemoryDiagnosticMetrics
import com.ai.assistance.operit.util.MemoryMetricSource
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages conversation rounds for the AI assistant.
 *
 * This class is responsible for tracking and managing different rounds of conversation,
 * particularly when tools are being executed and responses are processed.
 */
class ConversationRoundManager : MemoryMetricSource {
    @Volatile private var diagnosticChars = 0L
    init { MemoryDiagnosticMetrics.register(this) }
    override fun memoryCounters() = MemoryCounters(displayBuffers = 1, displayChars = diagnosticChars)
    companion object {
        private const val TAG = "ConversationRoundManager"
        private const val ROUND_SEPARATOR_FORMAT = "--- Round %d ---\n"
    }

    // Map to store content for each round
    private val roundContents = mutableMapOf<Int, ChunkedTextBuffer>()

    // Tracks the current round number
    private val currentResponseRound = AtomicInteger(0)

    // Pattern used to remove round separators from displayed content
    private val roundSeparatorPattern = Regex("--- Round \\d+ ---\n")

    /** Initializes a new conversation, resetting all round tracking. */
    fun initializeNewConversation() {
        currentResponseRound.set(0)
        roundContents.clear()
        diagnosticChars = 0
        AppLogger.d(TAG, "New conversation initialized")
    }

    /**
     * Updates content for the current round.
     *
     * @param content The content to be added or updated
     * @return The accumulated content after update
     */
    fun updateContent(content: String): String {
        val currentRound = currentResponseRound.get()
        val round = roundContents.getOrPut(currentRound) { ChunkedTextBuffer() }
        val oldLength = round.length
        round.replace(content)
        diagnosticChars += content.length - oldLength
        return getDisplayContent()
    }

    /** Appends a streamed chunk without rebuilding the current round's complete text. */
    fun appendChunk(content: String) {
        val currentRound = currentResponseRound.get()
        roundContents.getOrPut(currentRound) { ChunkedTextBuffer() }.append(content)
        diagnosticChars += content.length
    }

    /**
     * Starts a new round.
     *
     * @return The display separator that must also be emitted into the live stream
     */
    fun startNewRound(): String {
        val separator = if (roundContents.keys.any { it >= 0 }) "\n" else ""
        val newRound = currentResponseRound.incrementAndGet()
        roundContents[newRound] = ChunkedTextBuffer()
        AppLogger.d(TAG, "Starting new round: $newRound")
        return separator
    }

    /**
     * Appends content to the end of the accumulated content, outside any round structure.
     *
     * @param content The content to append
     * @return The updated display content
     */
    fun appendContent(content: String): String {
        appendChunk("\n" + content.trim())
        return getDisplayContent()
    }

    /**
     * Gets the content suitable for display, with round separators removed.
     *
     * @return Clean content without round separators
     */
    fun getDisplayContent(): String = getDisplaySnapshot().toString()

    /** Tool batches can retain these immutable snapshots without retaining a full copy per batch. */
    fun getDisplaySnapshot(): SegmentedText {
        val sortedKeys = roundContents.keys.filter { it >= 0 }.sorted()
        val parts = sortedKeys.map { roundContents.getValue(it).snapshot() }.toMutableList()
        roundContents[-1]?.let {
            if (parts.isEmpty()) parts.add(SegmentedText(emptyList()))
            parts.add(it.snapshot())
        }
        return SegmentedText.join(parts, "\n")
    }

    fun getCurrentRoundContent(): String {
        return roundContents[currentResponseRound.get()]?.snapshot()?.toString().orEmpty()
    }

    /**
     * Gets the raw accumulated content including all round separators.
     *
     * @return Raw content with round separators
     */
    fun getRawContent(): String {
        val buffer = StringBuilder()

        // Add rounds in order with separators
        val sortedKeys = roundContents.keys.filter { it >= 0 }.sorted()

        sortedKeys.forEachIndexed { index, round ->
            val content = roundContents[round]?.snapshot() ?: return@forEachIndexed
            if (index > 0) buffer.append("\n")
            buffer.append(String.format(ROUND_SEPARATOR_FORMAT, round))
            buffer.append(content)
        }

        // Append any content that's outside rounds (key -1)
        if (roundContents.containsKey(-1)) {
            buffer.append("\n").append(roundContents[-1]?.snapshot())
        }

        return buffer.toString()
    }

    /**
     * Gets the current round number.
     *
     * @return Current round number
     */
    fun getCurrentRound(): Int {
        return currentResponseRound.get()
    }

    /** Clears all content. */
    fun clearContent() {
        roundContents.clear()
        diagnosticChars = 0
        AppLogger.d(TAG, "Content cleared")
    }
}

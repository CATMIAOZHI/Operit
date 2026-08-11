package com.ai.assistance.operit.util

internal enum class DisplayEndMatchResult {
    MATCH,
    IN_PROGRESS,
    NO_MATCH,
}

/**
 * Incrementally recognizes the closing grammar shared by display-only XML blocks.
 *
 * Think and thinking are one family, matching is case-insensitive, and whitespace is accepted
 * between the closing name and `>`. Search uses the same whitespace/case rules but remains its own
 * family.
 */
internal class DisplayEndTagMatcher(private val validNames: Set<String>) {
    private var phase = 0
    private val matchedName = StringBuilder()

    var candidateLength: Int = 0
        private set

    var lastMatchLength: Int = 0
        private set

    fun processChar(char: Char): DisplayEndMatchResult {
        lastMatchLength = 0
        return when (phase) {
            0 -> if (char == '<') {
                phase = 1
                candidateLength = 1
                DisplayEndMatchResult.IN_PROGRESS
            } else {
                DisplayEndMatchResult.NO_MATCH
            }
            1 -> if (char == '/') {
                phase = 2
                matchedName.clear()
                candidateLength += 1
                DisplayEndMatchResult.IN_PROGRESS
            } else {
                restartFrom(char)
            }
            2 -> {
                when {
                    char.isLetter() -> {
                        matchedName.append(char.lowercaseChar())
                        candidateLength += 1
                        if (validNames.any { it.startsWith(matchedName.toString()) }) {
                            DisplayEndMatchResult.IN_PROGRESS
                        } else {
                            restartFrom(char)
                        }
                    }
                    char.isWhitespace() && matchedName.toString() in validNames -> {
                        phase = 3
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    char == '>' && matchedName.toString() in validNames -> {
                        candidateLength += 1
                        completeMatch()
                    }
                    else -> restartFrom(char)
                }
            }
            else -> {
                when {
                    char.isWhitespace() -> {
                        candidateLength += 1
                        DisplayEndMatchResult.IN_PROGRESS
                    }
                    char == '>' -> {
                        candidateLength += 1
                        completeMatch()
                    }
                    else -> restartFrom(char)
                }
            }
        }
    }

    fun reset() {
        resetCandidate()
        lastMatchLength = 0
    }

    private fun completeMatch(): DisplayEndMatchResult {
        lastMatchLength = candidateLength
        resetCandidate()
        return DisplayEndMatchResult.MATCH
    }

    private fun resetCandidate() {
        phase = 0
        matchedName.clear()
        candidateLength = 0
    }

    private fun restartFrom(char: Char): DisplayEndMatchResult {
        resetCandidate()
        return if (char == '<') {
            phase = 1
            candidateLength = 1
            DisplayEndMatchResult.IN_PROGRESS
        } else {
            DisplayEndMatchResult.NO_MATCH
        }
    }
}

internal fun displayEndTagNames(tagName: String): Set<String>? =
    when (tagName.lowercase()) {
        "think", "thinking" -> setOf("think", "thinking")
        "search" -> setOf("search")
        else -> null
    }

internal fun findDisplayEndTagRange(
    content: String,
    tagName: String,
    fromIndex: Int = 0,
): IntRange? {
    val validNames = displayEndTagNames(tagName) ?: return null
    val matcher = DisplayEndTagMatcher(validNames)
    for (index in fromIndex.coerceAtLeast(0) until content.length) {
        if (matcher.processChar(content[index]) == DisplayEndMatchResult.MATCH) {
            val start = index - matcher.lastMatchLength + 1
            return start..index
        }
    }
    return null
}

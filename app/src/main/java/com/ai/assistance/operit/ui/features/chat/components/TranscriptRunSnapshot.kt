package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.runtime.compositionLocalOf
import com.ai.assistance.operit.data.model.SubagentRunEntity

/** One subscription for the timeline, rather than a fresh database query for each recycled card. */
internal data class TranscriptRunSnapshot(val chatId: String, val runs: List<SubagentRunEntity>)
internal val LocalTranscriptRuns = compositionLocalOf<TranscriptRunSnapshot?> { null }

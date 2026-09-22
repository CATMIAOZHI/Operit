package com.ai.assistance.operit.api.chat.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit UI review uses the same extractor, without waiting for the background queue threshold. */
object MemoryLearningService {
    suspend fun extract(context: Context, profileId: String, chatId: String) = withContext(Dispatchers.IO) {
        MemoryLearningCoordinator.manualReview(context,profileId,chatId)
    }
}

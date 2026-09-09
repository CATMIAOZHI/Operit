package com.ai.assistance.operit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory database only; never opens the application's database or runtime singletons. */
@RunWith(AndroidJUnit4::class)
class IntermediateMessageRepairAndroidTest {
    @Test fun repairRemovesOnlyExactIntermediateCopiesAndIsIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            db.chatDao().insertChat(ChatEntity(id = "fixture", title = "fixture"))
            db.chatDao().insertChat(ChatEntity(id = "other", title = "other"))
            val original = MessageEntity(
                chatId = "fixture", sender = "ai", content = "tool result".repeat(20000),
                timestamp = 100, orderIndex = 0, displayMode = "ASSISTANT_INTERMEDIATE",
            )
            val dao = db.messageDao()
            val keptId = dao.insertMessage(original)
            repeat(26) { dao.insertMessage(original.copy(orderIndex = it + 1)) }
            dao.insertMessage(original.copy(orderIndex = 27, content = "different"))
            dao.insertMessage(original.copy(orderIndex = 28, isFavorite = true))
            dao.insertMessage(original.copy(orderIndex = 29, selectedVariantIndex = 1))
            repeat(2) { dao.insertMessage(original.copy(orderIndex = 30 + it, sender = "user")) }
            repeat(2) { dao.insertMessage(original.copy(orderIndex = 32 + it, displayMode = "NORMAL")) }
            repeat(2) { dao.insertMessage(original.copy(chatId = "other", orderIndex = it)) }
            assertEquals(26, dao.deleteExactDuplicateIntermediateMessages("fixture"))
            assertEquals(0, dao.deleteExactDuplicateIntermediateMessages("fixture"))
            val rows = db.chatContentDao().getMessagesForChat("fixture")
            assertEquals(8, rows.size)
            assertTrue(rows.any { it.messageId == keptId && it.content == original.content })
            assertEquals(2, db.chatContentDao().getMessagesForChat("other").size)
        } finally {
            db.close()
        }
    }
}

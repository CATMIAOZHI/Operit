package com.ai.assistance.operit.data.dao

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.MessageEntity
import com.ai.assistance.operit.data.model.MessageVariantEntity
import com.ai.assistance.operit.data.stats.JdbcSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ChatContentDaoTest {
    private lateinit var tempDir: File
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("chat-content-dao-test").toFile()
        database =
            Room.databaseBuilder(mockContext(tempDir), AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun `large message and variant content are materialized without truncation`() = runBlocking {
        val chatId = "large-content-chat"
        val timestamp = 1234L
        val messageContent = buildLargeContent("message")
        val variantContent = buildLargeContent("variant")

        database.chatDao().insertChat(ChatEntity(id = chatId, title = "Large content"))
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                sender = "ai",
                content = messageContent,
                timestamp = timestamp,
                orderIndex = 0,
            )
        )
        database.messageVariantDao().insertVariant(
            MessageVariantEntity(
                chatId = chatId,
                messageTimestamp = timestamp,
                variantIndex = 1,
                content = variantContent,
            )
        )

        val contentDao = database.chatContentDao()
        assertEquals(messageContent, contentDao.getMessagesForChat(chatId).single().content)
        assertEquals(messageContent, contentDao.getMessageByTimestamp(chatId, timestamp)?.content)
        assertEquals(
            variantContent,
            contentDao.getVariantsForMessages(chatId, listOf(timestamp)).single().content,
        )
        assertEquals(
            variantContent,
            contentDao.getVariantForMessage(chatId, timestamp, 1)?.content,
        )
    }

    private fun buildLargeContent(label: String): String =
        buildString {
            append("$label-start|")
            repeat(140_000) { index ->
                append(if (index % 97 == 0) "你" else "x")
            }
            append("|$label-end")
        }

    private fun mockContext(filesDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(filesDir, invocation.getArgument<String>(0))
        }
        return context
    }
}

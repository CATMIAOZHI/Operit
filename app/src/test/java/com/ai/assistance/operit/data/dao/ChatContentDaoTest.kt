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
    fun `FTS tracks edits and deletion with bounded snippets and visible filtered sessions`() = runBlocking {
        val dao = database.chatContentDao()
        database.chatDao().insertChat(ChatEntity(id="fts",title="FTS",characterCardName="work"))
        database.chatDao().insertChat(ChatEntity(id="secret",title="Hidden",isHidden=true))
        val id=database.messageDao().insertMessage(MessageEntity(chatId="fts",sender="user",
            content="needle "+"x".repeat(2_500_000),timestamp=10,orderIndex=0))
        database.messageDao().insertMessage(MessageEntity(chatId="secret",sender="user",
            content="needle secret",timestamp=10,orderIndex=0))
        val hit=dao.searchRecallIndex("\"needle\"","user","work","",0,20,20,0).single()
        assertEquals(id,hit.messageId)
        assertEquals(true,hit.excerpt.length<=2000)
        assertEquals(emptyList<ChatRecallHit>(),dao.searchRecallIndex("\"needle\"","ai","","",0,20,20,0))
        database.messageDao().updateMessageContent(id,"replacement")
        assertEquals(emptyList<ChatRecallHit>(),dao.searchRecallIndex("\"needle\"","","","",0,20,20,0))
        assertEquals(1,dao.searchRecallIndex("\"replacement\"","","","",0,20,20,0).size)
        database.messageDao().deleteAllMessagesForChat("fts")
        assertEquals(emptyList<ChatRecallHit>(),dao.searchRecallIndex("\"replacement\"","","","",0,20,20,0))
        assertEquals(listOf("fts"),dao.browseRecallSessions("work",0,Long.MAX_VALUE,20,0).map { it.chatId })
    }

    @Test
    fun `long messages with emoji can be read in complete codepoint pages`() = runBlocking {
        database.chatDao().insertChat(ChatEntity(id="pages",title="Pages"))
        val text="😀".repeat(5000)+"终点\u0000"+"word ".repeat(3000)
        val id=database.messageDao().insertMessage(MessageEntity(chatId="pages",sender="ai",content=text,orderIndex=0))
        val repo=com.ai.assistance.operit.data.repository.ChatRecallRepository(database.chatContentDao())
        var offset=0
        val collected=StringBuilder()
        while(true) {
            val page=repo.execute(mapOf("mode" to "message","message_id" to id.toString(),"char_offset" to offset.toString()))
                .getJSONObject("message")
            collected.append(page.getString("content"))
            if(page.isNull("next_char_offset")) break
            val next=page.getInt("next_char_offset")
            org.junit.Assert.assertTrue(next>offset)
            offset=next
        }
        assertEquals(text,collected.toString())
        assertEquals(listOf(id),repo.search("终点").map { it.messageId })
    }

    @Test
    fun `recall search excludes hidden and child chats and matches wildcard text literally`() = runBlocking {
        val chats = listOf(
            ChatEntity(id = "visible", title = "Visible"),
            ChatEntity(id = "hidden", title = "Hidden", isHidden = true),
            ChatEntity(id = "child", title = "Child", parentChatId = "visible")
        )
        chats.forEach { chat ->
            database.chatDao().insertChat(chat)
            database.messageDao().insertMessage(MessageEntity(chatId = chat.id, sender = "user",
                content = "prefix %_needle suffix", timestamp = 10L, orderIndex = 0))
        }
        database.messageDao().insertMessage(MessageEntity(chatId = "visible", sender = "tool",
            content = "%_needle", timestamp = 11L, orderIndex = 1))
        val dao = database.chatContentDao()
        val hits = dao.searchRecallMessages("%_NEEDLE", 20, 0)
        assertEquals(listOf("visible"), hits.map { it.chatId })
        assertEquals(listOf("visible"), dao.readRecallContext(hits.single().messageId).map { it.chatId })
        assertEquals(emptyList<ChatRecallHit>(), dao.searchRecallMessages("", 20, 0))
        assertEquals(emptyList<ChatRecallHit>(), dao.searchRecallMessages("%_needle", 20, 1))
    }

    @Test
    fun `variant lookup handles large sparse duplicate timestamp sets in query order`() = runBlocking {
        val chatId = "many-variants"
        database.chatDao().insertChat(ChatEntity(id = chatId, title = "Variants"))
        val timestamps = (1L..1100L).map { it * 10 }
        for (timestamp in timestamps + 15L) {
            database.messageVariantDao().insertVariant(MessageVariantEntity(
                chatId = chatId, messageTimestamp = timestamp, variantIndex = 1,
                content = "variant-$timestamp",
            ))
        }
        val result = database.chatContentDao().getVariantsForMessages(
            chatId, timestamps.reversed() + timestamps.take(5),
        )
        assertEquals(timestamps, result.map { it.messageTimestamp })
        assertEquals(emptyList<MessageVariantEntity>(),
            database.chatContentDao().getVariantsForMessages(chatId, emptyList()))
    }

    @Test
    fun `process metadata follows the selected variant without loading its body`() = runBlocking {
        val chatId = "process-variant"
        database.chatDao().insertChat(ChatEntity(id = chatId, title = "Process"))
        database.messageDao().insertMessage(MessageEntity(
            chatId = chatId, sender = "ai", content = "base", timestamp = 10L,
            orderIndex = 0, selectedVariantIndex = 1, sentAt = 1L, completedAt = 2L,
        ))
        database.messageVariantDao().insertVariant(MessageVariantEntity(
            chatId = chatId, messageTimestamp = 10L, variantIndex = 1, content = "selected",
            sentAt = 3L, completedAt = 9L, waitDurationMs = 4L, outputDurationMs = 5L,
        ))
        val selected = database.messageDao().getProcessMetadata(chatId).single()
        assertEquals(3L, selected.sentAt)
        assertEquals(9L, selected.completedAt)
        assertEquals(4L, selected.waitDurationMs)
        assertEquals(5L, selected.outputDurationMs)
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
        val structure = database.messageDao().getProcessMetadata(chatId).single()
        assertEquals(timestamp, structure.timestamp)
        assertEquals("ai", structure.sender)
        assertEquals("NORMAL", structure.displayMode)
        assertEquals(emptyList<com.ai.assistance.operit.data.model.ChatMessageProcessMetadata>(),
            database.messageDao().getProcessMetadata("another-chat"))
        assertEquals(messageContent, contentDao.getMessageByTimestamp(chatId, timestamp)?.content)
        assertEquals(messageContent, contentDao.getMessagesByTimestamps(chatId, listOf(timestamp)).single().content)
        assertEquals(emptyList<MessageEntity>(), contentDao.getMessagesByTimestamps("other", listOf(timestamp)))
        assertEquals(
            variantContent,
            contentDao.getVariantsForMessages(chatId, listOf(timestamp)).single().content,
        )
        assertEquals(
            variantContent,
            contentDao.getVariantForMessage(chatId, timestamp, 1)?.content,
        )
    }

    @Test
    fun `embedded NUL and split UTF-8 content are materialized without truncation`() = runBlocking {
        val chatId = "nul-content-chat"
        val timestamp = 5678L
        val messageContent = "m".repeat(65_535) + "你\u0000message-tail"
        val variantContent = "v".repeat(65_534) + "🙂\u0000variant-tail"

        database.chatDao().insertChat(ChatEntity(id = chatId, title = "NUL content"))
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

    @Test
    fun `empty message and variant content remain readable`() = runBlocking {
        val chatId = "empty-content-chat"
        val timestamp = 9012L

        database.chatDao().insertChat(ChatEntity(id = chatId, title = "Empty content"))
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = chatId,
                sender = "ai",
                content = "",
                timestamp = timestamp,
                orderIndex = 0,
            )
        )
        database.messageVariantDao().insertVariant(
            MessageVariantEntity(
                chatId = chatId,
                messageTimestamp = timestamp,
                variantIndex = 1,
                content = "",
            )
        )

        val contentDao = database.chatContentDao()
        assertEquals("", contentDao.getMessagesForChat(chatId).single().content)
        assertEquals("", contentDao.getMessageByTimestamp(chatId, timestamp)?.content)
        assertEquals(
            "",
            contentDao.getVariantsForMessages(chatId, listOf(timestamp)).single().content,
        )
        assertEquals(
            "",
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

package com.ai.assistance.operit.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.model.MessageEntity
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatClassificationV24MigrationAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "chat-classification-v24-migration-test.db"
    private val databaseFile: File
        get() = context.getDatabasePath(databaseName)

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrate20To24_preservesStableDataAndBackfillsDerivedState() = runBlocking {
        createV24Fixture()
        replaceChatsTableWithExactV20Shape()

        val database =
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(AppDatabase.MIGRATION_20_24, AppDatabase.MIGRATION_24_25)
                .allowMainThreadQueries()
                .build()
        val sqlite = database.openHelper.writableDatabase

        assertEquals(25, sqlite.version)
        assertEquals(3, database.chatDao().getTotalChatCount())
        assertEquals(3, database.messageDao().getTotalMessageCount())

        val first = requireNotNull(database.chatDao().getChatById("chat-1"))
        assertEquals("Work", first.group)
        assertEquals(7L, first.displayOrder)
        assertEquals(true, first.pinned)
        assertFalse(first.isFavorite)
        assertEquals(300L, first.lastMessageAt)

        val empty = requireNotNull(database.chatDao().getChatById("chat-empty"))
        assertNull(empty.lastMessageAt)
        assertFalse(empty.isFavorite)

        val messageFavorite =
            requireNotNull(database.messageDao().getMessageByTimestamp("chat-1", 300L))
        assertEquals(true, messageFavorite.isFavorite)
        assertFalse(first.isFavorite)

        val recentIds = database.chatDao().getRecentChats().first().map { it.id }
        assertEquals(listOf("chat-2", "chat-1", "chat-empty"), recentIds)
        assertFalse(sqlite.query("PRAGMA foreign_key_check").use { it.moveToFirst() })

        database.close()
    }

    @Test
    fun lastMessageCache_advancesMonotonicallyAndCanBeRecalculated() = runBlocking {
        val database =
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
        database.chatDao().insertChat(
            ChatEntity(
                id = "chat",
                title = "Chat",
                createdAt = 10L,
                updatedAt = 99L,
            )
        )
        database.messageDao().insertMessage(
            MessageEntity(
                chatId = "chat",
                sender = "user",
                content = "newer",
                timestamp = 200L,
                orderIndex = 0,
            )
        )
        database.chatDao().advanceLastMessageAt("chat", 200L)
        database.chatDao().advanceLastMessageAt("chat", 100L)
        assertEquals(200L, database.chatDao().getChatById("chat")?.lastMessageAt)

        database.chatDao().updateChatFavorite("chat", true)
        database.chatDao().updateChatFavorite("chat", true)
        val favoritedChat = requireNotNull(database.chatDao().getChatById("chat"))
        assertEquals(true, favoritedChat.isFavorite)
        assertEquals(99L, favoritedChat.updatedAt)
        assertEquals(200L, favoritedChat.lastMessageAt)
        assertEquals(
            false,
            database.messageDao().getMessageByTimestamp("chat", 200L)?.isFavorite,
        )

        database.chatDao().updateChatOrderAndFolder(
            chatId = "chat",
            displayOrder = 42L,
            folderId = null,
        )
        val reorderedChat = requireNotNull(database.chatDao().getChatById("chat"))
        assertEquals(42L, reorderedChat.displayOrder)
        assertEquals(null, reorderedChat.folderId)
        assertEquals(99L, reorderedChat.updatedAt)
        assertEquals(true, reorderedChat.isFavorite)
        assertEquals(200L, reorderedChat.lastMessageAt)

        database.messageDao().deleteMessageByTimestamp("chat", 200L)
        database.chatDao().recalculateLastMessageAt("chat")
        assertNull(database.chatDao().getChatById("chat")?.lastMessageAt)

        database.close()
    }

    @Test
    fun experimentalVersions21To23_areNarrowlyRebuiltWithoutStartupCrash() {
        for (experimentalVersion in 21..23) {
            context.deleteDatabase(databaseName)
            createV24Fixture()
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { it.version = experimentalVersion }

            val database =
                Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                    .addMigrations(AppDatabase.MIGRATION_20_24, AppDatabase.MIGRATION_24_25)
                    .fallbackToDestructiveMigrationFrom(true, 21, 22, 23)
                    .allowMainThreadQueries()
                    .build()
            val sqlite = database.openHelper.writableDatabase

            assertEquals(25, sqlite.version)
            assertEquals(0, sqlite.query("SELECT COUNT(*) FROM chats").use {
                it.moveToFirst()
                it.getInt(0)
            })
            database.close()
        }
    }

    private fun createV24Fixture() = runBlocking {
        val database =
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
        database.chatDao().insertChat(
            ChatEntity(
                id = "chat-1",
                title = "One",
                createdAt = 100L,
                updatedAt = 900L,
                group = "Work",
                displayOrder = 7L,
                pinned = true,
            )
        )
        database.chatDao().insertChat(
            ChatEntity(
                id = "chat-2",
                title = "Two",
                createdAt = 200L,
                updatedAt = 800L,
                displayOrder = 8L,
            )
        )
        database.chatDao().insertChat(
            ChatEntity(
                id = "chat-empty",
                title = "Empty",
                createdAt = 50L,
                updatedAt = 1_000L,
                displayOrder = 9L,
            )
        )
        database.messageDao().insertMessages(
            listOf(
                MessageEntity(
                    chatId = "chat-1",
                    sender = "user",
                    content = "old",
                    timestamp = 100L,
                    orderIndex = 0,
                ),
                MessageEntity(
                    chatId = "chat-1",
                    sender = "ai",
                    content = "latest",
                    timestamp = 300L,
                    orderIndex = 1,
                    isFavorite = true,
                ),
                MessageEntity(
                    chatId = "chat-2",
                    sender = "user",
                    content = "most recent",
                    timestamp = 400L,
                    orderIndex = 0,
                ),
            )
        )
        database.close()
    }

    private fun replaceChatsTableWithExactV20Shape() {
        val sqlite =
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        sqlite.execSQL("PRAGMA foreign_keys = OFF")
        sqlite.execSQL(
            """
            CREATE TABLE chats_v20 (
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                inputTokens INTEGER NOT NULL,
                outputTokens INTEGER NOT NULL,
                currentWindowSize INTEGER NOT NULL,
                `group` TEXT,
                displayOrder INTEGER NOT NULL,
                workspace TEXT,
                workspaceEnv TEXT,
                parentChatId TEXT,
                characterCardName TEXT,
                characterGroupId TEXT,
                locked INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO chats_v20 (
                id, title, createdAt, updatedAt, inputTokens, outputTokens,
                currentWindowSize, `group`, displayOrder, workspace, workspaceEnv,
                parentChatId, characterCardName, characterGroupId, locked, pinned
            )
            SELECT
                id, title, createdAt, updatedAt, inputTokens, outputTokens,
                currentWindowSize, `group`, displayOrder, workspace, workspaceEnv,
                parentChatId, characterCardName, characterGroupId, locked, pinned
            FROM chats
            """.trimIndent()
        )
        sqlite.execSQL("DROP TABLE chats")
        sqlite.execSQL("ALTER TABLE chats_v20 RENAME TO chats")
        sqlite.version = 20
        sqlite.close()
    }
}

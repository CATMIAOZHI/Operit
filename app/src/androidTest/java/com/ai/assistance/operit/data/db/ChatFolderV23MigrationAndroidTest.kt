package com.ai.assistance.operit.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.assistance.operit.data.model.ChatEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFolderV23MigrationAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "chat-folder-v23-migration-test.db"
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
    fun migrate22To23_preservesChatsAndRepairsFolderIntegrity() {
        createCurrentDatabaseWithChats()
        replaceFolderTablesWithDirtyV22Fixture()

        val database =
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addMigrations(MIGRATION_22_23)
                .addCallback(CHAT_FOLDER_INTEGRITY_CALLBACK)
                .allowMainThreadQueries()
                .build()
        val sqlite = database.openHelper.writableDatabase

        assertEquals(
            3,
            sqlite.query("SELECT COUNT(*) FROM chat_placements WHERE scope = 'ALL'").use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertTrue(
            sqlite.query(
                "SELECT folderId FROM chat_placements WHERE chatId = 'chat-2' AND scope = 'ALL'"
            ).use {
                it.moveToFirst()
                it.isNull(0)
            }
        )
        assertTrue(
            sqlite.query(
                "SELECT parentFolderId, parentKey FROM chat_folders WHERE id = 'cross-child'"
            ).use {
                it.moveToFirst()
                it.isNull(0) && it.getString(1) == "root:"
            }
        )
        assertEquals(
            2,
            sqlite.query(
                "SELECT COUNT(DISTINCT name) FROM chat_folders WHERE scope = 'ALL' AND parentFolderId IS NULL AND name LIKE 'Work%'"
            ).use {
                it.moveToFirst()
                it.getInt(0)
            },
        )
        assertFalse(sqlite.query("PRAGMA foreign_key_check").use { it.moveToFirst() })

        var rejectedCrossScopePlacement = false
        try {
            sqlite.execSQL(
                "UPDATE chat_placements SET folderId = 'favorite-parent' WHERE chatId = 'chat-3' AND scope = 'ALL'"
            )
        } catch (_: SQLiteConstraintException) {
            rejectedCrossScopePlacement = true
        }
        assertTrue(rejectedCrossScopePlacement)

        sqlite.execSQL(
            """
            INSERT INTO chat_folders
                (id, scope, name, parentFolderId, parentKey, displayOrder, pinned)
            VALUES ('trigger-check', 'ALL', 'Trigger Check', NULL, 'stale', 100, 0)
            """.trimIndent()
        )
        assertEquals(
            "root:",
            sqlite.query("SELECT parentKey FROM chat_folders WHERE id = 'trigger-check'").use {
                it.moveToFirst()
                it.getString(0)
            },
        )

        database.close()
    }

    private fun createCurrentDatabaseWithChats() {
        val database =
            Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
                .addCallback(CHAT_FOLDER_INTEGRITY_CALLBACK)
                .allowMainThreadQueries()
                .build()
        database.openHelper.writableDatabase
        runBlocking {
            database.chatDao().insertChat(ChatEntity(id = "chat-1", title = "One", displayOrder = 1))
            database.chatDao().insertChat(ChatEntity(id = "chat-2", title = "Two", displayOrder = 2))
            database.chatDao().insertChat(ChatEntity(id = "chat-3", title = "Three", displayOrder = 3))
        }
        database.close()
    }

    private fun replaceFolderTablesWithDirtyV22Fixture() {
        val sqlite =
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        sqlite.execSQL("PRAGMA foreign_keys = OFF")
        sqlite.execSQL("DROP TABLE chat_placements")
        sqlite.execSQL("DROP TABLE chat_folders")
        sqlite.execSQL(
            """
            CREATE TABLE chat_folders (
                id TEXT NOT NULL,
                scope TEXT NOT NULL,
                name TEXT NOT NULL,
                parentFolderId TEXT,
                parentKey TEXT NOT NULL DEFAULT '',
                displayOrder INTEGER NOT NULL,
                pinned INTEGER NOT NULL,
                PRIMARY KEY(id),
                FOREIGN KEY(parentFolderId) REFERENCES chat_folders(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            "CREATE UNIQUE INDEX index_chat_folders_scope_parentKey_name ON chat_folders(scope, parentKey, name)"
        )
        sqlite.execSQL(
            """
            CREATE TABLE chat_placements (
                chatId TEXT NOT NULL,
                scope TEXT NOT NULL,
                folderId TEXT,
                displayOrder INTEGER NOT NULL,
                PRIMARY KEY(chatId, scope),
                FOREIGN KEY(chatId) REFERENCES chats(id) ON DELETE CASCADE,
                FOREIGN KEY(folderId) REFERENCES chat_folders(id) ON DELETE SET NULL
            )
            """.trimIndent()
        )

        insertV22Folder(sqlite, "all-root", "ALL", "Work", null, "", 5)
        insertV22Folder(sqlite, "duplicate-root", "ALL", "Work", null, "deleted-parent", 5)
        insertV22Folder(sqlite, "favorite-parent", "FAVORITE", "Favorite", null, "", 0)
        insertV22Folder(
            sqlite,
            "cross-child",
            "ALL",
            "Cross",
            "favorite-parent",
            "favorite-parent",
            0,
        )
        insertV22Folder(sqlite, "cycle-a", "ALL", "Cycle A", "cycle-b", "cycle-b", 1)
        insertV22Folder(sqlite, "cycle-b", "ALL", "Cycle B", "cycle-a", "cycle-a", 1)

        sqlite.execSQL(
            "INSERT INTO chat_placements(chatId, scope, folderId, displayOrder) VALUES ('chat-2', 'ALL', 'favorite-parent', 0)"
        )
        sqlite.execSQL(
            "INSERT INTO chat_placements(chatId, scope, folderId, displayOrder) VALUES ('chat-3', 'ALL', 'all-root', 0)"
        )
        sqlite.version = 22
        sqlite.close()
    }

    private fun insertV22Folder(
        db: SQLiteDatabase,
        id: String,
        scope: String,
        name: String,
        parentId: String?,
        parentKey: String,
        order: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO chat_folders
                (id, scope, name, parentFolderId, parentKey, displayOrder, pinned)
            VALUES (?, ?, ?, ?, ?, ?, 0)
            """.trimIndent(),
            arrayOf<Any?>(id, scope, name, parentId, parentKey, order),
        )
    }
}

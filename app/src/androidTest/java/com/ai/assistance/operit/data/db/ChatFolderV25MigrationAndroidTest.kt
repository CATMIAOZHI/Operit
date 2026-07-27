package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFolderV25MigrationAndroidTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )

    @After
    fun cleanUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationBucketsByTypedBindingAndPreservesMessageForeignKeys() {
        helper.createDatabase(TEST_DATABASE, 24).use { db ->
            insertChat(db, "first", " Shared ", null, null, 4, 100)
            insertChat(db, "same-raw-other-binding", " Shared ", "card", null, 1, 200)
            insertChat(db, "same-name-other-raw", "Shared", null, null, 3, 150)
            insertChat(db, "blank", "   ", null, null, 0, 50)
            insertMessage(db, "first", 10)
            insertVariant(db, "first", 10)
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            25,
            true,
            AppDatabase.MIGRATION_24_25,
        ).use { db ->
            assertEquals(3, queryLong(db, "SELECT COUNT(*) FROM chat_folders"))
            assertNull(queryNullableString(db, "SELECT folderId FROM chats WHERE id = 'blank'"))

            val firstFolder = queryString(db, "SELECT folderId FROM chats WHERE id = 'first'")
            val otherBindingFolder =
                queryString(
                    db,
                    "SELECT folderId FROM chats WHERE id = 'same-raw-other-binding'",
                )
            val otherRawFolder =
                queryString(db, "SELECT folderId FROM chats WHERE id = 'same-name-other-raw'")
            assertNotEquals(firstFolder, otherBindingFolder)
            assertNotEquals(firstFolder, otherRawFolder)
            assertEquals(
                listOf(0L, 1L, 2L),
                queryLongs(db, "SELECT displayOrder FROM chat_folders ORDER BY displayOrder"),
            )
            assertEquals(
                listOf("Shared", "Shared", "Shared"),
                queryStrings(db, "SELECT name FROM chat_folders ORDER BY displayOrder"),
            )
            assertEquals(0L, queryLong(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))

            db.execSQL("DELETE FROM chats WHERE id = 'first'")
            assertEquals(0L, queryLong(db, "SELECT COUNT(*) FROM messages WHERE chatId = 'first'"))
            assertEquals(
                0L,
                queryLong(db, "SELECT COUNT(*) FROM message_variants WHERE chatId = 'first'"),
            )
        }
    }

    private fun insertChat(
        db: SupportSQLiteDatabase,
        id: String,
        group: String?,
        characterCardName: String?,
        characterGroupId: String?,
        displayOrder: Long,
        createdAt: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO chats (
                id, title, createdAt, updatedAt, inputTokens, outputTokens,
                currentWindowSize, `group`, displayOrder, workspace, workspaceEnv,
                parentChatId, characterCardName, characterGroupId, locked, pinned,
                isFavorite, lastMessageAt
            ) VALUES (?, ?, ?, ?, 0, 0, 0, ?, ?, NULL, NULL, NULL, ?, ?, 0, 0, 0, NULL)
            """.trimIndent(),
            arrayOf(
                id,
                id,
                createdAt,
                createdAt,
                group,
                displayOrder,
                characterCardName,
                characterGroupId,
            ),
        )
    }

    private fun insertMessage(db: SupportSQLiteDatabase, chatId: String, timestamp: Long) {
        db.execSQL(
            """
            INSERT INTO messages (
                chatId, sender, content, timestamp, orderIndex, roleName,
                selectedVariantIndex, provider, modelName, inputTokens, outputTokens,
                cachedInputTokens, sentAt, outputDurationMs, waitDurationMs, completedAt,
                displayMode, isFavorite
            ) VALUES (?, 'ai', 'message', ?, 0, '', 0, '', '', 0, 0, 0, 0, 0, 0, 0, 'NORMAL', 0)
            """.trimIndent(),
            arrayOf(chatId, timestamp),
        )
    }

    private fun insertVariant(db: SupportSQLiteDatabase, chatId: String, timestamp: Long) {
        db.execSQL(
            """
            INSERT INTO message_variants (
                chatId, messageTimestamp, variantIndex, content, roleName, provider,
                modelName, inputTokens, outputTokens, cachedInputTokens, sentAt,
                outputDurationMs, waitDurationMs, completedAt
            ) VALUES (?, ?, 1, 'variant', '', '', '', 0, 0, 0, 0, 0, 0, 0)
            """.trimIndent(),
            arrayOf(chatId, timestamp),
        )
    }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private fun queryString(db: SupportSQLiteDatabase, sql: String): String =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun queryNullableString(db: SupportSQLiteDatabase, sql: String): String? =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun queryLongs(db: SupportSQLiteDatabase, sql: String): List<Long> =
        db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }

    private fun queryStrings(db: SupportSQLiteDatabase, sql: String): List<String> =
        db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private companion object {
        const val TEST_DATABASE = "chat-folder-v25-migration"
    }
}

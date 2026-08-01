package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubagentV26MigrationAndroidTest {
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
    fun migrationClassifiesExistingChatsAndEnforcesRunChildRelationship() {
        helper.createDatabase(TEST_DATABASE, 25).use { db ->
            insertChat(db, id = "parent", parentChatId = null)
            insertChat(db, id = "branch", parentChatId = "parent")
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            26,
            true,
            AppDatabase.MIGRATION_25_26,
        ).use { db ->
            assertEquals(
                "NORMAL",
                queryString(db, "SELECT chatKind FROM chats WHERE id = 'parent'"),
            )
            assertEquals(
                "BRANCH",
                queryString(db, "SELECT chatKind FROM chats WHERE id = 'branch'"),
            )

            insertChat(
                db = db,
                id = "child",
                parentChatId = "parent",
                chatKind = "SUBAGENT",
            )
            db.execSQL(
                """
                INSERT INTO subagent_runs (
                    id, parentChatId, childChatId, parentToolCallId, agentProfileId,
                    title, status, createdAt, startedAt, completedAt, error,
                    agentConfigSnapshot, modelConfigIdSnapshot, modelIndexSnapshot
                ) VALUES (
                    'task', 'parent', 'child', 'call', 'explore',
                    'inspect', 'RUNNING', 100, 101, NULL, NULL,
                    '{"id":"explore"}', 'model-config', 0
                )
                """.trimIndent()
            )

            assertEquals(
                listOf("branch"),
                queryStrings(
                    db,
                    "SELECT id FROM chats WHERE parentChatId = 'parent' AND chatKind = 'BRANCH'",
                ),
            )
            assertEquals(
                listOf("branch", "parent"),
                queryStrings(
                    db,
                    "SELECT id FROM chats WHERE chatKind != 'SUBAGENT' ORDER BY id",
                ),
            )
            assertEquals(0L, queryLong(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))

            try {
                db.execSQL("DELETE FROM chats WHERE id = 'parent'")
                fail("Parent deletion must fail while its Subagent run exists")
            } catch (_: Exception) {
                // NO_ACTION is intentional; repository deletion removes child rows first.
            }

            db.execSQL("DELETE FROM chats WHERE id = 'child'")
            assertEquals(
                0L,
                queryLong(db, "SELECT COUNT(*) FROM subagent_runs WHERE id = 'task'"),
            )
            assertEquals(0L, queryLong(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        }
    }

    private fun insertChat(
        db: SupportSQLiteDatabase,
        id: String,
        parentChatId: String?,
        chatKind: String? = null,
    ) {
        val chatKindColumn = if (chatKind == null) "" else ", chatKind"
        val chatKindValue = if (chatKind == null) "" else ", ?"
        val args =
            buildList<Any?> {
                add(id)
                add(id)
                add(parentChatId)
                if (chatKind != null) add(chatKind)
            }.toTypedArray()
        db.execSQL(
            """
            INSERT INTO chats (
                id, title, createdAt, updatedAt, inputTokens, outputTokens,
                currentWindowSize, `group`, folderId, displayOrder, workspace, workspaceEnv,
                parentChatId, characterCardName, characterGroupId, locked, pinned,
                isFavorite, lastMessageAt$chatKindColumn
            ) VALUES (
                ?, ?, 100, 100, 0, 0,
                0, NULL, NULL, 0, NULL, NULL,
                ?, NULL, NULL, 0, 0,
                0, NULL$chatKindValue
            )
            """.trimIndent(),
            args,
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

    private fun queryStrings(db: SupportSQLiteDatabase, sql: String): List<String> =
        db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private companion object {
        const val TEST_DATABASE = "subagent-v26-migration"
    }
}

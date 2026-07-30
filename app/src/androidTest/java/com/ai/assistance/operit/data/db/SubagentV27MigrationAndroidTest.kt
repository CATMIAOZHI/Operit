package com.ai.assistance.operit.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubagentV27MigrationAndroidTest {
    @After
    fun cleanUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationAddsDurableCountAndBackfillsCompletedToolResults() {
        openDatabase(version = 26, createVersion26Schema = true).use { helper ->
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO chats (id) VALUES ('parent')")
            db.execSQL("INSERT INTO chats (id) VALUES ('child')")
            db.execSQL(
                """
                INSERT INTO messages (chatId, content) VALUES (
                    'child',
                    '<tool_result_a final="true"><content>' ||
                    'source text contains  final="true" but it is only payload' ||
                    '</content></tool_result_a>' ||
                    '<tool_result_b final="true"></tool_result_b>'
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO subagent_runs (
                    id, parentChatId, childChatId, parentToolCallId, agentProfileId,
                    title, status, createdAt, startedAt, completedAt, error,
                    agentConfigSnapshot, modelConfigIdSnapshot, modelIndexSnapshot
                ) VALUES (
                    'task', 'parent', 'child', 'call', 'explore',
                    'inspect', 'COMPLETED', 100, 101, 102, NULL,
                    '{"id":"explore"}', 'model-config', 0
                )
                """.trimIndent()
            )
        }

        openDatabase(version = 27, createVersion26Schema = false).use { helper ->
            val db = helper.writableDatabase
            assertEquals(
                2L,
                queryLong(
                    db,
                    "SELECT toolInvocationCount FROM subagent_runs WHERE id = 'task'",
                ),
            )
            assertEquals(0L, queryLong(db, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
        }
    }

    private fun openDatabase(
        version: Int,
        createVersion26Schema: Boolean,
    ): SupportSQLiteOpenHelper {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback =
            object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }

                override fun onCreate(db: SupportSQLiteDatabase) {
                    check(createVersion26Schema)
                    createVersion26Schema(db)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    assertEquals(26, oldVersion)
                    assertEquals(27, newVersion)
                    AppDatabase.MIGRATION_26_27.migrate(db)
                }
            }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(callback)
                .build(),
        )
    }

    private fun createVersion26Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE chats (id TEXT NOT NULL PRIMARY KEY)")
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                chatId TEXT NOT NULL,
                content TEXT NOT NULL,
                FOREIGN KEY(chatId) REFERENCES chats(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE subagent_runs (
                id TEXT NOT NULL PRIMARY KEY,
                parentChatId TEXT NOT NULL,
                childChatId TEXT NOT NULL,
                parentToolCallId TEXT NOT NULL,
                agentProfileId TEXT NOT NULL,
                title TEXT NOT NULL,
                status TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                startedAt INTEGER,
                completedAt INTEGER,
                error TEXT,
                agentConfigSnapshot TEXT NOT NULL,
                modelConfigIdSnapshot TEXT,
                modelIndexSnapshot INTEGER NOT NULL,
                FOREIGN KEY(parentChatId) REFERENCES chats(id) ON DELETE CASCADE,
                FOREIGN KEY(childChatId) REFERENCES chats(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private companion object {
        const val TEST_DATABASE = "subagent-v27-migration"
    }
}

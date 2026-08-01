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
class SubagentV28MigrationAndroidTest {
    @After
    fun cleanUp() {
        ApplicationProvider.getApplicationContext<Context>().deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationAddsNullableArchiveTimestampWithoutArchivingExistingRuns() {
        openDatabase(version = 27, createVersion27Schema = true).use { helper ->
            helper.writableDatabase.execSQL(
                "INSERT INTO subagent_runs (id, status) VALUES ('task', 'COMPLETED')"
            )
        }

        openDatabase(version = 28, createVersion27Schema = false).use { helper ->
            val db = helper.writableDatabase
            assertEquals(
                1L,
                queryLong(
                    db,
                    "SELECT COUNT(*) FROM pragma_table_info('subagent_runs') " +
                        "WHERE name = 'archivedAt'",
                ),
            )
            assertEquals(
                1L,
                queryLong(
                    db,
                    "SELECT COUNT(*) FROM subagent_runs " +
                        "WHERE id = 'task' AND archivedAt IS NULL",
                ),
            )
            db.execSQL("UPDATE subagent_runs SET archivedAt = 123 WHERE id = 'task'")
            assertEquals(
                123L,
                queryLong(db, "SELECT archivedAt FROM subagent_runs WHERE id = 'task'"),
            )
        }
    }

    private fun openDatabase(
        version: Int,
        createVersion27Schema: Boolean,
    ): SupportSQLiteOpenHelper {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val callback =
            object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    check(createVersion27Schema)
                    db.execSQL(
                        """
                        CREATE TABLE subagent_runs (
                            id TEXT NOT NULL PRIMARY KEY,
                            status TEXT NOT NULL
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    assertEquals(27, oldVersion)
                    assertEquals(28, newVersion)
                    AppDatabase.MIGRATION_27_28.migrate(db)
                }
            }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(TEST_DATABASE)
                .callback(callback)
                .build(),
        )
    }

    private fun queryLong(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private companion object {
        const val TEST_DATABASE = "subagent-v28-migration"
    }
}

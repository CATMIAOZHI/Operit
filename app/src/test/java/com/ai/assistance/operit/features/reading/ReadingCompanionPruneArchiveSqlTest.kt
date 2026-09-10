package com.ai.assistance.operit.features.reading

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `pruneAutoCommentRuns` 归档 SQL 回归测试（纯 JVM，sqlite-jdbc）。
 *
 * 回归背景（真机故障）：`auto_comment_runs.chapter_index` 可空（「无下一章」收尾或未解析到
 * 章节即中断的 run），而 `reading_task_attempts.chapter_index` 为 NOT NULL。旧 SQL 无过滤
 * 直接拷贝，遇到 task 关联但章节为 NULL 的 run 即抛 NOT NULL 约束异常，整个剪枝事务回滚，
 * 脏 run 永不删除且每次状态检查必失败。本测试执行与生产同一份 SQL 来源
 * [pruneAutoCommentArchiveSql]，验证：空章节 run 被跳过、有效 run 正常归档、同等 selection
 * 的 DELETE 仍会清除空章节脏 run（自愈）。
 */
class ReadingCompanionPruneArchiveSqlTest {

    /** 与生产剪枝一致：剪掉非 generating 且不在最近 50 条内的 run。 */
    private fun pruneSelection() =
        "status != ? AND id NOT IN (" +
            "SELECT id FROM auto_comment_runs ORDER BY started_at DESC, id DESC LIMIT ?" +
            ")"

    /** 生产实现里自动生成的脏数据形态：有任务关联但章节为 NULL（无下一章收尾/未解析即中断）。 */
    private fun openDatabase(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE auto_comment_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        book_id TEXT,
                        chapter_index INTEGER,
                        chapter_title TEXT,
                        status TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        subagent_run_id TEXT,
                        child_chat_id TEXT,
                        actual_input_tokens INTEGER,
                        actual_output_tokens INTEGER,
                        started_at INTEGER NOT NULL,
                        finished_at INTEGER,
                        task_id TEXT
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE reading_task_attempts (
                        id INTEGER PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        chapter_index INTEGER NOT NULL,
                        chapter_title TEXT,
                        status TEXT,
                        stage TEXT,
                        subagent_run_id TEXT,
                        child_chat_id TEXT,
                        actual_input_tokens INTEGER,
                        actual_output_tokens INTEGER,
                        started_at INTEGER,
                        finished_at INTEGER
                    )
                    """.trimIndent()
                )
            }
        }

    @Test
    fun `null chapter task run is skipped and cleaned, valid runs are archived`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                // 55 条非 generating run：剪枝会选中最早 5 条（id 1..5）。
                for (id in 1..55) {
                    statement.execute(
                        "INSERT INTO auto_comment_runs " +
                            "(id, chapter_index, chapter_title, status, stage, started_at, task_id)" +
                            " VALUES ($id, $id, 'chapter-$id', 'interrupted', 'running', $id, NULL)"
                    )
                }
                // id=1：task 关联但章节为 NULL（旧 SQL 在此崩溃）；id=2：task 关联且章节有效。
                statement.execute(
                    "UPDATE auto_comment_runs SET task_id = 'task-1', chapter_index = NULL WHERE id = 1"
                )
                statement.execute(
                    "UPDATE auto_comment_runs SET task_id = 'task-2' WHERE id = 2"
                )
            }

            val selection = pruneSelection()
            val selectionArgs = arrayOf("generating", "50")
            connection.prepareStatement(pruneAutoCommentArchiveSql(selection)).use { prepared ->
                prepared.setString(1, selectionArgs[0])
                prepared.setString(2, selectionArgs[1])
                prepared.executeUpdate()
            }

            // 归档只包含有效章节的 task run；空章节 run 被跳过（修复前此处抛 NOT NULL 约束）。
            val archived =
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT id, task_id, chapter_index FROM reading_task_attempts"
                    ).use { rows ->
                        buildList {
                            while (rows.next()) {
                                add("${rows.getLong(1)}:${rows.getString(2)}:${rows.getInt(3)}")
                            }
                        }
                    }
                }
            assertEquals(listOf("2:task-2:2"), archived)

            // 剪枝 DELETE 用同一 selection（不含 chapter_index 条件），空章节脏 run 仍被清除。
            connection.prepareStatement(
                "DELETE FROM auto_comment_runs WHERE $selection"
            ).use { prepared ->
                prepared.setString(1, selectionArgs[0])
                prepared.setString(2, selectionArgs[1])
                prepared.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM auto_comment_runs").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(50, rows.getInt(1))
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM auto_comment_runs WHERE id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `archive without chapter filter still reproduces the not null violation`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                for (id in 1..55) {
                    statement.execute(
                        "INSERT INTO auto_comment_runs " +
                            "(id, chapter_index, chapter_title, status, stage, started_at, task_id)" +
                            " VALUES ($id, $id, 'chapter-$id', 'interrupted', 'running', $id, NULL)"
                    )
                }
                statement.execute(
                    "UPDATE auto_comment_runs SET task_id = 'task-1', chapter_index = NULL WHERE id = 1"
                )
            }

            // 旧 SQL 形态（无 chapter_index IS NOT NULL）：应当复现真机 NOT NULL 约束失败。
            val selection = pruneSelection()
            val legacySql =
                """
                INSERT OR REPLACE INTO reading_task_attempts
                    (id, task_id, chapter_index, chapter_title, status, stage, subagent_run_id,
                     child_chat_id, actual_input_tokens, actual_output_tokens, started_at, finished_at)
                    SELECT id, task_id, chapter_index, chapter_title, status, stage, subagent_run_id,
                           child_chat_id, actual_input_tokens, actual_output_tokens, started_at, finished_at
                    FROM auto_comment_runs
                    WHERE task_id IS NOT NULL AND ($selection)
                """.trimIndent()
            val thrown =
                try {
                    connection.prepareStatement(legacySql).use { prepared ->
                        prepared.setString(1, "generating")
                        prepared.setString(2, "50")
                        prepared.executeUpdate()
                    }
                    null
                } catch (expected: SQLException) {
                    expected
                }
            assertTrue(
                "旧 SQL 应复现 NOT NULL 约束失败，实际未抛出",
                thrown != null && thrown.message!!.contains("chapter_index"),
            )
        }
    }
}

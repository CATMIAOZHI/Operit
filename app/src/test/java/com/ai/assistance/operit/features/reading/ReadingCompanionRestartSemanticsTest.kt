package com.ai.assistance.operit.features.reading

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用生产的过期判定 SQL 验证启动时刻作为截止时间：旧进程的普通 run 和 summary run
 * 都会被中断。运行中的心跳保活场景由 [ReadingCompanionHeartbeatTest] 覆盖。
 */
class ReadingCompanionRestartSemanticsTest {

    private fun openDatabase(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE auto_comment_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        trigger_source TEXT NOT NULL,
                        execution_mode TEXT NOT NULL,
                        status TEXT NOT NULL,
                        stage TEXT NOT NULL DEFAULT 'starting',
                        stage_updated_at INTEGER NOT NULL DEFAULT 0,
                        run_heartbeat_at INTEGER NOT NULL DEFAULT 0,
                        comment_count INTEGER NOT NULL DEFAULT 0,
                        started_at INTEGER NOT NULL,
                        restarted_from_run_id INTEGER
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE auto_comment_generation_claims (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        book_id TEXT NOT NULL,
                        chapter_index INTEGER NOT NULL,
                        run_id INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

    @Test
    fun `restart sweep interrupts the old run and releases its claim`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO auto_comment_runs " +
                        "(trigger_source, execution_mode, status, comment_count, started_at) " +
                        "VALUES ('background', 'subagent', 'generating', 0, 1000)"
                )
                statement.execute(
                    "INSERT INTO auto_comment_generation_claims " +
                        "(book_id, chapter_index, run_id, updated_at) " +
                        "VALUES ('b1', 3, 1, 1000)"
                )
            }
            // 模拟进程重启：启动清扫 staleBefore = 启动时刻 2000（无存活协程，claim 不可能比
            // 启动时刻新，生产 reconcileAfterProcessStart 同语义）。
            val restartAt = 2000L
            val swept =
                connection.prepareStatement(
                    "UPDATE auto_comment_runs SET status = 'interrupted' " +
                        "WHERE $READING_STALE_RUN_WHERE_SQL"
                ).use { prepared ->
                    prepared.setString(1, "generating")
                    prepared.setLong(2, restartAt)
                    prepared.setLong(3, restartAt)
                    prepared.executeUpdate()
                }
            assertEquals(1, swept)
            // 释放 claim（生产 markRunInterrupted 同语义：DELETE claims WHERE run_id）。旧
            // transcript（child 聊天）保留不删。
            connection.createStatement().use { statement ->
                statement.execute("DELETE FROM auto_comment_generation_claims WHERE run_id = 1")
                statement.executeQuery(
                    "SELECT COUNT(*) FROM auto_comment_generation_claims WHERE run_id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(0, rows.getInt(1))
                }
                statement.executeQuery(
                    "SELECT status FROM auto_comment_runs WHERE id = 1"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("interrupted", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun `restart sweep interrupts a summary run even when the old process heartbeated it`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO auto_comment_runs " +
                        "(trigger_source, execution_mode, status, stage_updated_at, " +
                        "run_heartbeat_at, started_at) " +
                        "VALUES ('manual_summary', 'subagent', 'generating', 1000, 1900, 1000)"
                )
            }
            // 新进程启动时刻晚于旧进程最后心跳；启动清扫仍必须回收遗留摘要。
            val restartAt = 2000L
            val swept =
                connection.prepareStatement(
                    "UPDATE auto_comment_runs SET status = 'interrupted' " +
                        "WHERE $READING_STALE_RUN_WHERE_SQL"
                ).use { prepared ->
                    prepared.setString(1, "generating")
                    prepared.setLong(2, restartAt)
                    prepared.setLong(3, restartAt)
                    prepared.executeUpdate()
                }
            assertEquals(1, swept)
        }
    }

}

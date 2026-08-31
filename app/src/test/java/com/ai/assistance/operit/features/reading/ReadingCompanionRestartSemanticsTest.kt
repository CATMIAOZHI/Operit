package com.ai.assistance.operit.features.reading

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进程重启恢复语义测试（纯 JVM，sqlite-jdbc + 共享 SQL 常量/静态契约）。
 *
 * 生产路径：
 * - 启动时 [ReadingCompanionStore.reconcileAfterProcessStart] 把早于启动时刻的 generating
 *   run 标 interrupted 并释放 claim（清扫 SQL 与 [ReadingCompanionHeartbeatTest] 同一来源）；
 * - 下次 Worker 用 [ReadingCompanionStore.startAutoCommentRun] 创建新 run 并写
 *   restarted_from_run_id=旧 id（谱系，只作记录）；
 * - 新 child 由 SubagentCoordinator 每次全新创建（taskId=null），绝不续写旧对话。
 */
class ReadingCompanionRestartSemanticsTest {

    private val coordinatorSource: String =
        File(
            "src/main/java/com/ai/assistance/operit/features/reading/" +
                "ReadingCompanionSubagentCoordinator.kt",
        ).readText()

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
    fun `new run records restart lineage and keeps execution mode subagent`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO auto_comment_runs " +
                        "(trigger_source, execution_mode, status, comment_count, started_at) " +
                        "VALUES ('background', 'subagent', 'interrupted', 0, 1000)"
                )
            }
            // 与生产 startAutoCommentRun 落库契约一致（阶段 3：execution_mode=subagent；
            // restarted_from_run_id=旧 id 只作谱系）。
            val oldRunId = 1L
            connection.prepareStatement(
                "INSERT INTO auto_comment_runs " +
                    "(trigger_source, execution_mode, status, stage, stage_updated_at, run_heartbeat_at, " +
                    "comment_count, started_at, restarted_from_run_id) " +
                    "VALUES ('background', 'subagent', 'generating', 'starting', 2000, 2000, 0, 2000, ?)"
            ).use { prepared ->
                prepared.setLong(1, oldRunId)
                prepared.executeUpdate()
            }
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, execution_mode, status, restarted_from_run_id " +
                        "FROM auto_comment_runs WHERE id = 2"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals("subagent", rows.getString(2))
                    assertEquals("generating", rows.getString(3))
                    assertEquals(oldRunId, rows.getLong(4))
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

    @Test
    fun `restart semantics never resume the old subagent task id`() {
        // 新 run 的 subagent child 由 SubagentCoordinator 每次全新创建：阅读协调器恒传
        // taskId=null，restarted_from_run_id 只写 reading 侧 run 谱系，绝不作 runTask 入参。
        assertTrue(coordinatorSource.contains("taskId = null"))
        assertTrue(
            "阅读协调器不得把旧 subagent taskId 传给 runTask",
            !coordinatorSource.contains("subagentCoordinator.runTask(request, ") &&
                !coordinatorSource.contains("taskId = taskId"),
        )
        assertTrue(
            "谱系只落在 reading 侧（restartedFromRunId 不得出现在阅读协调器 runTask 请求里）",
            !coordinatorSource.contains("restartedFromRunId"),
        )
    }
}

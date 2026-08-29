package com.ai.assistance.operit.features.reading

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * claim 心跳语义测试（纯 JVM，sqlite-jdbc）。
 *
 * 生产 [ReadingCompanionStore.heartbeatClaimIfOwned] 用共享常量
 * [READING_CLAIM_OWNER_WHERE_SQL] 做归属判定；stale 清扫
 * [ReadingCompanionStore.interruptStaleAutoCommentRuns] 用共享常量
 * [READING_CLAIMS_NOT_STALE_SUBQUERY_SQL] 排除“claim 仍未过期”的 run。本测试执行**同一份
 * SQL 来源**（常量拼接），验证：
 * - heartbeat 刷新 claims.updated_at（而不是 stage_updated_at）；
 * - 5 分钟 stale 窗口内，heartbeat 刷新后的 run 不被 interruptStale 误杀；
 * - 错误 owner heartbeat 返回 false（affected=0），旧段评归属不变。
 */
class ReadingCompanionHeartbeatTest {

    private fun openDatabase(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").apply {
            createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE auto_comment_runs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        status TEXT NOT NULL,
                        started_at INTEGER NOT NULL
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
    fun `heartbeat refreshes the claim updated_at timestamp`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO auto_comment_generation_claims " +
                        "(book_id, chapter_index, run_id, updated_at) " +
                        "VALUES ('b1', 3, 11, 1000)"
                )
            }
            // 与生产 heartbeatClaimIfOwned 同一来源：共享 WHERE 常量。
            val affected =
                connection.prepareStatement(
                    "UPDATE auto_comment_generation_claims SET updated_at = ? WHERE " +
                        READING_CLAIM_OWNER_WHERE_SQL
                ).use { prepared ->
                    prepared.setLong(1, 1000 + 120_000L)
                    prepared.setString(2, "b1")
                    prepared.setInt(3, 3)
                    prepared.setLong(4, 11L)
                    prepared.executeUpdate()
                }
            assertEquals(1, affected)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT updated_at FROM auto_comment_generation_claims WHERE run_id = 11"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1000 + 120_000L, rows.getLong(1))
                }
            }
        }
    }

    @Test
    fun `wrong owner heartbeat returns false and never touches the real claim`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO auto_comment_generation_claims " +
                        "(book_id, chapter_index, run_id, updated_at) " +
                        "VALUES ('b1', 3, 11, 1000)"
                )
            }
            val affected =
                connection.prepareStatement(
                    "UPDATE auto_comment_generation_claims SET updated_at = ? WHERE " +
                        READING_CLAIM_OWNER_WHERE_SQL
                ).use { prepared ->
                    prepared.setLong(1, 1000 + 60_000L)
                    prepared.setString(2, "b1")
                    prepared.setInt(3, 3)
                    // 错误 owner：另一个 run 尝试刷新。
                    prepared.setLong(4, 99L)
                    prepared.executeUpdate()
                }
            assertEquals("affected != 1 => claim_lost", 0, affected)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT updated_at FROM auto_comment_generation_claims WHERE run_id = 11"
                ).use { rows ->
                    assertTrue(rows.next())
                    // 真 owner 的时间戳原样保留，生成停止且旧段评仍在（提交底线不被触发）。
                    assertEquals(1000L, rows.getLong(1))
                }
            }
        }
    }

    @Test
    fun `stale sweep skips runs whose claim was refreshed inside the window`() {
        openDatabase().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO auto_comment_runs (id, status, started_at) VALUES (1, 'generating', 1000)"
                )
                statement.execute(
                    "INSERT INTO auto_comment_runs (id, status, started_at) VALUES (2, 'generating', 1000)"
                )
                // run 1：创建后从未心跳（updated_at = 创建时刻）。
                statement.execute(
                    "INSERT INTO auto_comment_generation_claims " +
                        "(book_id, chapter_index, run_id, updated_at) " +
                        "VALUES ('b1', 3, 1, 1000)"
                )
                // run 2：4 分钟后心跳刷新。
                statement.execute(
                    "INSERT INTO auto_comment_generation_claims " +
                        "(book_id, chapter_index, run_id, updated_at) " +
                        "VALUES ('b1', 3, 2, 1000 + 240_000)"
                )
            }
            // 清扫发生在 T = 1000 + 6min；staleBefore = T - 5min。
            val staleBefore = 1000L + 60_000L
            val swept =
                connection.prepareStatement(
                    "UPDATE auto_comment_runs SET status = 'interrupted' " +
                        "WHERE status = 'generating' AND started_at < ? " +
                        "AND id NOT IN $READING_CLAIMS_NOT_STALE_SUBQUERY_SQL"
                ).use { prepared ->
                    prepared.setLong(1, staleBefore)
                    prepared.setLong(2, staleBefore)
                    prepared.executeUpdate()
                }
            assertEquals("只有从未心跳的 run 1 被清扫", 1, swept)
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, status FROM auto_comment_runs ORDER BY id"
                ).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                    assertEquals("interrupted", rows.getString(2))
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt(1))
                    assertEquals("generating", rows.getString(2))
                }
            }
        }
    }
}

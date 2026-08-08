package com.ai.assistance.operit.data.stats

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement

/**
 * 记录型 SQLite 驱动（仅测试）：包装 [JdbcSQLiteDriver]，记录每条真实执行的 SQL、
 * 绑定参数与返回行数（[RecordedSql]），用于断言：
 * - 固定查询次数（防 N+1）、绝不调用全表读取（getAllEvents）；
 * - IN 分块大小（`?` 占位符个数与绑定值）；
 * - 生命周期分页的 LIMIT 绑定与每页行数（P2-1 页界）。
 *
 * 时机与复用说明：Room 2.8 连接池会**缓存已 prepare 的语句**（同一事务内相同
 * SQL 复用同一 statement，重新绑定并重新 step），且 close 语句发生在 DAO 调用
 * 返回之后。因此本记录器按**执行周期**记录：绑定开始新周期，终止 step（返回
 * false，同步于查询执行）完成当前周期并落记录——行数同时确定，测试在 DAO 调用
 * 返回后读取 [executed] 即完整、确定，不依赖异步 close。
 */
class RecordingSQLiteDriver : SQLiteDriver {

    private val delegate = JdbcSQLiteDriver()

    /** 已执行完成的语句周期记录（按执行顺序）。 */
    val executed = mutableListOf<RecordedSql>()

    fun clear() = executed.clear()

    override fun open(fileName: String): SQLiteConnection =
        RecordingConnection(delegate.open(fileName), executed)
}

/** 单次语句执行周期：SQL 文本、绑定参数（index -> 值）、返回行数。 */
class RecordedSql(
    val sql: String,
    val binds: Map<Int, String>,
    val rows: Int,
) {
    /** SQL 中 `?` 占位符个数（Room 动态生成的 IN 列表直接反映参数个数）。 */
    val questionMarkCount: Int
        get() = sql.count { it == '?' }

    /** 绑定值（按 index 升序）的文本表示，如 `1=1000;2=...`。 */
    fun bindText(): String = binds.toSortedMap().entries.joinToString(";") { (index, value) -> "$index=$value" }

    override fun toString(): String = "$sql | ${bindText()} | rows=$rows"
}

private class RecordingConnection(
    private val delegate: SQLiteConnection,
    private val sink: MutableList<RecordedSql>,
) : SQLiteConnection by delegate {
    override fun prepare(sql: String): SQLiteStatement =
        RecordingStatement(delegate.prepare(sql), sql, sink)
}

private class RecordingStatement(
    private val delegate: SQLiteStatement,
    private val sql: String,
    private val sink: MutableList<RecordedSql>,
) : SQLiteStatement by delegate {

    private val binds = ArrayList<Pair<Int, String>>()
    private var rows = 0
    private var cycleComplete = true

    private fun startCycleIfNeeded() {
        if (cycleComplete) {
            cycleComplete = false
            binds.clear()
            rows = 0
        }
    }

    override fun bindBlob(index: Int, value: ByteArray) {
        startCycleIfNeeded()
        binds += index to "<blob>"
        delegate.bindBlob(index, value)
    }

    override fun bindDouble(index: Int, value: Double) {
        startCycleIfNeeded()
        binds += index to value.toString()
        delegate.bindDouble(index, value)
    }

    override fun bindLong(index: Int, value: Long) {
        startCycleIfNeeded()
        binds += index to value.toString()
        delegate.bindLong(index, value)
    }

    override fun bindText(index: Int, value: String) {
        startCycleIfNeeded()
        binds += index to value
        delegate.bindText(index, value)
    }

    override fun bindNull(index: Int) {
        startCycleIfNeeded()
        binds += index to "NULL"
        delegate.bindNull(index)
    }

    override fun step(): Boolean {
        // 无绑定参数的语句（如 SELECT * FROM token_stat_identities）也要开始周期
        startCycleIfNeeded()
        val advanced = delegate.step()
        if (advanced) {
            rows += 1
        } else if (!cycleComplete) {
            // 终止 step（同步于查询执行）：行数已确定，完成当前执行周期
            cycleComplete = true
            sink += RecordedSql(sql, binds.toMap(), rows)
        }
        return advanced
    }

    override fun close() {
        // 连接池异步 close：若周期未完成（异常路径），补一条占位记录
        if (!cycleComplete) {
            cycleComplete = true
            sink += RecordedSql(sql, binds.toMap(), rows)
        }
        delegate.close()
    }
}

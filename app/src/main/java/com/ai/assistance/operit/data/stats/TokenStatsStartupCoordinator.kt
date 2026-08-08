package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * 启动统计 readiness 门控（P1 关键链路）。测试可注入 no-op 或门控实现（见
 * [TokenUsageStatisticsViewModel] 的构造参数）。
 */
fun interface TokenStatsReadiness {
    /**
     * 等待初始统计就绪（旧 baseline 导入 + spool 初始重放完成）。返回 true = 就绪；
     * false = 超时或失败（调用方可稍后重试，就绪状态不缓存）。
     */
    suspend fun awaitReady(timeoutMs: Long): Boolean
}

/**
 * 启动统计 single-flight 初始化（P1 关键链路）：依次
 * [TokenBaselineImportRunner.ensureMigrated] → [TokenBaselineImportRunner.consumePendingRestore]
 * → [TokenStatSpool.awaitInitialDrain]，保证统计页首次查询看到的是重放完成后的数据，
 * 绝不无限展示 pre-replay 快照。
 *
 * - **single-flight**：并发调用 join 同一轮初始化（[inFlight] 引用只在锁内读写，
 *   初始化执行本身在锁外——绝不在持有任何锁时执行 DAO/DataStore/spool 工作）。
 * - **失败不永久缓存**：一轮失败/超时后 [inFlight] 不保留，下一次调用重新执行；
 *   spool drain 自身另有退避重试。
 * - **无反向依赖**：本协调器只从外部调用各步骤，spool 内部/DAO 事务绝不反向 await
 *   本协调器（无死锁环）。
 */
object TokenStatsStartupCoordinator {

    private const val TAG = "TokenStatsStartupCoordinator"

    /** baseline、pending restore 与初始 drain 共用的端到端初始化预算。 */
    internal const val INITIALIZATION_TIMEOUT_MS = 60_000L

    /** [awaitInitialized] 默认等待上限。 */
    private const val DEFAULT_AWAIT_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 单飞 bookkeeping：只保护 [inFlight] 引用，绝不持锁执行初始化（避免反向死锁）。 */
    private val singleFlight = Any()

    /** 进行中的初始化轮；完成后不保留（失败/成功都不缓存，下次调用重新执行）。 */
    private var inFlight: kotlinx.coroutines.Deferred<Boolean>? = null

    // 测试注入缝：生产代码始终为 null，走真实实现。
    internal var ensureMigratedStep: (suspend (Context) -> Boolean)? = null
    internal var consumePendingRestoreStep: (suspend (Context) -> Boolean)? = null
    internal var initialDrainStep: (suspend (Context, Long) -> Boolean)? = null
    internal var initializationTimeoutMsForTest: Long? = null

    /**
     * 等待启动统计就绪（single-flight）：首次调用触发初始化，并发调用 join 同一轮。
     * 返回 true = 本轮初始化成功（含幂等重放）；false = 超时或失败——**不缓存**，
     * 后续调用重新执行（失败的 spool drain 有退避重试，成功后即可重试成功）。
     */
    suspend fun awaitInitialized(
        context: Context,
        timeoutMs: Long = DEFAULT_AWAIT_TIMEOUT_MS,
    ): Boolean {
        val appContext = context.applicationContext
        val job = synchronized(singleFlight) {
            inFlight?.takeIf { it.isActive }
                ?: scope.async {
                    val budgetMs = initializationTimeoutMsForTest ?: INITIALIZATION_TIMEOUT_MS
                    val deadlineNanos =
                        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs)
                    withTimeoutOrNull(budgetMs) {
                        runInitialization(appContext, deadlineNanos)
                    } ?: false
                }.also { inFlight = it }
        }
        // 超时只停止本次等待（初始化继续，幂等；调用方取消则向上传播）。
        return withTimeoutOrNull(timeoutMs) { job.await() } ?: false
    }

    /** 生产 readiness（绑定 applicationContext；测试可注入 no-op 或门控实现）。 */
    fun readiness(context: Context): TokenStatsReadiness =
        TokenStatsReadiness { timeoutMs -> awaitInitialized(context, timeoutMs) }

    private suspend fun runInitialization(context: Context, deadlineNanos: Long): Boolean {
        return try {
            val migrated = ensureMigratedStep
            val migrationReady =
                if (migrated != null) migrated(context)
                else TokenBaselineImportRunner.ensureMigratedStrict(context)
            if (!migrationReady) return false
            val restore = consumePendingRestoreStep
            val restoreReady =
                if (restore != null) restore(context)
                else TokenBaselineImportRunner.consumePendingRestoreStrict(context)
            if (!restoreReady) return false
            val remainingMs =
                TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
            if (remainingMs <= 0L) return false
            val drain = initialDrainStep
            if (drain != null) {
                drain(context, remainingMs)
            } else {
                TokenStatSpool.awaitInitialDrain(context, remainingMs)
            }
        } catch (e: CancellationException) {
            // 取消必须向上传播（scope.async 的任务被外部取消时正常清理）
            throw e
        } catch (e: Exception) {
            // 失败不缓存：记日志并返回 false，下一次调用重新执行
            runCatching { AppLogger.e(TAG, "启动统计初始化失败（不缓存，可重试）", e) }
            false
        }
    }
}

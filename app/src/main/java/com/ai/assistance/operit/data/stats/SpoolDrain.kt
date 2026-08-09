package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.api.chat.llmprovider.TokenStatsPersistenceException
import com.ai.assistance.operit.data.backup.AtomicRestoreMarkerStore
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Internal SpoolDrain responsibilities extracted from [TokenStatSpool]. */
/**
 * 请求合并式 drain 调度（丢失唤醒修复）：每次调用都在 [stateLock] 下置位
 * [drainRequested]——请求绝不丢失；仅当没有 worker 在跑/在队列（[drainScheduled]
 * 为 false）时才入队新任务。worker 每轮开始前消费请求，轮末在同一锁内决定
 * retire/立即 rerun/失败 backoff，请求在轮内到达时由同一 worker 接管。
 *
 * RejectedExecution 恢复正确状态：请求保留（drainRequested=true，绝不丢），仅释放
 * 调度令牌（drainScheduled=false）；下一次 schedule（append/replay/awaitInitialDrain）
 * 会重建 executor（isShutdown 检查）并重新入队。
 */
internal fun TokenStatSpool.scheduleDrain(context: Context, delayMs: Long = 0L) {
    val generation: Long
    synchronized(stateLock) {
        if (writerExecutor.isShutdown) writerExecutor = newWriterExecutor()
        drainRequested = true
        if (drainScheduled) return
        drainScheduled = true
        generation = sessionGeneration
    }
    try {
        if (rejectDrainScheduleForTest) {
            throw RejectedExecutionException("drain schedule rejected (injected)")
        }
        val task = Runnable { runDrain(context, generation) }
        if (delayMs == 0L) writerExecutor.execute(task)
        else writerExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        synchronized(stateLock) { drainScheduled = false }
        logE("statistics drain scheduling failed; request retained", e)
    }
}
/**
 * 每轮开始前消费 drain 请求（持 [stateLock]）。返回 false 表示本轮无需运行：
 * - 无请求：释放调度令牌并 retire；
 * - 已被快照 generation 取代：不触碰任何标志——快照屏障已清 [drainScheduled]，
 *   新 generation 的请求由新 schedule 自行记账，旧 worker 绝不消费新请求。
 */
internal fun TokenStatSpool.consumeDrainRequest(generation: Long): Boolean = synchronized(stateLock) {
    when {
        sessionGeneration != generation -> false
        !drainRequested -> {
            drainScheduled = false
            false
        }
        else -> {
            drainRequested = false
            true
        }
    }
}
/**
 * 轮末决策（持 [stateLock]，同一锁内原子完成等待者与状态转移）：
 * - generation 已变：快照屏障已接管（其 drain/替换处理了被等待的数据），retire
 *   且不触碰标志；等待者按成功完成。
 * - 本轮失败：完成等待者（false），释放调度令牌并计算退避延迟，稍后重试。
 * - 成功且有新请求（轮内到达）：完成等待者（true）后立即 rerun，绝不丢请求。
 * - 成功且无请求：完成等待者（true），释放调度令牌并 retire。
 */
internal fun TokenStatSpool.runDrain(context: Context, generation: Long) {
    while (true) {
        if (!consumeDrainRequest(generation)) return
        var success = false
        try {
            success = runBlocking {
                lifecycleMutex.withLock {
                    if (synchronized(stateLock) { sessionGeneration != generation }) return@withLock true
                    drainCore(context, generation)
                }
            }
        } catch (e: Throwable) {
            logE("statistics spool drain failed", e)
        }
        afterDrainRoundForTest?.invoke()
        val retry: Long
        val rerun: Boolean
        synchronized(stateLock) {
            if (sessionGeneration != generation) {
                completeInitialDrainWaitersLocked(true)
                return
            }
            completeInitialDrainWaitersLocked(success)
            if (!success) {
                drainScheduled = false
                retry = retryDelayMs
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_BACKOFF_CAP_MS)
                rerun = false
            } else if (drainRequested) {
                retryDelayMs = RETRY_BACKOFF_BASE_MS
                retry = 0L
                rerun = true
            } else {
                retryDelayMs = RETRY_BACKOFF_BASE_MS
                drainScheduled = false
                retry = 0L
                rerun = false
            }
        }
        if (rerun) continue
        if (retry > 0L) scheduleDrain(context, retry)
        return
    }
}
internal fun TokenStatSpool.completeInitialDrainWaitersLocked(success: Boolean) {
    if (initialDrainWaiters.isEmpty()) return
    initialDrainWaiters.forEach { waiter ->
        if (waiter.isActive) waiter.complete(success)
    }
    initialDrainWaiters.clear()
}

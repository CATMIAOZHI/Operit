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

/** Internal SpoolLock responsibilities extracted from [TokenStatSpool]. */
/**
 * P1-1 终审：每进程首次使用 spool 前的 durable bootstrap gate（调用方持 lifecycleMutex）。
 *
 * 内存标记 [directoryDurabilityConfirmedThisProcess] 初始为 false，进程重启即清零
 * （测试经 [clearPendingStateForTest]/[resetExecutorsForTest] 模拟进程重启）。若 spool
 * 目录**已存在**——无论它是本进程创建还是**上一进程**创建——都必须先 sync filesDir
 * （确认 spool 目录项持久）再 sync spool 目录（确认 active/metadata 等可见目录项持久）；
 * 两者都 OK 之前不得写新行/返回 durable/做任何目录变更。这样上一进程已可见但未确认的
 * 目录项在本进程重新提交（崩溃后文件可能消失的窗口被关闭）。
 *
 * 目录尚不存在时没有可确认的目录项，放行（首次创建协议在 [append] 中负责创建后同步
 * 父目录与新目录本身；其任一 sync 失败会把本标记保持/复位为 false，下一次使用重新走
 * 本 gate）。任一非 OK 均 fail-closed：不置位标记、返回 false，由调用方明确失败
 * （append 返回 false / drain 退避 / snapshot 抛 IOException）。
 */
internal fun TokenStatSpool.ensureDirectoryDurabilityConfirmed(context: Context, dir: File): Boolean {
    if (directoryDurabilityConfirmedThisProcess) return true
    if (!dir.isDirectory) return true
    val parent = dir.parentFile
    // P1-1 终审：bootstrap 只在 filesDir 与 spool 目录两者都 OK 时才置位；任一非 OK
    // 由 [requireSpoolDirSync] 保持/置回 false（本处进入时 flag 必为 false），绝不置位。
    if (parent == null || !requireSpoolDirSync(parent, dir)) {
        logE(
            "statistics spool directory durability unconfirmed; refusing writes " +
                "until directory entries are re-confirmed: ${dir.absolutePath}",
        )
        return false
    }
    directoryDurabilityConfirmedThisProcess = true
    return true
}
internal suspend fun <T> TokenStatSpool.withExclusiveSnapshotAccessInternal(
    context: Context,
    drainBefore: Boolean,
    clearAfter: Boolean,
    deferredRestoreCommit: (suspend () -> Unit)?,
    block: suspend () -> T,
): T = lifecycleMutex.withLock {
    val appContext = context.applicationContext
    // P1-1 终审：快照/恢复前必须先确认 spool 目录项持久（上一进程可见未确认的目录项
    // 在本进程重新提交）；失败明确中止，绝不带着未确认状态做 drain/替换/清理。
    if (!ensureDirectoryDurabilityConfirmed(appContext, spoolDir(appContext))) {
        throw IOException(
            "statistics spool directory durability could not be confirmed for snapshot",
        )
    }
    val generation = synchronized(stateLock) {
        sessionGeneration += 1L
        drainScheduled = false
        if (clearAfter && deferredRestoreCommit == null) {
            // P1 终审：恢复屏障开始即原子递增 restore epoch——所有在屏障前开始的请求
            // 收尾 append 时 epoch 不匹配而被明确拒绝；导出/快照（clearAfter=false）
            // 不递增，进行中的请求在导出期间正常收尾。
            restoreEpoch += 1L
        }
        sessionGeneration
    }
    if (drainBefore && !drainCore(appContext, generation)) {
        throw IOException("statistics spool could not be drained for snapshot")
    }
    if (drainBefore && hasPendingSegments(appContext)) {
        throw IOException("statistics spool still contains pending events after drain")
    }
    if (drainBefore && hasQuarantineEvidenceForSnapshotLocked(appContext)) {
        throw IOException(
            "statistics quarantine evidence must be exported and acknowledged before snapshot",
        )
    }
    // 排他状态必须在 drain 阶段之后设置：drainBefore 自己的 insert 需要登记。
    // 此后不再有任何新登记（登记与标志检查原子），registry 只减不增。
    synchronized(stateLock) { exclusiveBarrierActive = true }
    try {
        if (!awaitActiveInsertsEmpty()) {
            val live = synchronized(stateLock) { activeInserts.size }
            throw IOException(
                "statistics Room insert still active ($live); " +
                    "snapshot/restore aborted before any file replacement",
            )
        }
        if (clearAfter) {
            if (deferredRestoreCommit != null) {
                var restoreFenceCommitted = false
                try {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        deferredRestoreCommit()
                        synchronized(stateLock) {
                            restoreEpoch += 1L
                            acceptingEventsThisProcess = false
                        }
                        restoreFenceCommitted = true
                    }
                } catch (e: Exception) {
                    if (!restoreFenceCommitted) {
                        // The request fence is unchanged. Resume normal draining after releasing
                        // lifecycleMutex so durable old/new events can still reach the old DB.
                        scheduleDrain(appContext)
                    }
                    throw e
                }
            } else {
                // P1 终审：替换开始（block 即将执行）——本进程不再接受任何统计事件，直到
                // 进程重启（UI 允许稍后重启；替换后失败同样保持拒绝，绝不写入已部分替换的
                // 数据库）。此前任何失败（bootstrap/drain/quiesce）都不触碰该标志，新请求
                // 可继续（替换前失败可恢复）。
                synchronized(stateLock) { acceptingEventsThisProcess = false }
            }
        }
        val result = block()
        if (clearAfter) clearForRestoreLocked(appContext)
        result
    } finally {
        synchronized(stateLock) { exclusiveBarrierActive = false }
    }
}
/**
 * Request/session fencing 判定（P1 终审，调用方持 lifecycleMutex）：请求开始捕获的
 * [sessionEpoch] 必须等于当前 [restoreEpoch]（恢复屏障开始时原子递增使旧请求失效），
 * 且本进程仍接受事件（恢复替换开始后为 false 直至重启）。任一不满足 → 明确拒绝，
 * 绝不写入可能已被恢复替换的 spool。
 */
internal fun TokenStatSpool.fenceAcceptsRestore(sessionEpoch: Long): Boolean =
    synchronized(stateLock) { acceptingEventsThisProcess && sessionEpoch == restoreEpoch }
/**
 * 硬超时等待已登记 insert 全部结束。等待期间不持有 [stateLock]（轮询只短暂取快照），
 * 因此绝不阻塞普通 drain/append；[delay] 可被协程取消，超时由调用方转换为明确失败。
 */
internal suspend fun TokenStatSpool.awaitActiveInsertsEmpty(): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(exclusiveQuiesceTimeoutMs)
    while (true) {
        if (synchronized(stateLock) { activeInserts.isEmpty() }) return true
        if (System.nanoTime() >= deadline) return false
        delay(QUIESCE_POLL_INTERVAL_MS)
    }
}
internal fun TokenStatSpool.clearForRestoreLocked(context: Context) {
    val dir = spoolDir(context)
    // P1-1 终审修复：删除开始前立即失效 bootstrap gate——删除本身是目录项变更，删除后
    // 任何 sync 失败都不得让“已确认”内存标记继续生效，下一次使用必须重新确认（或重新
    // 走首次创建协议）。
    directoryDurabilityConfirmedThisProcess = false
    if (dir.exists()) {
        val deleted = spoolDeleteForTest?.invoke(dir) ?: dir.deleteRecursively()
        if (!deleted || dir.exists()) {
            throw IOException("statistics spool cleanup failed: ${dir.absolutePath}")
        }
    }
    // P1-3 终审：spool 目录项删除（可能刚发生且可见）必须确认持久，否则 restore 失败并
    // 保留恢复状态；目录删除可见但 sync 失败时重试幂等（目录已不存在则跳过删除，本处
    // 仍 sync filesDir 确认“删除/不存在”持久后才放行）。P1-1：sync 非 OK 由
    // [requireSpoolDirSync] 同步失效 gate（本函数开头已失效，保持 false 供下次重新确认）。
    val parent = dir.parentFile
    if (parent == null || !requireSpoolDirSync(parent)) {
        throw IOException(
            "statistics spool cleanup not durable; restore state retained: ${dir.absolutePath}",
        )
    }
    synchronized(stateLock) {
        insertionWaiters.values.forEach { it.cancel() }
        insertionWaiters.clear()
    }
}

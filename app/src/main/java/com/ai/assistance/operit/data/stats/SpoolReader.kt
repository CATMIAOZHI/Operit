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

/** Internal SpoolReader responsibilities extracted from [TokenStatSpool]. */
/** Called with lifecycleMutex held. */
internal suspend fun TokenStatSpool.drainCore(context: Context, generation: Long): Boolean {
    // P1-1 终审：replay/维护/快照 drain 前必须先确认 spool 目录项持久（上一进程可见
    // 未确认的目录项在本进程重新提交）；失败退避重试，绝不带着未确认状态做任何目录变更。
    if (!ensureDirectoryDurabilityConfirmed(context, spoolDir(context))) {
        logE("statistics spool drain deferred: directory durability unconfirmed")
        return false
    }
    val dao = resolveDaoSafely(context) ?: return false
    // P1-2 维护/后台重试：先清理删除失败被隔离的残留（pending-delete 与 tombstoned 段）。
    // 返回 false 表示本轮存在目录项未确认持久的变更——drain 退避重试，绝不推进状态。
    if (!retryPendingCleanup(context)) {
        logE("statistics spool maintenance deferred: directory changes unconfirmed")
        return false
    }
    var lastRound: List<File> = emptyList()
    while (synchronized(stateLock) { sessionGeneration == generation }) {
        val segments = sealAndList(context) ?: return false
        if (segments.isEmpty()) return true
        // P1-1 有界推进：受管集合已满且仍无法处置的段会停留在队列（未入受管集合）。
        // 连续两轮同一集合说明无进展——跳过并返回，健康段已排空，绝不无限重扫/持锁。
        if (segments == lastRound) {
            logE(
                "statistics spool drain cannot make progress (managed-failure set full?); " +
                    "leaving ${segments.size} unmanageable segment(s): ${segments.joinToString { it.name }}",
            )
            return true
        }
        lastRound = segments
        for (segment in segments) if (!drainSegment(context, dao, segment)) return false
    }
    return true
}
internal suspend fun TokenStatSpool.sealAndList(context: Context): List<File>? {
    val dir = spoolDir(context)
    if (!dir.isDirectory) return emptyList()
    val active = File(dir, ACTIVE_FILE_NAME)
    if (active.isFile && active.length() > 0L && !sealActive(context, dir)) {
        return null
    }
    // P1-2：tombstone 按稳定 identity（file+bytes+sha256）跳过，绝不只信文件名
    return sealedFilesToProcess(context, dir, readTombstoneLines(context))
}
internal suspend fun TokenStatSpool.hasPendingSegments(context: Context): Boolean {
    val dir = spoolDir(context)
    val processableNames =
        sealedFilesToProcess(context, dir, readTombstoneLines(context)).mapTo(HashSet()) { it.name }
    // P1-7 fail-closed：待处理判定依赖枚举完整性——根枚举失败（null）时绝不能当作
    // “没有 pending”返回（那会让快照在仍有待处理事件时误成功）。抛 IOException 由
    // [withExclusiveSnapshotAccess] 传播，[block] 绝不执行，文件保持原样。
    val files = listDir(dir)
        ?: throw IOException("cannot enumerate spool directory for pending segments: ${dir.absolutePath}")
    return files.any {
        it.isFile &&
            it.length() > 0L &&
            (it.name == ACTIVE_FILE_NAME ||
                (it.name.startsWith(SEALED_PREFIX) &&
                    it.name.endsWith(SEALED_SUFFIX) &&
                    it.name in processableNames))
    }
}
/**
 * Raw snapshots intentionally exclude the active spool queue. Quarantine files and their
 * summary/tombstone/trash metadata are not queue data and must never be silently omitted from a
 * successful snapshot. Until raw restore has a selective evidence-preservation protocol, fail
 * before ZIP creation and leave every evidence byte in place for explicit export/acknowledgment.
 */
internal suspend fun TokenStatSpool.hasQuarantineEvidenceForSnapshotLocked(context: Context): Boolean {
    val dir = spoolDir(context.applicationContext)
    if (quarantineAreaFiles(dir).isNotEmpty()) return true
    if (stuckAckTrashEvidenceLocked(context).isNotEmpty()) return true
    if (readTombstoneLines(context).isNotEmpty()) return true
    val summaryFile = File(dir, QUARANTINE_SUMMARY_NAME)
    return readMetadata(summaryStore(summaryFile), summaryFile)?.isNotBlank() == true
}
/**
 * P1-2：对 sealed 队列应用受管失败集合。身份匹配的段跳过（受管）；身份不匹配或文件已
 * 消失的条目是陈旧 tombstone（旧文件已删但 manifest 未更新，随后同名不同 hash 的健康
 * 段复用）——移除陈旧记录并正常处理新文件，绝不删/跳过健康。例外（P1-2）：条目身份仍
 * 停留在未提交 ack trash 中时绝不按陈旧移除——该身份的证据还存在于 trash，移除会让崩溃
 * 窗口判定把未确认的证据误判为已提交而删除。身份**不可校验**（读取失败）的段本轮跳过
 * 且保留 manifest 条目：无法证明当前文件仍是记录中的段时，既不处理也不清理，绝不基于
 * 失败猜测破坏性决策。陈旧记录移除在持锁下崩溃安全重写 manifest。
 */
internal suspend fun TokenStatSpool.sealedFilesToProcess(context: Context, dir: File, rawLines: List<String>): List<File> {
    val trashHold = scanUncommittedTrashHolds(context)
    val heldInTrash = trashHold.known
    val entries = rawLines.mapNotNull(::parseTombstoneLine)
    val stale = mutableListOf<TombstoneEntry>()
    // P1-7 fail-closed：sealed 队列枚举失败（null）时抛 IOException——drain 据此退避
    // 重试、快照/恢复中止；绝不把失败当作空队列（否则 drain 会在仍有待处理段时误成功，
    // 快照 barrier 也随之误判“无 pending”）。
    val allFiles = listDir(dir)
        ?: throw IOException("cannot enumerate spool directory for sealed segments: ${dir.absolutePath}")
    val files = allFiles
        .filter {
            it.isFile &&
                it.name.startsWith(SEALED_PREFIX) &&
                it.name.endsWith(SEALED_SUFFIX)
        }
        .sortedBy { it.sealIndex() }
        .filter { file ->
            val entry = entries.firstOrNull { it.file == file.name }
            when {
                    entry == null -> true
                    else -> when (tombstoneIdentityCheck(entry, file)) {
                        IdentityCheck.MATCH -> false
                        IdentityCheck.MISMATCH -> {
                            // P1-2：旧身份仍停留在未提交 trash 中 → 保留条目（证据未
                            // 消失），但仍正常处理同名新文件（旧文件在 trash 中不可能
                            // 与当前文件同名共存，身份判定互不干扰）。
                            // P1-4：存在无法完整严格解析的 UNCOMMITTED trash 时同样
                            // 保留条目——旧身份可能正被其持有，scanner 绝不把可能受
                            // trash 持有的身份当无保护而按 stale 移除。
                            if (!trashHold.hasUnknown &&
                                heldInTrash[entry.file] != (entry.bytes to entry.sha256)
                            ) {
                                stale += entry
                            }
                            true
                        }
                        IdentityCheck.UNREADABLE -> {
                            logE(
                                "statistics tombstone identity unreadable; keeping manifest " +
                                    "entry and skipping the segment this round: ${file.name}",
                            )
                            false
                        }
                    }
                }
            }
    if (stale.isNotEmpty()) {
        logE(
            "statistics tombstone manifest has stale identities (vanished or reused-name files); " +
                "removing: ${stale.joinToString { it.file }}",
        )
        rewriteTombstoneManifest(
            context = context,
            remainingRawLines = rawLines.filterNot { line -> parseTombstoneLine(line)?.let { it in stale } == true },
        )
    }
    return files
}
internal suspend fun TokenStatSpool.drainSegment(
    context: Context,
    dao: TokenStatsDao,
    segment: File,
): Boolean {
    val rawBytes = try {
        if (segmentReadErrorForTest?.invoke(segment) == true) {
            throw IOException("statistics spool segment read failed (injected): ${segment.name}")
        }
        segment.readBytes()
    } catch (e: Exception) {
        logE("statistics spool segment read failed: ${segment.name}", e)
        return false
    }
    afterSegmentReadForTest?.invoke()
    // 身份哈希一律基于原始字节（readText 会对非法 UTF-8 做替换再编码，与文件字节
    // 不一致会让 tombstone 身份永远无法匹配损坏段，造成反复重扫/重复条目）。
    val text = String(rawBytes, Charsets.UTF_8)
    var corrupt = false
    var corruptLineCount = 0
    for (line in text.lineSequence().filter { it.isNotEmpty() }) {
        val request = try {
            TokenStatRequestContext.fromSpoolLine(line)
        } catch (e: Exception) {
            corrupt = true
            corruptLineCount += 1
            logE("statistics spool line corrupt; preserving segment evidence: ${segment.name}", e)
            continue
        }
        if (!insertSafely(context, dao, request)) return false
        synchronized(stateLock) {
            insertionWaiters.remove(request.eventId)?.complete(Unit)
        }
    }
    if (corrupt) {
        val existingBytes = quarantineEvidenceLocked(context).sumOf { it.length() }
        if (existingBytes + segment.length() > MAX_QUARANTINE_BYTES) {
            // 硬边界（P2-1）：容量内保留完整证据；超限的新损坏段只保留固定大小滚动
            // 摘要（计数/hash/字节/时间，不含正文），并移除原段，磁盘占用有界。
            // 健康排空不受影响：本段处理完立即继续后续 segment。
            if (!summarizeOverCapSegment(context, segment, rawBytes, text, corruptLineCount)) {
                logE("statistics quarantine hard cap: over-cap segment retained: ${segment.name}")
                return false
            }
            return true
        }
        val target = File(
            segment.parentFile,
            "$QUARANTINE_PREFIX${UUID.randomUUID().toString().replace("-", "")}_${segment.name}",
        )
        if (!renameForTest(segment, target)) {
            // P1-2：证据重命名失败也不能阻塞健康排空——容量内预算允许时先移入有界
            // pending-delete 诊断区（完整证据，维护入口会移回证据区），再失败才 tombstone
            val pending = File(
                segment.parentFile,
                "$PENDING_DELETE_PREFIX${UUID.randomUUID().toString().replace("-", "")}_${segment.name}",
            )
            if (renameForTest(segment, pending)) {
                // P1 终审：rename 后目录项必须确认持久，非 OK fail-closed（段内容在任一
                // 名字下保留，绝不丢原始证据；drain 退避重试下一轮）。P1-1：非 OK 同时
                // 失效 gate。
                if (!requireSpoolDirSync(segment.parentFile!!)) {
                    logE(
                        "statistics corrupt segment pending-delete rename not durable; " +
                            "deferring round: ${pending.name}",
                    )
                    return false
                }
                logE("statistics corrupt segment quarantine rename failed; retained as pending-delete evidence: ${pending.name}")
                return true
            }
            logE("statistics corrupt segment quarantine rename failed; tombstoning: ${segment.name}")
            // P1-1：受管集合满时跳过该段继续健康（有界重扫），写失败才退避重试
            return when (tombstoneSegment(context, segment, rawBytes, overCap = false)) {
                TombstoneResult.RECORDED, TombstoneResult.CAPACITY_FULL -> true
                TombstoneResult.FAILED -> false
            }
        }
        // P1 终审：证据 rename 后目录项必须确认持久，非 OK fail-closed——证据内容在
        // quarantine 名下保留（绝不丢原始），本轮退避由 drain 重试。P1-1：非 OK 同时
        // 失效 gate。
        if (!requireSpoolDirSync(segment.parentFile!!)) {
            logE(
                "statistics corrupt segment quarantine rename not durable; " +
                    "deferring round: ${target.name}",
            )
            return false
        }
        return true
    }
    if (!(segmentDeleteForTest?.invoke(segment) ?: segment.delete())) {
        logE("statistics spool segment deletion failed: ${segment.name}")
        return false
    }
    // P1 终审：删除后目录项必须确认持久，非 OK fail-closed（行已入 Room，崩溃后文件
    // 复活会被 INSERT IGNORE 幂等重放，绝不丢数据；本轮退避下一轮继续）。P1-1：非 OK
    // 同时失效 gate。
    if (!requireSpoolDirSync(segment.parentFile!!)) {
        logE("statistics spool segment deletion not durable: ${segment.name}")
        return false
    }
    return true
}
/**
 * Room insert with a genuinely bounded lifecycle. The write runs on the dedicated single-thread
 * insert worker; the drain waits at most [insertTimeoutMs] and on timeout releases the lifecycle
 * lock WITHOUT joining the worker (SQLite can ignore interrupts forever, and an unbounded join
 * under the lock would freeze every append/snapshot/replay).
 *
 * Safety across the snapshot/restore barrier (P1-2) has two halves:
 * - Generation fencing: the task captures the session generation at submission and atomically
 *   re-checks it together with [exclusiveBarrierActive] before touching Room. A task that runs
 *   after a restore bumped the generation skips entirely (its durable segment belongs to the
 *   pre-restore state that restore replaces).
 * - Active-insert registry: the same atomic section registers the insert BEFORE Room is
 *   entered and the `finally` unregisters it. A snapshot/restore therefore provably waits (or
 *   bounded-fails) for every insert that already passed the fence, instead of merely relying on
 *   a check-then-act race that could let an old DAO write into replaced files.
 */
internal suspend fun TokenStatSpool.insertSafely(
    context: Context,
    dao: TokenStatsDao,
    request: TokenStatRequestContext,
): Boolean {
    val generation = synchronized(stateLock) { sessionGeneration }
    val task = FutureTask<Unit> {
        val registered = synchronized(stateLock) {
            if (sessionGeneration != generation || exclusiveBarrierActive) {
                false
            } else {
                activeInserts[request.eventId] = generation
                true
            }
        }
        if (!registered) return@FutureTask
        try {
            runBlocking { TokenStatsLedger.recordWith(context, dao, request) }
            synchronized(stateLock) {
                insertionWaiters.remove(request.eventId)?.complete(Unit)
            }
        } finally {
            synchronized(stateLock) { activeInserts.remove(request.eventId) }
        }
    }
    try {
        insertExecutor.execute(task)
    } catch (e: RejectedExecutionException) {
        logE("statistics insert worker saturated; durable segment retained: ${request.eventId}", e)
        return false
    }
    return try {
        task.get(insertTimeoutMs, TimeUnit.MILLISECONDS)
        true
    } catch (e: TimeoutException) {
        // The worker may legitimately outlive this wait; the durable segment stays for a later
        // drain and the generation fence keeps a late write out of a restored database.
        task.cancel(true)
        logE("statistics Room insert timed out; durable segment retained: ${request.eventId}", e)
        false
    } catch (e: ExecutionException) {
        logE("statistics Room insert failed; durable segment retained: ${request.eventId}", e.cause ?: e)
        false
    } catch (e: CancellationException) {
        logE("statistics Room insert cancelled; durable segment retained: ${request.eventId}", e)
        false
    } catch (e: Throwable) {
        logE("statistics Room insert failed; durable segment retained: ${request.eventId}", e)
        false
    }
}
/**
 * Database preparation with bounded single-flight semantics: at most one resolution runs at a
 * time, and a timed-out resolution is reused by later drain cycles instead of spawning another
 * thread (P2-1). A permanently wedged open cannot recover without a restart, but it can never
 * block the lifecycle lock or leak threads.
 */
internal fun TokenStatSpool.resolveDaoSafely(context: Context): TokenStatsDao? {
    val task = synchronized(stateLock) {
        pendingDaoTask?.takeIf { !it.isDone }
            ?: FutureTask<TokenStatsDao> {
                (TokenStatsLedger.databaseProvider?.invoke(context) ?: AppDatabase.getDatabase(context))
                    .tokenStatsDao()
            }.also { created ->
                pendingDaoTask = created
                try {
                    databaseExecutor.execute(created)
                } catch (e: RejectedExecutionException) {
                    pendingDaoTask = null
                    throw e
                }
            }
    }
    return try {
        task.get(prepareTimeoutMs, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        task.cancel(true)
        logE("statistics database preparation timed out; durable segments retained", e)
        null
    } catch (e: RejectedExecutionException) {
        logE("statistics database preparation rejected; durable segments retained", e)
        null
    } catch (e: ExecutionException) {
        logE("statistics database preparation failed; durable segments retained", e.cause ?: e)
        null
    } catch (e: Throwable) {
        logE("statistics database preparation failed; durable segments retained", e)
        null
    }
}

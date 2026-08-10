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

/**
 * Durable token-stat outbox.
 *
 * The acceptance boundary is a synchronous append plus fsync of a complete event line. Price
 * resolution happens before this object is called; the background worker only inserts immutable
 * rows into Room. There is deliberately no memory overflow queue: a disk failure is returned to
 * the model-call boundary and cannot be mistaken for a durable statistic.
 *
 * [lifecycleMutex] is also the raw-snapshot barrier. A snapshot drains every pending line before
 * checkpointing; restore invalidates queued workers before replacing files and verifies cleanup
 * before reporting success.
 *
 * Room insert isolation across the snapshot/restore boundary is enforced by an active-insert
 * registry plus generation fencing (P1-2): an insert task atomically re-checks the session
 * generation and the exclusive flag, and only then registers itself before touching Room. A
 * snapshot/restore bumps the generation, blocks new registrations, and waits a hard-bounded
 * [exclusiveQuiesceTimeoutMs] for the registry to empty; on timeout it fails explicitly BEFORE
 * any file replacement (or checkpoint), so an old insert that is wedged inside Room can never
 * overlap a replaced database. Lock order is always `lifecycleMutex -> stateLock` and never
 * reversed, so a normal drain/append can never deadlock on the registry wait.
 *
 * In-flight provider/stream requests are NOT visible to the insert registry (they have not
 * reached [append] yet), so the registry alone cannot stop an old request from writing into a
 * restored database. Request/session fencing (P1 终审) closes that gap:
 * - Every request captures [restoreEpoch] synchronously at its start via [captureRestoreEpoch]
 *   (pure in-memory, no Room) and carries it in its [TokenStatRequestContext.sessionEpoch].
 * - [append] validates the captured epoch against the current [restoreEpoch] AND
 *   [acceptingEventsThisProcess] inside [lifecycleMutex] before any spool write. Raw restore uses
 *   [withExclusiveRestoreAccess] to increment [restoreEpoch] only after its durable REPLACING
 *   commit; every old request is then rejected before stores close or directories are replaced.
 * - Once a restore replacement actually starts (right before [block] runs), the process stops
 *   accepting ALL statistics events ([acceptingEventsThisProcess] = false) until it restarts:
 *   the UI allows restarting later, so same-process new requests must be explicitly rejected
 *   ([isAcceptingEvents], used by the tracking boundary) and can never pollute the new DB.
 *   A restore failure BEFORE replacement leaves accepting enabled (new requests continue);
 *   a failure after replacement has started keeps it disabled and requires a restart.
 */
internal object TokenStatSpool {
    internal const val TAG = "TokenStatSpool"
    internal const val SPOOL_DIR_NAME = "token_stats_spool"
    internal const val ACTIVE_FILE_NAME = "active.jsonl"
    internal const val SEALED_PREFIX = "sealed_"
    internal const val SEALED_SUFFIX = ".jsonl"
    internal const val QUARANTINE_PREFIX = "quarantine_"

    /**
     * P2：seal copy 回退中途失败的部分目标隔离前缀（`seal_failed_<uuid>`）。隔离文件 scanner
     * 忽略（不匹配 [SEALED_PREFIX]）、计入递归总 cap（占用可见）、由维护入口 [retryPendingCleanup]
     * 清理（active 保留完整内容，隔离副本删除安全，无数据损失）。P2 终审：同时作为**受管失败
     * 发布证据**计入 [quarantineAreaFiles]——长期删除失败时可见（quarantineEvidence/info 字节）、
     * 可导出、可确认删除（ack 按 NOFOLLOW/path 根校验删除并释放容量），绝不无限隐藏占用。
     */
    internal const val SEAL_FAILED_PREFIX = "seal_failed_"

    /**
     * ack 删除的事务化暂存目录前缀（reviewer P1）：ack 先把全部待删文件 rename 进本轮唯一
     * 的 trash 目录（同 filesystem、可回滚），全部成功后才重写 manifest。P1-2：trash 目录内
     * 先原子写入状态文件 [ACK_TRASH_STATE_FILE_NAME]（首行 UNCOMMITTED/COMMITTED + mapping
     * 行），维护入口 [retryPendingCleanup] 按持久状态处置：**只有显式 COMMITTED 才允许后台
     * 补删**；UNCOMMITTED 一律按 mapping+identity 回滚（P1-1 修复：绝不根据主 manifest 缺失
     * 推断已提交——普通 quarantine 证据从未进入 manifest，缺失恒成立，旧推断会把未确认的
     * 证据误删；主 manifest 已发布但 COMMITTED marker 未写时，回滚后的损坏 sealed 会被扫描器
     * 重新隔离，ack 视失败但不丢证据）。状态缺失/损坏或回滚长期失败的 trash 作为
     * StuckAckEvidence 由 UI 管理（见 [stuckAckTrashEvidence]），绝不自动删除。trash 目录及
     * 其内容计入递归总容量（P1-1），占用绝不隐藏。目录名不可与任何证据/元数据文件前缀冲突。
     */
    internal const val ACK_TRASH_PREFIX = "quarantine_ack_trash_"

    /** ack trash 内的原子状态文件（P1-2）：首行 = [ACK_STATE_UNCOMMITTED]/[ACK_STATE_COMMITTED]，后续行 = mapping（原名 → trash 名 + bytes + sha256）。 */
    internal const val ACK_TRASH_STATE_FILE_NAME = "ack_state.jsonl"
    internal const val ACK_STATE_UNCOMMITTED = "UNCOMMITTED"
    internal const val ACK_STATE_COMMITTED = "COMMITTED"
    internal const val MAX_LINE_BYTES = 8 * 1024

    /** 单段封顶字节（P1-2 测试可注入更小值，端到端验证 seal 与总容量边界）。 */
    internal var MAX_SEGMENT_BYTES = 4L * 1024 * 1024

    /**
     * 总 spool 硬上限（P1-1/P1-2）：active/sealed/pending-delete/quarantine/summary/manifest
     * 及 sidecar/tmp 与 ack trash 等**全部管理文件**的实际字节总和（递归，见
     * [totalSpoolBytes]），任意时刻恒 ≤ 该值。
     *
     * 数据准入上限 = 总上限 − [METADATA_RESERVE_BYTES]（见 [dataAdmissionMaxBytes]）：append
     * 准入时投影（当前总量 + 本次行字节）超过即明确抛 [TokenStatsPersistenceException]，
     * 绝不发布新文件——Room 长期失败时 sealed 段因此也有界（256MiB / 4MiB 段 ≈ 64 段）。
     * 元数据（summary/manifest 及其 sidecar/tmp、ack trash 状态/mapping）写在发布前另行投影
     * `totalSpoolBytes + worstCaseAdditional ≤ 总上限`（见 [metadataWriteBudgetExceeded]），
     * 因此 drain/ack 的元数据发布同样不可能把实际总量推过总上限。测试可注入更小值。
     */
    internal const val TOTAL_SPOOL_MAX_BYTES = 256L * 1024 * 1024
    internal var totalSpoolMaxBytesForTest: Long? = null

    /**
     * Hard retention cap for full corrupt-segment evidence. Within the cap the complete evidence
     * is preserved; a NEW corrupt segment that would exceed the cap is replaced by a bounded
     * rolling summary (count/hash/bytes/time, never content) and removed, so disk usage stays
     * bounded while healthy drains continue.
     */
    internal const val MAX_QUARANTINE_BYTES = 16L * 1024 * 1024

    /** Fixed-size rolling summary of over-cap corrupt segments (atomic update, never grows unbounded). */
    internal const val QUARANTINE_SUMMARY_NAME = "quarantine_summary.jsonl"
    internal const val MAX_QUARANTINE_SUMMARY_BYTES = 64L * 1024
    internal const val MAX_QUARANTINE_SUMMARY_LINES = 256

    /**
     * 有界 skip/tombstone manifest（P1-2/P1-1）：既不能删除也不能重命名出 sealed 队列的段身份
     * 记入此处（时间/文件名/字节/SHA-256/是否超限，不含正文），扫描器按稳定 identity
     * （file+bytes+sha256）跳过该具体文件并继续后续健康段；维护入口（drain 重试）会再次尝试
     * 处置并移除记录。
     *
     * 这是**不滚动**的活跃受管失败集合：条目只在对应文件物理消失或身份变化后移除（P1-2），
     * 绝不能像历史摘要那样滚动丢弃仍存在文件的身份（P1-1：滚动会让旧段重新进入扫描队列，
     * 造成无界重扫循环）。硬上限 [MAX_TOMBSTONE_ENTRIES]/[MAX_TOMBSTONE_MANIFEST_BYTES]/
     * [MAX_MANAGED_BYTES] 到达后停止接受新的统计 append（明确抛
     * [TokenStatsPersistenceException]，不产生更多段），drain 有界跳过并继续健康段。
     */
    internal const val TOMBSTONE_MANIFEST_NAME = "quarantine_skip_manifest.jsonl"

    /** 活跃受管失败集合的身份硬上限：超过后新统计 append 明确失败（不能继续产生更多段）。 */
    internal const val MAX_TOMBSTONE_ENTRIES = 64

    /** 受管失败集合的 manifest 文件字节硬上限（64 条约 200B，纯 ASCII，正常远达不到）。 */
    internal const val MAX_TOMBSTONE_MANIFEST_BYTES = 64L * 1024

    // ── P1-1 元数据预留（总 cap 证明）────────────────────────────────────────────
    // 有界元数据文件（quarantine summary / tombstone manifest）各有 4 个磁盘槽位：
    // canonical、`.new`、`.bak`、`.tmp<随机>`。原子替换或回退协议的任意中断窗口下四个
    // 槽位都可能同时各持一份完整副本（read 恢复/清理前），因此单份元数据的最坏磁盘
    // 占用 = 4 × 内容硬上限。所有 spool 元数据读写都持 lifecycleMutex（至多一个写进行
    // 中，Atomic tmp 唯一文件并发数 = 1），预留按单写者计算即可覆盖。
    /** 单份有界元数据的磁盘槽位数（canonical + .new + .bak + tmp）。 */
    internal const val METADATA_COPY_COUNT = 4

    /** 有界元数据文件数：quarantine summary + tombstone manifest。 */
    internal const val METADATA_FILE_COUNT = 2

    /** 单份有界元数据内容的字节硬上限（summary/manifest 中较大者，均为纯 ASCII 有界）。 */
    internal val MAX_METADATA_FILE_BYTES: Long =
        maxOf(MAX_QUARANTINE_SUMMARY_BYTES, MAX_TOMBSTONE_MANIFEST_BYTES)

    /**
     * 元数据预留（P1-1）：数据准入上限 = 总上限 − 本预留。预留 = 2 个元数据文件 × 4 个
     * 槽位 × 内容硬上限，覆盖 summary 与 manifest 各 canonical/.new/.bak/tmp 最坏副本、
     * overflow summary（quarantine_summary 本身）与固定大小临时文件（tmp 内容同 bound）。
     * 生产值 512KiB = 2 × 4 × 64KiB，远小于 256MiB 总上限（[init] 有 require 证明）。
     */
    internal val METADATA_RESERVE_BYTES: Long =
        METADATA_FILE_COUNT * METADATA_COPY_COUNT * MAX_METADATA_FILE_BYTES

    init {
        require(METADATA_RESERVE_BYTES < TOTAL_SPOOL_MAX_BYTES) {
            "metadata reserve must be strictly smaller than the total spool cap: " +
                "$METADATA_RESERVE_BYTES >= $TOTAL_SPOOL_MAX_BYTES"
        }
    }

    /** 受管失败集合中仍占用磁盘的段的总字节硬上限（64 × 单段 4MiB 封顶；段大小可注入时实时计算）。 */
    internal val MAX_MANAGED_BYTES get() = MAX_TOMBSTONE_ENTRIES * MAX_SEGMENT_BYTES

    /** 删除失败的 over-cap 段的诊断去向：完整证据（计入硬 cap，可导出/确认删除）。 */
    internal const val PENDING_DELETE_PREFIX = "quarantine_pending_delete_"
    internal const val RETRY_BACKOFF_BASE_MS = 1_000L
    internal const val RETRY_BACKOFF_CAP_MS = 30_000L
    internal var insertTimeoutMs: Long = 5_000L
    internal var prepareTimeoutMs: Long = 5_000L

    /** 排他快照/恢复等待已登记 insert 全部结束的硬超时；超时则操作明确失败，绝不替换文件。 */
    internal var exclusiveQuiesceTimeoutMs: Long = 5_000L
    internal const val QUIESCE_POLL_INTERVAL_MS = 50L

    /** 文件 I/O 调度缝（P2-2）：导出/确认删除的复制、fsync、扫描绝不运行在调用方（Main）线程。 */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    internal val lifecycleMutex = Mutex()
    internal val stateLock = Any()

    /**
     * 调度令牌：有 worker 任务已入队/正在运行（用于入队去重）。快照屏障在递增
     * [sessionGeneration] 时同步清空，使旧 generation 的排队 worker 失效。
     */
    internal var drainScheduled = false

    /**
     * 未消费的 drain 请求（丢失唤醒修复）：每次 [scheduleDrain] 都在 [stateLock] 下
     * 置位；worker 每轮开始前消费，轮末在同一锁内决定 retire/立即 rerun/失败 backoff。
     * 请求在轮内到达时由同一 worker 接管，绝不依赖下一次外部触发；RejectedExecution
     * 时请求保留、仅释放调度令牌（见 [scheduleDrain]）。
     */
    internal var drainRequested = false

    /**
     * [awaitInitialDrain] 的等待者：worker 轮末决策点持 [stateLock] 统一完成并清空；
     * 完成/失败都不保留——下次调用重新登记并触发新轮（失败不缓存，可重试）。
     */
    internal val initialDrainWaiters = ArrayList<CompletableDeferred<Boolean>>()

    internal var sessionGeneration = 0L
    internal var retryDelayMs = RETRY_BACKOFF_BASE_MS
    internal var writerExecutor = newWriterExecutor()

    /**
     * 排他快照/恢复进行中：阻止新 insert 登记（与 insert 的登记在同一 critical section 原子判定）。
     */
    internal var exclusiveBarrierActive = false

    /** 已通过 fence 且正在 Room 内写入的 insert（eventId -> 提交时 generation）。 */
    internal val activeInserts = HashMap<String, Long>()

    /**
     * Request/session fencing epoch（P1 终审）：通用恢复屏障开始时递增；Raw restore 则在
     * 外部 REPLACING 状态成功持久化后、关闭 stores 前原子递增，
     * 使所有在屏障开始前开始（已捕获旧 epoch）的 in-flight provider/stream 请求在收尾
     * [append] 时被明确拒绝——绝不写入可能已被恢复替换的 spool/Room。导出/快照屏障
     * （clearAfter=false）不递增：进行中的请求在导出期间正常收尾。进程内单调递增，
     * 不随普通 [withExclusiveSnapshotAccess] 变化；测试经 [clearPendingStateForTest] 复位。
     */
    internal var restoreEpoch = 0L

    /**
     * 本进程是否仍接受统计事件（P1 终审）：恢复屏障的替换开始（[block] 即将执行）时置 false，
     * 直到进程重启——此后所有 [append] 与新的跟踪请求被明确拒绝，绝不污染
     * 恢复后的新 DB。替换前失败（drain/quiesce 阶段抛错）保持 true，新请求可继续。
     * 进程重启（含测试模拟）经 [resetExecutorsForTest]/[clearPendingStateForTest] 复位。
     */
    @Volatile
    internal var acceptingEventsThisProcess = true

    /**
     * Dedicated bounded insert worker. Room/SQLite writes can ignore thread interrupts, so the
     * drain never joins this worker; a timed-out task is detached and the durable segment is
     * retried later. The single daemon thread plus one queue slot is the hard bound (P2-1), so a
     * permanently wedged database cannot leak threads or hold the lifecycle lock.
     */
    internal var insertExecutor = newInsertExecutor()

    /** Dedicated bounded database-preparation worker with single-flight semantics (P2-1). */
    internal var databaseExecutor = newDatabaseExecutor()
    internal var pendingDaoTask: FutureTask<TokenStatsDao>? = null
    internal val insertionWaiters = HashMap<String, CompletableDeferred<Unit>>()

    /** 测试注入缝：返回 null 走真实删除；返回 false 模拟删除失败（P1-2 分支注入）。 */
    internal var segmentDeleteForTest: ((File) -> Boolean?)? = null

    /** 测试注入缝：返回 null 走真实 renameTo；返回 false 模拟段处置重命名失败。 */
    internal var segmentRenameForTest: ((File, File) -> Boolean?)? = null
    internal var afterSegmentReadForTest: (() -> Unit)? = null
    internal var spoolDeleteForTest: ((File) -> Boolean)? = null

    /** 测试注入缝：返回 false 强制摘要/manifest 原子替换不支持（走 old/new/backup 回退）。 */
    internal var quarantineAtomicMoveForTest: ((File, File) -> Boolean)? = null

    /** 测试注入缝：返回 true 时对应元数据文件（summary/manifest）读取抛 IOException（P1-2）。 */
    internal var metadataReadErrorForTest: ((File) -> Boolean)? = null

    /** 测试注入缝：返回 true 时对应元数据文件发布抛 IOException。 */
    internal var metadataWriteErrorForTest: ((File) -> Boolean)? = null

    /**
     * 可控 seal publication seam（P1-8）：候选编号选定后、实际发布前以目标文件调用。
     * 返回 false 模拟发布前失败（seal 明确失败）；测试可在回调里创建同名不同内容的目标
     * 文件模拟冲突（返回 true），真实发布路径必须检测到占用并选下一编号，目标原字节
     * 保持不变。返回 null 表示无操作。
     */
    internal var beforeSealPublishForTest: ((File) -> Boolean?)? = null

    /** 测试注入缝：返回 true 时 [scheduleDrain] 的入队被模拟拒绝（RejectedExecution 状态恢复）。 */
    internal var rejectDrainScheduleForTest: Boolean = false

    /** 测试注入缝：每轮 drain（runBlocking 结束、轮末决策前）在 worker 线程调用。 */
    internal var afterDrainRoundForTest: (() -> Unit)? = null

    /** 测试注入缝：返回 false 强制模拟硬链接不受支持（走 copy 回退发布）；其余走真实 createLink。 */
    internal var sealHardLinkForTest: ((File, File) -> Boolean?)? = null

    /** 测试注入缝：返回 false 模拟 seal 发布成功后删除 active 失败（硬链接崩溃窗口）。 */
    internal var sealActiveDeleteForTest: ((File) -> Boolean?)? = null

    /**
     * 测试注入缝（P1 终审）：返回 null 走真实 fsync；返回 false 模拟 seal 目标文件 fsync
     * 失败（调用方必须保留 active、处置目标并返回 FAILED，绝不声称 PUBLISHED）。
     */
    internal var fileSyncForTest: ((File) -> Boolean?)? = null

    /**
     * 测试注入缝（P1 终审）：返回 null 走真实目录 fsync；返回 [DirSyncResult.OK] 模拟目录
     * fsync 成功；[DirSyncResult.FAILED] 模拟真实失败（发布路径 fail-closed）；
     * [DirSyncResult.UNSUPPORTED] 模拟平台明确不支持（发布路径同样 fail-closed——绝不当作
     * 成功继续删除唯一 fsynced active 或声称 PUBLISHED）。生产平台（Android/Linux）支持
     * 目录 fd fsync；Windows 仅 JVM 测试环境，测试统一注入 OK 运行正常路径，UNSUPPORTED
     * 只用于显式 fail-closed 测试。不存在“原地排空”平台模式：UNSUPPORTED 与 FAILED 一样
     * 只让调用方 fail-closed 保留数据。
     */
    internal var dirSyncForTest: ((File) -> DirSyncResult?)? = null

    /**
     * 每进程 spool 目录项持久确认标记（P1-1 终审）：进程内首次成功完成 bootstrap gate
     * （[ensureDirectoryDurabilityConfirmed]）后为 true；初始 false，进程重启即清零
     * （测试经 [clearPendingStateForTest]/[resetExecutorsForTest] 模拟）。P1-1 修复：
     * 任一 spool 目录项变更（新建/rename/delete，含元数据严格发布）后的目录 sync 非 OK，
     * 或删除开始/重建 mkdir 失败，都**立即**重新置 false（统一经 [requireSpoolDirSync]），
     * 下一次使用前必须先重新确认——绝不带着“已确认”内存标记声称 durable。**仅进程内
     * 有效**：上一进程可见但未确认的目录项在本进程重新提交，未声明 durable 的事件允许
     * 丢失，属 append 契约内。
     */
    @Volatile
    internal var directoryDurabilityConfirmedThisProcess = false

    /**
     * 测试注入缝（P2）：返回 null 走真实 `Files.copy`；返回 false 模拟 copy 中途失败（seam
     * 可在回调内先写入部分目标字节，发布路径必须按 identity 确认后隔离/删除/tombstone）；
     * 返回 true 模拟 copy 成功（seam 自行写入目标内容）。
     */
    internal var sealCopyForTest: ((File, File) -> Boolean?)? = null

    /** 测试注入缝：返回 null 走真实原子 move；返回 false 模拟 ack prepare/rollback 失败。 */
    internal var ackAtomicMoveForTest: ((File, File) -> Boolean?)? = null

    /** 测试注入缝：返回 true 时对应段原始字节读取失败 → 身份校验 UNREADABLE / drain 中止（P1-2）。 */
    internal var segmentReadErrorForTest: ((File) -> Boolean)? = null

    /**
     * 测试注入缝：安全关键路径的目录枚举（默认行为与 [File.listFiles] 完全一致）。测试可按
     * 目录返回 null 模拟枚举失败——依赖枚举完整性的安全判定（mapping 全有或全无、trash 空
     * 目录判定、UNCOMMITTED 身份扫描、sealed 队列/待处理判定、seal 编号选择）据此显式
     * fail-closed，绝不把失败当空目录、空队列或编号 1。仅用于这些安全路径，不影响普通目录
     * 枚举（容量扫描等）。
     */
    internal var directoryListingForTest: ((File) -> Array<File>?)? = null

    /** tombstone 写入结果：容量满 ≠ 写失败（容量满时跳过该段继续健康，写失败才退避重试）。 */

    /**
     * 身份判定（P1-1）只允许使用**实时**从原始字节计算的 SHA-256：length+mtime 不足以
     * 区分同名同长同 mtime 的不同内容，任何身份缓存复用旧 SHA 都会让 cleanup/ack 误删或
     * 隔离健康段。因此这里没有任何身份哈希缓存——每个破坏性决策（skip/delete/rename/ack）
     * 都现场 hash 文件原始字节（单段 ≤4MiB，成本可接受）。
     */

    internal fun newWriterExecutor() = SpoolWriter.newDrainExecutor()

    internal fun newInsertExecutor() = SpoolWriter.newInsertExecutor()

    internal fun newDatabaseExecutor() = SpoolWriter.newDatabaseExecutor()

    /**
     * Append a complete immutable event. true means an fsync-backed durable copy exists.
     * 受管失败集合（tombstone entries）或证据区到达硬上限时，在持锁下明确抛
     * [TokenStatsPersistenceException]：不能继续产生更多段，绝不返回伪 durable。
     *
     * P1 终审：目录 fsync（[syncDir]）的 OK 是返回 durable 的唯一前提。首次创建 spool
     * 目录时先同步父目录（filesDir）目录项、再同步新目录本身；首次创建 active 文件时写
     * +fd.sync 后必须同步 spool 目录确认目录项。任一非 OK 都 fail-closed（返回 false，
     * 内容保留、可重试，但绝不声明 durable）。
     *
     * P1-1 终审：每进程首次使用 spool 前先过 durable bootstrap gate
     * （[ensureDirectoryDurabilityConfirmed]）——若 spool 目录已存在（无论本进程还是
     * **上一进程**创建），先 sync filesDir 确认 spool 目录项、再 sync spool 目录确认
     * active/metadata 等可见目录项；两者 OK 前绝不写新行或返回 durable。上一进程已可见
     * 但未确认的目录项在本进程重新提交，绝不把两个事件混为一次成功。
     *
     * P1 终审（request/session fencing）：写 spool 前先验证请求开始捕获的
     * [sessionEpoch] 仍等于当前 [restoreEpoch] 且 [acceptingEventsThisProcess] 为 true
     * （恢复屏障开始时原子递增 epoch 使所有旧请求失效；恢复替换开始后本进程不再接受任何
     * 事件直至重启）。任一不满足即明确失败（返回 false，调用方抛
     * [com.ai.assistance.operit.api.chat.llmprovider.TokenStatsPersistenceException]），
     * 绝不写入可能已被恢复替换的 spool。默认 [sessionEpoch] 为调用时刻捕获（直接 spool
     * 写入的请求边界即调用本身）；生产请求边界经 [TokenStatRequestContext.sessionEpoch]
     * 在请求开始时显式捕获。
     */
    suspend fun append(
        context: Context,
        line: String,
        eventId: String,
        sessionEpoch: Long = captureRestoreEpoch(),
    ): Boolean =
        lifecycleMutex.withLock {
            val appContext = context.applicationContext
            // P1 终审：restore fencing 必须在任何目录创建/容量检查/写入之前判定——旧请求
            // 绝不能碰（可能已被恢复替换的）spool，也绝不能把事件写进新 DB。
            if (!fenceAcceptsRestore(sessionEpoch)) {
                logE(
                    "statistics append rejected after restore barrier: eventId=$eventId, " +
                        "epoch=$sessionEpoch, accepting=$acceptingEventsThisProcess",
                )
                return@withLock false
            }
            try {
                val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_LINE_BYTES) {
                    logE("statistics event exceeds durable line limit: eventId=$eventId, bytes=${bytes.size}")
                    return@withLock false
                }
                val dir = spoolDir(appContext)
                // P1-1 终审：bootstrap gate——本进程首次使用前必须先确认既有 spool 目录项
                // 持久（上进程可见未确认的目录项在此重新提交）；失败 fail-closed。
                if (!ensureDirectoryDurabilityConfirmed(appContext, dir)) {
                    logE(
                        "statistics spool directory durability unconfirmed; refusing append: eventId=$eventId",
                    )
                    return@withLock false
                }
                if (managedFailureCapacityExceeded(appContext)) {
                    logE("statistics managed-failure capacity exhausted; refusing new durable event: eventId=$eventId")
                    throw TokenStatsPersistenceException(
                        "token statistics persistence capacity exhausted: managed failure set is full",
                    )
                }
                // P1 终审：首次创建 spool 目录必须确认目录项持久才允许声称 durable。
                // 创建成功后先同步父目录（filesDir，系统预存）的目录项，再同步新目录本身；
                // 任一非 OK 本次 append 明确失败（已创建的目录可保留供重试，但未声明 durable，
                // 且 [directoryDurabilityConfirmedThisProcess] 保持 false，下一次 append 由
                // bootstrap gate 重新确认）。
                val dirCreated = !dir.isDirectory
                if (dirCreated) {
                    if (!dir.mkdirs()) {
                        // P1-1 终审修复：创建失败立即失效 gate——已确认的目录项状态不再
                        // 可靠，下一次使用必须先重新确认，绝不带着“已确认”标记继续。
                        directoryDurabilityConfirmedThisProcess = false
                        logE("statistics spool directory cannot be created: ${dir.absolutePath}")
                        return@withLock false
                    }
                    val parent = dir.parentFile
                    if (parent == null || !requireSpoolDirSync(parent, dir)) {
                        logE(
                            "statistics spool directory creation is not durable; " +
                                "directory retained for retry: ${dir.absolutePath}",
                        )
                        return@withLock false
                    }
                    directoryDurabilityConfirmedThisProcess = true
                }
                val active = File(dir, ACTIVE_FILE_NAME)
                // P1-8：active 非空时先恢复可能的 seal 崩溃窗口重复（硬链接或 copy 回退的
                // copy+delete 窗口）。不消除同 inode 重复就追加会把已 seal 段一起改写；
                // 不消除 copy 窗口重复会让同一内容被排空两次。恢复失败 fail-closed——
                // 绝不带着“可能还有重复”的状态写入。
                if (active.isFile && active.length() > 0L && !recoverSealDuplicates(dir, active)) {
                    logE("statistics spool seal recovery failed; refusing append: eventId=$eventId")
                    return@withLock false
                }
                // P1-1 总硬上限：数据准入上限 = 总上限 − 元数据预留。全部管理文件实际字节
                // （递归含 ack trash 子目录，含元数据 canonical 与 sidecar/tmp 残留）+ 本次行
                // 超过即明确拒绝且不发布任何新文件（seal 只是同目录改名，不新增字节；空 active
                // 计入 0 字节）。持有 lifecycleMutex，与并发 seal/drain 互斥，扫描结果即一致性快照。
                // P1 终审：不尝试任何“排空换容量”回退——容量超限就是明确拒绝。
                val cap = totalSpoolMaxBytesForTest ?: TOTAL_SPOOL_MAX_BYTES
                if (totalSpoolBytes(dir, cap) + bytes.size > dataAdmissionMaxBytes(cap)) {
                    logE("statistics spool total size cap exceeded; refusing new durable event: eventId=$eventId")
                    throw TokenStatsPersistenceException(
                        "token statistics persistence capacity exhausted: total spool size cap reached",
                    )
                }
                if (active.isFile && active.length() > 0L && !activeEndsWithLineBreak(active)) {
                    // A crash mid-write leaves a partial line without a trailing newline. Appending
                    // here would splice the healthy event onto the broken tail and quarantine the
                    // combined segment, losing a durable healthy event. Seal the incomplete tail as
                    // evidence first (the drain quarantines the partial line), then write clean.
                    if (!sealActive(appContext, dir)) return@withLock false
                }
                if (active.isFile && active.length() + bytes.size > MAX_SEGMENT_BYTES) {
                    // 段超限：先封段（发布持久化协议见 [sealActive]），失败则明确持久化失败
                    // 但 active 保留。
                    if (!sealActive(appContext, dir)) return@withLock false
                }
                // P1 终审：append 前记录 active 是否已存在。首次创建时写+fd.sync 之后必须
                // 同步 spool 目录确认目录项；非 OK 则本次不返回 durable（内容保留，且
                // [directoryDurabilityConfirmedThisProcess] 复位——下一次 append 由 bootstrap
                // gate 重新确认目录项后才写入新行，绝不把两事件混为一次成功）。
                val activeExisted = active.isFile
                FileOutputStream(File(dir, ACTIVE_FILE_NAME), true).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                if (!activeExisted && !requireSpoolDirSync(dir)) {
                    logE(
                        "statistics spool first active creation is not durable; " +
                            "content retained: eventId=$eventId",
                    )
                    return@withLock false
                }
                synchronized(stateLock) {
                    insertionWaiters[eventId] = CompletableDeferred()
                }
                scheduleDrain(appContext)
                true
            } catch (e: TokenStatsPersistenceException) {
                throw e
            } catch (e: Exception) {
                logE("statistics durable append failed: eventId=$eventId", e)
                false
            }
        }

    /**
     * Optional visibility wait; durability already succeeded before this is called.
     *
     * A restore may invalidate this waiter while the caller's own coroutine is still active
     * (callers run under [kotlinx.coroutines.NonCancellable]). That is internal invalidation, not
     * a persistence failure and not a coroutine cancellation: it must never escape over the
     * caller's primary model outcome. Only a cancellation of the caller coroutine itself is
     * rethrown.
     */
    suspend fun awaitRoomVisibility(eventId: String, timeoutMs: Long) {
        val waiter = synchronized(stateLock) { insertionWaiters[eventId] } ?: return
        try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } catch (e: CancellationException) {
            // Throws only if the caller coroutine itself was cancelled; under NonCancellable this
            // is a no-op, so a restore-invalidated waiter simply returns (durability was already
            // fsynced and restore semantics supersede visibility).
            currentCoroutineContext().ensureActive()
        } finally {
            synchronized(stateLock) {
                if (insertionWaiters[eventId] === waiter) insertionWaiters.remove(eventId)
            }
        }
    }

    fun replay(context: Context) = scheduleDrain(context.applicationContext)


    /**
     * Raw snapshot barrier. Export uses [drainBefore] and checkpoints inside [block]; restore uses
     * [clearAfter] so old workers are invalidated before replacement and old files are verified
     * gone before the restore can succeed.
     *
     * Isolation guarantee (P1-2): after the drain phase the barrier enters an exclusive state that
     * atomically rejects any new insert registration, then waits a hard-bounded time for every
     * already-registered (in-flight, inside Room) insert to finish. On timeout it throws
     * [IOException] BEFORE [block] runs, so no checkpoint or file replacement can overlap a live
     * old insert; the durable spool is untouched and a later process restart can retry. [block]
     * itself is only reached once the registry is provably empty.
     *
     * Request/session fencing (P1 终审): a restore barrier (clearAfter=true) additionally
     * atomically increments [restoreEpoch] at its start, invalidating every in-flight
     * provider/stream request that captured the previous epoch (their [append] is explicitly
     * rejected, never writing the replaced spool/Room). Right before [block] runs (replacement
     * starts) the process stops accepting ALL statistics events ([acceptingEventsThisProcess] =
     * false) until restart. State machine: failure BEFORE [block] (drain/quiesce/bootstrap)
     * leaves accepting enabled — new requests continue; failure after [block] started keeps it
     * disabled — the process must restart.
     */
    suspend fun <T> withExclusiveSnapshotAccess(
        context: Context,
        drainBefore: Boolean,
        clearAfter: Boolean = false,
        block: suspend () -> T,
    ): T = withExclusiveSnapshotAccessInternal(
        context = context,
        drainBefore = drainBefore,
        clearAfter = clearAfter,
        deferredRestoreCommit = null,
        block = block,
    )

    /**
     * Raw restore two-phase barrier. [prepareBeforeCommit] may do fallible, non-replacement work;
     * [commitReplacement] must persist the external REPLACING state. Request fencing changes only
     * after that commit succeeds, and before [block] closes stores or replaces any directory.
     */
    internal suspend fun <T> withExclusiveRestoreAccess(
        context: Context,
        prepareBeforeCommit: suspend () -> Unit,
        commitReplacement: suspend () -> Unit,
        block: suspend () -> T,
    ): T = withExclusiveSnapshotAccessInternal(
        context = context,
        drainBefore = false,
        clearAfter = true,
        deferredRestoreCommit = {
            prepareBeforeCommit()
            commitReplacement()
        },
        block = block,
    )

    /**
     * Serialize request admission with the restore lifecycle lock. The acceptance decision,
     * restore epoch capture and caller-supplied Room identity transaction form one critical
     * section, so restore cannot close/replace stores after a check but before that transaction
     * registers the request identity.
     */
    internal suspend fun <T> withRequestAdmission(block: suspend () -> T): Pair<T, Long> =
        lifecycleMutex.withLock {
            val epoch = synchronized(stateLock) {
                if (acceptingEventsThisProcess) restoreEpoch else null
            } ?: throw TokenStatsPersistenceException(
                "Token statistics are not accepting new events until the app restarts after a restore",
            )
            block() to epoch
        }



    /**
     * 请求开始时同步捕获当前 restore epoch（P1 终审）：纯内存读取，无需 Room。请求在
     * 收尾 [append] 时按该值验证未被恢复屏障取代。
     */
    fun captureRestoreEpoch(): Long = synchronized(stateLock) { restoreEpoch }

    /** 本进程是否仍接受新的统计事件/请求（恢复替换开始后为 false，直到进程重启）。 */
    fun isAcceptingEvents(): Boolean = synchronized(stateLock) { acceptingEventsThisProcess }



    /**
     * Evidence management is explicit: callers can export these files, then acknowledge deletion.
     *  摘要与 tombstone manifest 是有界元数据（不计入完整证据硬 cap，导出时单独带上）；
     *  两者的 `.new`/`.bak`/`.tmp*` 崩溃安全 sidecar 也不算证据，避免误导出与重复计容。
     *  受管失败段（P1-3）：manifest 中仍存在且 identity 匹配的原损坏 sealed 文件必须作为
     *  managed evidence 参与容量、UI 计数/字节、导出与 ack 删除，绝不隐藏。
     *
     *  P1-3：非空 ack trash 目录（未完成删除事务）也作为受管证据返回（追加在文件列表之后）——
     *  有效 UNCOMMITTED 的 trash 会被维护优先自动回滚、不常驻；状态缺失/损坏或回滚长期失败
     *  的 trash 必须可被 UI 计数/导出/确认删除，绝不隐藏。字节计数需配合 [stuckAckTrashBytes]
     *  （目录的 [File.length] 恒为 0）。
     *
     *  P1-1：公开入口统一持 [lifecycleMutex]——sidecar 恢复（read 会把 `.new`/`.bak` 改名回
     *  canonical）与 append/drain 的容量扫描共享同一把锁，绝不与容量投影竞态。内部调用
     *  （已在锁内）必须使用 [quarantineEvidenceLocked]，避免重入。
     *
     *  P1-6：证据枚举失败（quarantine 区或 stuck ack trash）时抛 [IOException]（fail-closed，
     *  见 [stuckAckTrashEvidenceLocked]/[quarantineAreaFiles]）——调用方必须按失败处理，
     *  绝不能当作“0 证据”诱导删除。
     */
    suspend fun quarantineEvidence(context: Context): List<File> =
        lifecycleMutex.withLock {
            quarantineEvidenceLocked(context) + stuckAckTrashEvidenceLocked(context)
        }

    /**
     * P1-3：非空 ack trash 目录（未完成删除事务）——维护无法（或尚未）自动解决的 stuck
     * 证据，等待用户在 UI 中导出/确认删除。空目录不算（stage 前的崩溃窗口，维护会删除）。
     * 只接受真实普通目录，排除符号链接。
     *
     * P1-6：枚举失败即抛 [IOException]（fail-closed）：根目录或任一 trash 子目录的枚举
     * 失败绝不能当作“没有 trash”/“空目录”返回——否则 export 会在遗漏全部 stuck 证据时
     * 仍报告成功，UI 也会收到误导性的空列表。只有枚举**成功**且目录为空才忽略。
     */
    suspend fun stuckAckTrashEvidence(context: Context): List<File> =
        lifecycleMutex.withLock { stuckAckTrashEvidenceLocked(context) }

    /** P1-3：全部 stuck ack trash 目录的实际字节总和（递归、NOFOLLOW、只计普通文件）。 */
    suspend fun stuckAckTrashBytes(context: Context): Long =
        lifecycleMutex.withLock {
            stuckAckTrashEvidenceLocked(context).sumOf { trash ->
                totalSpoolBytes(trash, Long.MAX_VALUE)
            }
        }





    /**
     * Structured summary of over-cap corrupt segments (P2-1). Within [MAX_QUARANTINE_BYTES] the
     * full evidence is preserved; each new segment that would exceed the cap is replaced by one
     * bounded rolling summary record (count/hash/bytes/time, never content) and removed, so the
     * quarantine area has a hard disk bound while healthy drains continue.
     */
    data class QuarantineSummaryInfo(
        val recordCount: Int,
        val summaryBytes: Long,
    )

    /**
     * 崩溃安全读取摘要（P1-1）：经 [AtomicRestoreMarkerStore] 恢复旧/新完整值后统计，
     * 任意中断后得到的都是完整旧或完整新内容，绝不截断。公开入口持 [lifecycleMutex]
     * （sidecar 恢复与容量扫描互斥，P1-1），内部调用使用 [quarantineSummaryInfoLocked]。
     * 读取失败（非测试注入的异常路径）返回 null：纯展示信息，不参与容量/维护判定。
     */
    suspend fun quarantineSummaryInfo(context: Context): QuarantineSummaryInfo? =
        lifecycleMutex.withLock { quarantineSummaryInfoLocked(context) }





    /**
     * Copy evidence (and the bounded over-cap summary and tombstone manifest) for support/export.
     *  Deletion still requires a separate acknowledged call. File I/O always runs on [ioDispatcher] (P2-2).
     *  摘要/manifest 先经 [AtomicRestoreMarkerStore.read] 恢复 canonical（P2-2：崩溃窗口里
     *  canonical 可能缺失、内容只在 `.new`/`.bak` sidecar），导出内容绝不遗漏元数据；
     *  sidecar 本身不直接导出。受管失败段（P1-3）以原文件名导出并附 manifest 供身份核对。
     */
    suspend fun exportQuarantineEvidence(context: Context, destinationDir: File): List<File> =
        lifecycleMutex.withLock {
            withContext(ioDispatcher) {
                // P2：导出目标必须是专用空目录——拒绝写入非空目录（上一次导出的残留
                // 绝不混入/冒充本次结果，也绝不删除目录中用户自己的文件）。UI 每次导出
                // 创建唯一子目录，并在失败/取消时只清理该唯一目录。
                if (destinationDir.isFile) {
                    throw IOException("quarantine export destination is not a directory: ${destinationDir.absolutePath}")
                }
                if (destinationDir.isDirectory) {
                    // P1-6 fail-closed：无法枚举目标目录内容时绝不能当作“空目录”继续导出——
                    // 未知内容可能与本次结果混入，也无法验证“专用空目录”前提。
                    val existing = destinationDir.listFiles()
                    if (existing == null) {
                        throw IOException(
                            "cannot enumerate quarantine export destination: ${destinationDir.absolutePath}",
                        )
                    }
                    if (existing.isNotEmpty()) {
                        throw IOException("quarantine export destination is not empty: ${destinationDir.absolutePath}")
                    }
                }
                if (!destinationDir.exists() && !destinationDir.mkdirs()) {
                    throw IOException("cannot create quarantine export directory: ${destinationDir.absolutePath}")
                }
                val spool = spoolDir(context.applicationContext)
                val summaryContent = try {
                    readMetadata(summaryStore(File(spool, QUARANTINE_SUMMARY_NAME)), File(spool, QUARANTINE_SUMMARY_NAME))
                } catch (e: Exception) {
                    logE("statistics quarantine summary read failed", e)
                    null
                }
                // P1-2 fail-closed：manifest 不可读则整个导出失败——受管证据必须以可追溯身份
                // 随导出提供，绝不静默导出缺失 manifest 的证据集。
                val manifestContent =
                    readMetadata(summaryStore(File(spool, TOMBSTONE_MANIFEST_NAME)), File(spool, TOMBSTONE_MANIFEST_NAME))
                val exported = mutableListOf<File>()
                quarantineEvidenceLocked(context).forEach { evidence ->
                    val target = File(destinationDir, evidence.name)
                    evidence.inputStream().use { input ->
                        FileOutputStream(target, false).use { output ->
                            input.copyTo(output)
                            output.fd.sync()
                        }
                    }
                    exported += target
                }
                summaryContent?.let { content ->
                    val target = File(destinationDir, QUARANTINE_SUMMARY_NAME)
                    FileOutputStream(target, false).use { output ->
                        output.write(content.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    exported += target
                }
                manifestContent?.let { content ->
                    val target = File(destinationDir, TOMBSTONE_MANIFEST_NAME)
                    FileOutputStream(target, false).use { output ->
                        output.write(content.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    exported += target
                }
                // P1-3：未完成删除事务（非空 ack trash）复制到唯一子目录，包含状态文件与
                // sidecar。文件名做单层路径校验（防穿越），只接受普通文件、拒绝符号链接；
                // 任一文件不合法/复制失败 → 整个导出失败（fail-closed，绝不部分导出冒充完整）。
                stuckAckTrashEvidenceLocked(context).forEach { trash ->
                    val targetDir = File(destinationDir, trash.name)
                    if (!targetDir.mkdir()) {
                        throw IOException(
                            "cannot create ack trash export directory: ${trash.name}",
                        )
                    }
                    // P1-5：trash 枚举失败 → 整个导出明确失败（绝不部分导出冒充完整）。
                    // P1-6：枚举走统一 seam（[listDir]），与 stuck 证据筛选一致。
                    val trashFiles = listDir(trash)
                        ?: throw IOException(
                            "cannot enumerate ack trash during export: ${trash.name}",
                        )
                    trashFiles.forEach { file ->
                        if (Files.isSymbolicLink(file.toPath()) ||
                            !Files.isRegularFile(
                                file.toPath(),
                                java.nio.file.LinkOption.NOFOLLOW_LINKS,
                            ) ||
                            !isSafeTrashName(trash, file.name)
                        ) {
                            throw IOException(
                                "unsafe file inside ack trash during export: ${file.name}",
                            )
                        }
                        val target = File(targetDir, file.name)
                        file.inputStream().use { input ->
                            FileOutputStream(target, false).use { output ->
                                input.copyTo(output)
                                output.fd.sync()
                            }
                        }
                    }
                    exported += targetDir
                }
                exported
            }
        }

    /**
     * File I/O always runs on [ioDispatcher] (P2-2); the bounded rolling summary is kept unless
     *  the caller explicitly acknowledges it with [deleteSummary]. 受管失败段（P1-3）：
     *  按 identity 删除原损坏文件并移除对应 manifest 记录；文件已消失只移除记录；身份不匹配
     *  视为陈旧记录绝不删新身份文件。删除失败保留记录并抛错（UI 反馈失败，不声称全部成功）。
     *
     *  严格两阶段（reviewer P1）：Phase 1 在持锁下只读预检——先恢复/读取 manifest 并枚举本次
     *  全部目标（quarantine area + managed original），对所有 managed identity 现场 fresh 验证；
     *  任一读/UNREADABLE/元数据错误都使整个操作失败：零删除、零 manifest 写。MISMATCH 只视为
     *  陈旧记录并计划移除 manifest 条目，绝不删除同名新文件。所有目标名先做路径归属预检
     *  （防目录穿越）。Phase 2 预检全部通过后才动手：先把全部待删文件原子 rename 进本轮唯一
     *  trash 目录（同 filesystem 的可回滚 prepare），任一 rename 失败则把已 rename 文件移回
     *  原位、manifest 不改并报错；全部 rename 成功后原子写入 trash 状态文件（P1-2：
     *  UNCOMMITTED + mapping：原名 → trash 名 + bytes + sha256，只含已 stage 文件；P1-1：
     *  写入预算先投影，超限有界失败并回滚），再原子重写 manifest 移除对应条目，然后原子
     *  改状态为 COMMITTED，最后删除 trash（删除失败只记录——证据逻辑已 ack，维护入口按状态
     *  补删/回滚：只有显式 COMMITTED 才补删，UNCOMMITTED 一律回滚，绝不误删未提交的证据）。
     *  trash 占用计入递归总容量（P1-1），占用绝不隐藏。
     *
     *  P1-3：`names` 也可包含非空 ack trash 目录名（StuckAckEvidence）——用户显式确认后删除
     *  整个 trash（无需 mapping 完整）；仅接受 spool 根内匹配 [ACK_TRASH_PREFIX] 的真实普通
     *  目录（NOFOLLOW、拒绝符号链接）。全部名字都是 trash 目录时跳过 manifest 读取（其完整性
     *  与 trash 删除无关）。trash 删除放在文件事务完成后，文件侧失败时 trash 保持原样。
     */
    suspend fun acknowledgeAndDeleteQuarantine(
        context: Context,
        names: Set<String>,
        deleteSummary: Boolean = false,
    ) =
        lifecycleMutex.withLock {
            withContext(ioDispatcher) {
                val dir = spoolDir(context.applicationContext)
                // ── Phase 1：只读预检（任何失败 → 零删除、零 manifest 写）────────────────
                names.forEach { requireSafeEvidenceName(dir, it) }
                // P1-3：stuck ack trash 目录（未完成删除事务）的显式确认删除。名字必须是
                // spool 根内匹配 ACK_TRASH_PREFIX 的真实普通目录（NOFOLLOW、拒绝符号链接），
                // 全部名字都是 trash 目录时无需依赖 manifest（用户已显式授权删除）。
                val trashDirs = names
                    .filter { it.startsWith(ACK_TRASH_PREFIX) && File(dir, it).isDirectory }
                    .map { it to File(dir, it) }
                trashDirs.forEach { (_, trash) -> requireAckTrashDirForDelete(dir, trash) }
                if (trashDirs.size == names.size) {
                    trashDirs.forEach { (_, trash) ->
                        if (!(spoolDeleteForTest?.invoke(trash) ?: deleteAckTrashDirNoFollow(trash))) {
                            throw IOException(
                                "cannot delete acknowledged stuck trash: ${trash.name}",
                            )
                        }
                        // P1-3 终审：删除是目录项变更——sync 非 OK 绝不报告成功（保留状态、
                        // 失败；重试幂等：trash 已可见删除则下次无操作，未删则由状态机/直接
                        // 删除再处置）。P1-1：非 OK 同时失效 bootstrap gate。
                        if (!requireSpoolDirSync(dir)) {
                            throw IOException(
                                "acknowledged stuck trash deletion not durable: ${trash.name}",
                            )
                        }
                    }
                    if (deleteSummary) deleteQuarantineSummaryLocked(context)
                    return@withContext
                }
                val manifestFile = File(dir, TOMBSTONE_MANIFEST_NAME)
                // P1-3：不设 canonical isFile 前置——仅 `.new`/`.bak` sidecar 存在时
                // read() 会先恢复 canonical 再返回，ack 才能按身份删除受管段。
                // P1-2 fail-closed：manifest 不可读则 ack 明确失败（不删任何文件）。
                val rawLines = readMetadata(summaryStore(manifestFile), manifestFile)
                    ?.lineSequence()?.filter { it.isNotBlank() }?.toList()
                    ?: emptyList()
                val entries = rawLines.map { rawLine ->
                    parseTombstoneLine(rawLine)
                        ?: throw IOException("invalid tombstone manifest entry; refusing ack")
                }
                entries.forEach { requireSafeEvidenceName(dir, it.file) }
                val quarantineFiles = quarantineAreaFiles(dir).filter { it.name in names }
                quarantineFiles.forEach { requireManageableEvidenceFile(dir, it) }
                val removeRawLines = mutableListOf<String>()
                val managedFiles = mutableListOf<File>()
                for ((index, rawLine) in rawLines.withIndex()) {
                    val entry = entries[index]
                    if (entry.file !in names) continue
                    val file = File(dir, entry.file)
                    when {
                        !file.exists() -> {
                            // 物理消失：只计划移除陈旧记录
                            removeRawLines += rawLine
                        }
                        else -> when (tombstoneIdentityCheck(entry, file)) {
                            // P1-2：身份不可校验（UNREADABLE）时 ack 绝不能成功——既不能删
                            // 也不能当陈旧记录移除，保留 manifest，让维护/用户稍后重试。
                            IdentityCheck.UNREADABLE -> {
                                throw IOException(
                                    "cannot verify identity of acknowledged managed evidence; " +
                                        "refusing ack: ${entry.file}",
                                )
                            }
                            IdentityCheck.MISMATCH -> {
                                // 陈旧记录：计划移除 manifest 条目，但绝不删除同名新文件
                                removeRawLines += rawLine
                            }
                            IdentityCheck.MATCH -> {
                                requireManageableEvidenceFile(dir, file)
                                removeRawLines += rawLine
                                managedFiles += file
                            }
                        }
                    }
                }
                // 同一文件同时被 quarantine area 与 managed 枚举命中时只处理一次
                val allFiles = (quarantineFiles + managedFiles).distinct()
                if (allFiles.isEmpty() && removeRawLines.isEmpty() && trashDirs.isEmpty()) {
                    return@withContext
                }

                // ── Phase 2：预检全通过后的事务化删除──────────────────────────────────
                // 文件删除中途失败无法事务化，因此先全部 rename 进本轮唯一 trash 目录（同卷
                // 原子、可回滚 prepare）；全部成功后才重写 manifest，最后删除 trash。
                if (allFiles.isNotEmpty()) {
                    val trashDir =
                        File(dir, "$ACK_TRASH_PREFIX${UUID.randomUUID().toString().replace("-", "")}")
                    if (!trashDir.mkdir()) {
                        throw IOException(
                            "cannot create acknowledged-deletion trash directory: ${trashDir.name}",
                        )
                    }
                    // P1-3 终审：trash 目录创建是目录项变更——sync 非 OK 明确失败（尚未
                    // stage 任何证据，空 trash 由维护入口按“无状态空目录”清理，无证据损失）。
                    if (!requireSpoolDirSync(dir)) {
                        throw IOException(
                            "ack trash directory creation not durable: ${trashDir.name}",
                        )
                    }
                    val staged = mutableListOf<Pair<File, File>>()
                    try {
                        for (file in allFiles) {
                            val target = File(trashDir, file.name)
                            if (target.exists() || !atomicMoveForAck(file, target)) {
                                throw IOException(
                                    "cannot stage acknowledged evidence for deletion: ${file.name}",
                                )
                            }
                            staged += file to target
                            // P1-3 终审：证据移动跨 spool 根与 trash 两个目录——两者目录项
                            // 都必须确认持久；非 OK 走回滚/状态补写协议（见 catch），绝不
                            // 带着未确认状态继续 stage 或声称成功。P1-1：非 OK 同时失效 gate。
                            if (!requireSpoolDirSync(dir, trashDir)) {
                                throw IOException(
                                    "ack staging not durable: ${file.name}",
                                )
                            }
                        }
                    } catch (e: Exception) {
                        val rollback = rollbackStagedRenames(staged, trashDir)
                        if (rollback.syncFailed) {
                            logE(
                                "statistics ack rollback directory entries unconfirmed; " +
                                    "keeping UNCOMMITTED state for maintenance: ${trashDir.name}",
                            )
                        }
                        // P1-2：回滚失败（trash 仍持有已 stage 证据）时尽力补写状态
                        // （UNCOMMITTED + mapping 已 stage 文件），使维护入口能按身份回滚；
                        // 回滚成功时 trash 已删除，无需补写。P2：回滚目录项未确认持久
                        // （syncFailed）同样保留 trash 并补写状态，上层失败绝不静默。
                        if (trashDir.exists()) {
                            writeUncommittedTrashState(context, trashDir, staged)
                        }
                        throw e
                    }
                    // P1-2：stage 全部成功后才写状态文件（UNCOMMITTED + mapping，只含已 stage
                    // 文件：原名 → trash 名 + 稳定身份 bytes+sha256）——回滚只需处理真正进过
                    // trash 的文件。P1-1：写入预算先投影（4 槽位最坏副本），超限有界失败并
                    // 回滚已 stage 文件，不写任何正式文件。
                    if (!writeUncommittedTrashState(context, trashDir, staged)) {
                        val rollback = rollbackStagedRenames(staged, trashDir)
                        // P2：回滚目录项未确认持久（syncFailed）时 trash 保留证据——尽力再
                        // 补写一次 UNCOMMITTED 状态（预算仍拒绝时仅记录，trash 由维护/UI 作为
                        // StuckAckEvidence 管理），上层失败绝不静默。
                        if (trashDir.exists()) {
                            writeUncommittedTrashState(context, trashDir, staged)
                        }
                        throw IOException(
                            "ack trash state publish refused: metadata budget over the total cap",
                        )
                    }
                    if (removeRawLines.isNotEmpty()) {
                        val remaining = rawLines.filterNot { it in removeRawLines }
                        val newContent =
                            remaining.joinToString("\n") + if (remaining.isEmpty()) "" else "\n"
                        try {
                            rewriteAckManifestLocked(context, manifestFile, newContent)
                        } catch (e: Exception) {
                            logE("statistics quarantine tombstone manifest rewrite failed", e)
                            rollbackStagedRenames(staged, trashDir)
                            throw IOException(
                                "tombstone manifest rewrite failed after acknowledgment: ${e.message}",
                            )
                        }
                    }
                    // 主 manifest 已发布 → 原子改 COMMITTED。翻转只是同目录内替换小内容（比
                    // UNCOMMITTED 状态文件更小），最坏瞬态 ≤ 当前总量（已在预算内），无需另行
                    // 投影。P1-3 终审：状态翻转经严格目录同步 store——write 只有目录项确认
                    // 持久才成功；失败绝不报告成功（抛 IOException），trash 保持 UNCOMMITTED
                    // + mapping 由维护按状态机回滚（证据回到原路径后被扫描器重新隔离，ack
                    // 视失败但不丢证据）。
                    try {
                        summaryStore(File(trashDir, ACK_TRASH_STATE_FILE_NAME))
                            .write(ACK_STATE_COMMITTED + "\n")
                    } catch (e: Exception) {
                        logE(
                            "statistics ack trash commit flip failed; retaining trash for rollback",
                            e,
                        )
                        throw IOException("ack trash commit failed: ${e.message}")
                    }
                    // 证据逻辑已 ack：trash 删除失败只记录（COMMITTED 状态保留，下次维护/ack
                    // 有界补删）；P1-3 终审：删除**成功**后目录项必须确认持久，非 OK 绝不报告
                    // 成功（失败；重试幂等——trash 已可见删除则下次 ack 无操作）。
                    if (!(spoolDeleteForTest?.invoke(trashDir) ?: deleteAckTrashDirNoFollow(trashDir))) {
                        logE(
                            "statistics ack trash cleanup deferred; acknowledged evidence is already " +
                                "removed from the manifest: ${trashDir.name}",
                        )
                    } else if (!requireSpoolDirSync(dir)) {
                        throw IOException(
                            "ack trash deletion not durable: ${trashDir.name}",
                        )
                    }
                } else if (removeRawLines.isNotEmpty()) {
                    // 只有陈旧 manifest 记录要移除：没有任何文件需要 stage/删除，直接重写
                    // manifest（P1-1 预算投影 + 注入检查），失败抛错；无 trash 参与。
                    val remaining = rawLines.filterNot { it in removeRawLines }
                    val newContent =
                        remaining.joinToString("\n") + if (remaining.isEmpty()) "" else "\n"
                    try {
                        rewriteAckManifestLocked(context, manifestFile, newContent)
                    } catch (e: Exception) {
                        logE("statistics quarantine tombstone manifest rewrite failed", e)
                        throw IOException(
                            "tombstone manifest rewrite failed after acknowledgment: ${e.message}",
                        )
                    }
                }
                // P1-3：stuck trash 的确认删除放在文件事务完成之后——文件侧失败时 trash 保持
                // 原样（一致失败态，用户可重试）；删除使用 NOFOLLOW 遍历，绝不跟随符号链接。
                // P1-3 终审：删除成功后目录项必须确认持久，非 OK 失败（重试幂等）。P1-1：
                // 非 OK 同时失效 gate。
                for ((_, trash) in trashDirs) {
                    if (!(spoolDeleteForTest?.invoke(trash) ?: deleteAckTrashDirNoFollow(trash))) {
                        throw IOException("cannot delete acknowledged stuck trash: ${trash.name}")
                    }
                    if (!requireSpoolDirSync(dir)) {
                        throw IOException(
                            "acknowledged stuck trash deletion not durable: ${trash.name}",
                        )
                    }
                }
                if (deleteSummary) deleteQuarantineSummaryLocked(context)
            }
        }




    /** P2 终审：ack 回滚的结构化结果。success=false = 有文件未能移回原位（trash 保留为可
     * 重试记录，由维护按 mapping 处置）；syncFailed=true = 存在目录项未确认持久的变更
     * （上层必须失败并保留 UNCOMMITTED/stuck 状态，绝不静默推进）。 */





    /** ack trash mapping 条目（P1-2）：spool 根原名 → trash 内名 + 稳定身份（bytes+sha256）。 */












    /**
     * 可等待的初始 drain（P1 关键链路，启动 readiness 使用）：请求一轮 drain（已有
     * worker 在跑则请求被保留并由同一 worker 接管），并挂起直到该轮结束。返回 true =
     * 本轮成功（排空到轮内最后检查点，pre-replay 数据已入 Room）；false = 本轮失败或
     * 超时——**不缓存**：后续调用重新登记并触发新轮（drain 自身另有退避重试）。
     * 并发调用 join 同一轮；不持 [stateLock] 挂起（等待者由 worker 轮末决策点完成）。
     */
    suspend fun awaitInitialDrain(context: Context, timeoutMs: Long): Boolean {
        val appContext = context.applicationContext
        val waiter = synchronized(stateLock) {
            CompletableDeferred<Boolean>().also { initialDrainWaiters += it }
        }
        scheduleDrain(appContext)
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        } finally {
            synchronized(stateLock) {
                initialDrainWaiters.removeAll { it === waiter }
            }
        }
    }








    /** seal 原子发布结果：成功 / 目标已存在（调用方换下一编号）/ 其他失败（终止本轮）。 */









    /**
     * 目录同步结果（P1 终审）：OK 已持久；FAILED 真实失败；UNSUPPORTED 平台明确不支持目录
     * fsync——与 FAILED 一样 fail-closed（**绝不**当作成功继续删除唯一 fsynced active 或
     * 声称 PUBLISHED）。不存在“原地排空”平台模式：生产平台（Android/Linux）支持目录
     * fd fsync，Windows 仅 JVM 测试环境。
     */
    internal enum class DirSyncResult { OK, FAILED, UNSUPPORTED }

    /**
     * 目录 fsync（P1 终审，调用方持 lifecycleMutex）。Android/Linux：`FileChannel.open(dir,
     * READ)` + `force(true)` 即 fsync(2) 目录 fd，持久化目录项（新建/硬链接/删除）。
     * Windows：JDK 无法打开目录句柄（CreateFile 拒绝目录，实测抛 [AccessDeniedException]）
     * ——平台明确不支持，返回 [DirSyncResult.UNSUPPORTED]。
     *
     * 调用方契约（P1 终审）：只有 [DirSyncResult.OK] 才能继续发布/删除；FAILED 与
     * UNSUPPORTED 一律 fail-closed——目录项未确认持久时绝不删除唯一 fsynced active、绝不
     * 声称 durable/PUBLISHED。生产路径不缓存平台能力、不进入任何特殊模式：每次目录项
     * 变更（新建/rename/link/delete）都调用本方法确认。
     */
    internal fun syncDir(dir: File): DirSyncResult {
        val seam = dirSyncForTest
        if (seam != null) return seam(dir) ?: realSyncDir(dir)
        return realSyncDir(dir)
    }

    internal fun realSyncDir(dir: File): DirSyncResult {
        return SpoolFileSystem.syncDirectory(dir, ::logE)
    }

    /**
     * P1-1 终审修复：spool 目录项持久确认的统一入口（调用方持 lifecycleMutex）。任一目录
     * sync 非 OK 立即把 bootstrap gate 标记 [directoryDurabilityConfirmedThisProcess] 置 false
     * ——此后任何声称 durable 前都必须重新确认目录项，绝不带着“已确认”内存标记继续。
     * 所有 spool 目录项变更（新建/rename/delete，含 ack 跨 spool 根与 trash 两个目录）后的
     * 目录 sync 都必须经本入口确认。
     */
    internal fun requireSpoolDirSync(vararg dirs: File): Boolean {
        val ok = dirs.all { syncDir(it) == DirSyncResult.OK }
        if (!ok) directoryDurabilityConfirmedThisProcess = false
        return ok
    }

    /**
     * 文件 fsync（P1 终审）：`FileChannel.force(true)` 持久化数据与元数据；失败返回 false
     * （调用方保留 active、处置目标、返回 FAILED，绝不声称 PUBLISHED）。
     */
    internal fun syncFile(file: File): Boolean {
        val seam = fileSyncForTest
        if (seam != null) return seam(file) ?: realSyncFile(file)
        return realSyncFile(file)
    }

    internal fun realSyncFile(file: File): Boolean = SpoolFileSystem.syncFile(file, ::logE)


    /** 逐字节比较（长度先短路；读失败返回 null，调用方按 fail-closed 处理）。 */
    internal fun contentsEqual(a: File, b: File): Boolean? {
        return SpoolFileSystem.contentsEqual(a, b, ::logE)
    }



    internal fun File.sealIndex(): Long =
        name.removePrefix(SEALED_PREFIX).removeSuffix(SEALED_SUFFIX).toLongOrNull() ?: Long.MAX_VALUE


    internal fun renameForTest(from: File, to: File): Boolean =
        segmentRenameForTest?.invoke(from, to) ?: from.renameTo(to)







    /** 段身份校验结果（P1-2）：读取失败 = UNREADABLE，绝不误判为陈旧而删/隔离/清理。 */

    /**
     * P1-2：稳定身份校验——文件名相同且字节数相同且原始字节 SHA-256 相同才是同一段
     * （MATCH）。bytes/sha256 缺失的旧条目（无身份）永不匹配 → MISMATCH（陈旧记录被清理）。
     * 原始字节读取失败 → UNREADABLE（保留 manifest 条目，调用方跳过或失败，不做破坏性决策）。
     * P1-1：SHA 永远现场从原始字节计算，绝不复用 length+mtime 缓存——同名同长同 mtime
     * 的替换内容必须被识别为不同身份（陈旧记录被清理，健康段绝不删/跳/隔离）。
     */





    /**
     * 未提交 trash 回滚结果（P1-2 终审）：allResolved=false 表示有文件无法恢复（trash 保留
     * 为可重试记录）；syncFailed=true 表示存在目录项未确认持久的变更（本轮必须退避重试）。
     */



    /** P1-4：未提交 ack trash 扫描结果：已知身份 + 是否存在无法完整严格解析/读取的 trash。 */


    internal fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }



    internal fun spoolDir(context: Context) = SpoolFileSystem.spoolDir(context, SPOOL_DIR_NAME)

    /**
     * 安全关键路径的目录枚举统一入口（调用方持 lifecycleMutex）：注入缝为 null 时与
     * [File.listFiles] 完全一致（不影响普通目录枚举）；返回 null 表示枚举失败，调用方必须
     * fail-closed（保留/视为 unknown/中止），绝不把失败当作空目录、空队列或编号 1。
     * 覆盖 sealed 队列/待处理判定、seal 编号选择与 ack/trash 安全路径。
     *
     * 注意：不能用 `seam?.invoke(dir) ?: dir.listFiles()`——注入缝**返回 null** 表示“枚举
     * 失败”，必须原样传递，绝不能回退到真实枚举（否则 fail-closed 注入失效，测试无法覆盖
     * 该失败分支）。
     */
    internal fun listDir(dir: File): Array<File>? {
        return SpoolFileSystem.listDirectory(dir, directoryListingForTest)
    }

    /**
     * 全部 spool 管理文件的实际字节总和（P1-1 修复：递归）：覆盖 spool 根下所有子目录
     * （ack trash 等），只计 regular file，绝不跟随符号链接（NOFOLLOW_LINKS：链接按链接
     * 本身处理，符号链接目录不进入遍历）。总和超过 [cap] 或 Long 溢出时饱和返回 cap+1——
     * 调用方投影必拒绝，无需精确值；目录不存在返回 0；遍历失败按超限处理（fail-closed，
     * 绝不因扫描失败而低估容量）。文件数受总 cap 约束有界，无需维护缓存。
     */
    internal fun totalSpoolBytes(dir: File, cap: Long): Long {
        return SpoolFileSystem.totalBytes(dir, cap)
    }

    /**
     * 数据准入上限（P1-1）= 总上限 − 元数据预留。测试注入更小的总上限时预留同步收缩
     * （至少为数据保留一条完整行 [MAX_LINE_BYTES] 的空间，避免准入区间为负），生产值
     * 恒等于 [METADATA_RESERVE_BYTES]。
     */
    internal fun dataAdmissionMaxBytes(cap: Long): Long {
        return SpoolWriter.dataAdmissionMaxBytes(cap, METADATA_RESERVE_BYTES, MAX_LINE_BYTES)
    }

    /**
     * 元数据发布预算（P1-1，调用方持 lifecycleMutex）：发布 contentBytes 元数据时，最坏
     * 瞬时增量 = [METADATA_COPY_COUNT] × contentBytes（canonical/.new/.bak/tmp 四个槽位可能
     * 短暂同时各持一份完整副本）。投影“实际 [totalSpoolBytes]（递归含 ack trash）+ 该增量”
     * 仍 ≤ 总上限才允许发布，否则调用方有界失败且不写任何正式文件（sidecar 也不写）。spool
     * 内所有元数据读写都持 lifecycleMutex，任意时刻至多一个 AtomicRestoreMarkerStore 写进行中
     * （Atomic tmp 唯一文件并发数 = 1），因此按单写者投影即可证明全部实际字节恒 ≤ 总上限。
     */
    internal fun metadataWriteBudgetExceeded(context: Context, contentBytes: Int): Boolean {
        val cap = totalSpoolMaxBytesForTest ?: TOTAL_SPOOL_MAX_BYTES
        return SpoolWriter.metadataWriteBudgetExceeded(
            currentBytes = totalSpoolBytes(spoolDir(context), cap),
            contentBytes = contentBytes,
            metadataCopyCount = METADATA_COPY_COUNT,
            cap = cap
        )
    }

    internal fun shutdownWriterForTest() = synchronized(stateLock) {
        drainScheduled = false
        writerExecutor.shutdownNow()
    }

    /** Discard wedged worker executors (e.g. an interrupt-ignoring insert) so later tests start clean.
     *  Simulates a process restart: the active-insert registry, visibility waiters, the exclusive
     *  flag, the event-acceptance fence and the bootstrap durability marker are all reset (a wedged
     *  task can never unregister itself; directory durability must be re-confirmed on the next use). */
    internal fun resetExecutorsForTest() = synchronized(stateLock) {
        insertExecutor.shutdownNow()
        databaseExecutor.shutdownNow()
        pendingDaoTask = null
        insertExecutor = newInsertExecutor()
        databaseExecutor = newDatabaseExecutor()
        activeInserts.clear()
        insertionWaiters.values.forEach { it.cancel() }
        insertionWaiters.clear()
        exclusiveBarrierActive = false
        // P1 终审：模拟进程重启——restore 后本进程拒绝事件的状态随重启清除，重新接受
        acceptingEventsThisProcess = true
        // P1-1 终审：模拟进程重启——bootstrap gate 标记清零，下一次使用重新确认目录项
        directoryDurabilityConfirmedThisProcess = false
    }

    internal fun clearPendingStateForTest() = synchronized(stateLock) {
        sessionGeneration += 1L
        drainScheduled = false
        drainRequested = false
        // 未完成的初始 drain 等待者按失败完成（进程重启语义；测试内不应依赖旧轮）
        initialDrainWaiters.forEach { if (it.isActive) it.complete(false) }
        initialDrainWaiters.clear()
        retryDelayMs = RETRY_BACKOFF_BASE_MS
        // P1 终审：逐测试复位 restore fencing 状态（进程内标记绝不跨测试泄漏）
        restoreEpoch = 0L
        acceptingEventsThisProcess = true
        // P1 终审：bootstrap gate 标记逐测试复位（进程内标记绝不跨测试泄漏）
        directoryDurabilityConfirmedThisProcess = false
        resetExecutorsForTest()
    }

    internal fun emergencyQueueSizeForTest(): Int = 0
    internal fun pendingLatchCountForTest(): Int = synchronized(stateLock) { insertionWaiters.size }
    internal fun activeInsertCountForTest(): Int = synchronized(stateLock) { activeInserts.size }
    internal fun drainRequestPendingForTest(): Boolean = synchronized(stateLock) { drainRequested }
    internal fun drainScheduledForTest(): Boolean = synchronized(stateLock) { drainScheduled }
    internal fun initialDrainWaiterCountForTest(): Int = synchronized(stateLock) { initialDrainWaiters.size }

    internal fun logE(message: String, error: Throwable? = null) {
        try {
            if (error == null) AppLogger.e(TAG, message) else AppLogger.e(TAG, message, error)
        } catch (_: Throwable) {
        }
    }
}

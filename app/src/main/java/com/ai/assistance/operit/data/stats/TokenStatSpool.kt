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
    private const val TAG = "TokenStatSpool"
    internal const val SPOOL_DIR_NAME = "token_stats_spool"
    private const val ACTIVE_FILE_NAME = "active.jsonl"
    private const val SEALED_PREFIX = "sealed_"
    private const val SEALED_SUFFIX = ".jsonl"
    private const val QUARANTINE_PREFIX = "quarantine_"

    /**
     * P2：seal copy 回退中途失败的部分目标隔离前缀（`seal_failed_<uuid>`）。隔离文件 scanner
     * 忽略（不匹配 [SEALED_PREFIX]）、计入递归总 cap（占用可见）、由维护入口 [retryPendingCleanup]
     * 清理（active 保留完整内容，隔离副本删除安全，无数据损失）。P2 终审：同时作为**受管失败
     * 发布证据**计入 [quarantineAreaFiles]——长期删除失败时可见（quarantineEvidence/info 字节）、
     * 可导出、可确认删除（ack 按 NOFOLLOW/path 根校验删除并释放容量），绝不无限隐藏占用。
     */
    private const val SEAL_FAILED_PREFIX = "seal_failed_"

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
    private const val ACK_TRASH_PREFIX = "quarantine_ack_trash_"

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
    private const val QUARANTINE_SUMMARY_NAME = "quarantine_summary.jsonl"
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
    private const val TOMBSTONE_MANIFEST_NAME = "quarantine_skip_manifest.jsonl"

    /** 活跃受管失败集合的身份硬上限：超过后新统计 append 明确失败（不能继续产生更多段）。 */
    internal const val MAX_TOMBSTONE_ENTRIES = 64

    /** 受管失败集合的 manifest 文件字节硬上限（64 条约 200B，纯 ASCII，正常远达不到）。 */
    private const val MAX_TOMBSTONE_MANIFEST_BYTES = 64L * 1024

    // ── P1-1 元数据预留（总 cap 证明）────────────────────────────────────────────
    // 有界元数据文件（quarantine summary / tombstone manifest）各有 4 个磁盘槽位：
    // canonical、`.new`、`.bak`、`.tmp<随机>`。原子替换或回退协议的任意中断窗口下四个
    // 槽位都可能同时各持一份完整副本（read 恢复/清理前），因此单份元数据的最坏磁盘
    // 占用 = 4 × 内容硬上限。所有 spool 元数据读写都持 lifecycleMutex（至多一个写进行
    // 中，Atomic tmp 唯一文件并发数 = 1），预留按单写者计算即可覆盖。
    /** 单份有界元数据的磁盘槽位数（canonical + .new + .bak + tmp）。 */
    internal const val METADATA_COPY_COUNT = 4

    /** 有界元数据文件数：quarantine summary + tombstone manifest。 */
    private const val METADATA_FILE_COUNT = 2

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
    private val MAX_MANAGED_BYTES get() = MAX_TOMBSTONE_ENTRIES * MAX_SEGMENT_BYTES

    /** 删除失败的 over-cap 段的诊断去向：完整证据（计入硬 cap，可导出/确认删除）。 */
    private const val PENDING_DELETE_PREFIX = "quarantine_pending_delete_"
    private const val RETRY_BACKOFF_BASE_MS = 1_000L
    private const val RETRY_BACKOFF_CAP_MS = 30_000L
    internal var insertTimeoutMs: Long = 5_000L
    internal var prepareTimeoutMs: Long = 5_000L

    /** 排他快照/恢复等待已登记 insert 全部结束的硬超时；超时则操作明确失败，绝不替换文件。 */
    internal var exclusiveQuiesceTimeoutMs: Long = 5_000L
    private const val QUIESCE_POLL_INTERVAL_MS = 50L

    /** 文件 I/O 调度缝（P2-2）：导出/确认删除的复制、fsync、扫描绝不运行在调用方（Main）线程。 */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val lifecycleMutex = Mutex()
    private val stateLock = Any()

    /**
     * 调度令牌：有 worker 任务已入队/正在运行（用于入队去重）。快照屏障在递增
     * [sessionGeneration] 时同步清空，使旧 generation 的排队 worker 失效。
     */
    private var drainScheduled = false

    /**
     * 未消费的 drain 请求（丢失唤醒修复）：每次 [scheduleDrain] 都在 [stateLock] 下
     * 置位；worker 每轮开始前消费，轮末在同一锁内决定 retire/立即 rerun/失败 backoff。
     * 请求在轮内到达时由同一 worker 接管，绝不依赖下一次外部触发；RejectedExecution
     * 时请求保留、仅释放调度令牌（见 [scheduleDrain]）。
     */
    private var drainRequested = false

    /**
     * [awaitInitialDrain] 的等待者：worker 轮末决策点持 [stateLock] 统一完成并清空；
     * 完成/失败都不保留——下次调用重新登记并触发新轮（失败不缓存，可重试）。
     */
    private val initialDrainWaiters = ArrayList<CompletableDeferred<Boolean>>()

    private var sessionGeneration = 0L
    private var retryDelayMs = RETRY_BACKOFF_BASE_MS
    private var writerExecutor = newWriterExecutor()

    /**
     * 排他快照/恢复进行中：阻止新 insert 登记（与 insert 的登记在同一 critical section 原子判定）。
     */
    private var exclusiveBarrierActive = false

    /** 已通过 fence 且正在 Room 内写入的 insert（eventId -> 提交时 generation）。 */
    private val activeInserts = HashMap<String, Long>()

    /**
     * Request/session fencing epoch（P1 终审）：通用恢复屏障开始时递增；Raw restore 则在
     * 外部 REPLACING 状态成功持久化后、关闭 stores 前原子递增，
     * 使所有在屏障开始前开始（已捕获旧 epoch）的 in-flight provider/stream 请求在收尾
     * [append] 时被明确拒绝——绝不写入可能已被恢复替换的 spool/Room。导出/快照屏障
     * （clearAfter=false）不递增：进行中的请求在导出期间正常收尾。进程内单调递增，
     * 不随普通 [withExclusiveSnapshotAccess] 变化；测试经 [clearPendingStateForTest] 复位。
     */
    private var restoreEpoch = 0L

    /**
     * 本进程是否仍接受统计事件（P1 终审）：恢复屏障的替换开始（[block] 即将执行）时置 false，
     * 直到进程重启（UI 允许稍后重启——此后所有 [append] 与新的跟踪请求被明确拒绝，绝不污染
     * 恢复后的新 DB）。替换前失败（drain/quiesce 阶段抛错）保持 true，新请求可继续。
     * 进程重启（含测试模拟）经 [resetExecutorsForTest]/[clearPendingStateForTest] 复位。
     */
    @Volatile
    private var acceptingEventsThisProcess = true

    /**
     * Dedicated bounded insert worker. Room/SQLite writes can ignore thread interrupts, so the
     * drain never joins this worker; a timed-out task is detached and the durable segment is
     * retried later. The single daemon thread plus one queue slot is the hard bound (P2-1), so a
     * permanently wedged database cannot leak threads or hold the lifecycle lock.
     */
    private var insertExecutor = newInsertExecutor()

    /** Dedicated bounded database-preparation worker with single-flight semantics (P2-1). */
    private var databaseExecutor = newDatabaseExecutor()
    private var pendingDaoTask: FutureTask<TokenStatsDao>? = null
    private val insertionWaiters = HashMap<String, CompletableDeferred<Unit>>()

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
    private var directoryDurabilityConfirmedThisProcess = false

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

    /** 活跃受管失败集合的稳定身份（P1-2）：file+bytes+sha256，三者全匹配才算同一段。 */
    private data class TombstoneEntry(
        val file: String,
        val bytes: Long,
        val sha256: String,
        val overCap: Boolean,
    )

    /** tombstone 写入结果：容量满 ≠ 写失败（容量满时跳过该段继续健康，写失败才退避重试）。 */
    private enum class TombstoneResult { RECORDED, CAPACITY_FULL, FAILED }

    /**
     * 身份判定（P1-1）只允许使用**实时**从原始字节计算的 SHA-256：length+mtime 不足以
     * 区分同名同长同 mtime 的不同内容，任何身份缓存复用旧 SHA 都会让 cleanup/ack 误删或
     * 隔离健康段。因此这里没有任何身份哈希缓存——每个破坏性决策（skip/delete/rename/ack）
     * 都现场 hash 文件原始字节（单段 ≤4MiB，成本可接受）。
     */

    private fun newWriterExecutor() =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "operit-token-stats-writer").apply { isDaemon = true }
        }

    private fun newInsertExecutor() =
        ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(1),
        ) { runnable -> Thread(runnable, "operit-token-stats-insert").apply { isDaemon = true } }

    private fun newDatabaseExecutor() =
        ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(1),
        ) { runnable -> Thread(runnable, "operit-token-stats-database").apply { isDaemon = true } }

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
    private fun ensureDirectoryDurabilityConfirmed(context: Context, dir: File): Boolean {
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

    private suspend fun <T> withExclusiveSnapshotAccessInternal(
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
    private fun fenceAcceptsRestore(sessionEpoch: Long): Boolean =
        synchronized(stateLock) { acceptingEventsThisProcess && sessionEpoch == restoreEpoch }

    /**
     * 请求开始时同步捕获当前 restore epoch（P1 终审）：纯内存读取，无需 Room。请求在
     * 收尾 [append] 时按该值验证未被恢复屏障取代。
     */
    fun captureRestoreEpoch(): Long = synchronized(stateLock) { restoreEpoch }

    /** 本进程是否仍接受新的统计事件/请求（恢复替换开始后为 false，直到进程重启）。 */
    fun isAcceptingEvents(): Boolean = synchronized(stateLock) { acceptingEventsThisProcess }

    /**
     * 硬超时等待已登记 insert 全部结束。等待期间不持有 [stateLock]（轮询只短暂取快照），
     * 因此绝不阻塞普通 drain/append；[delay] 可被协程取消，超时由调用方转换为明确失败。
     */
    private suspend fun awaitActiveInsertsEmpty(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(exclusiveQuiesceTimeoutMs)
        while (true) {
            if (synchronized(stateLock) { activeInserts.isEmpty() }) return true
            if (System.nanoTime() >= deadline) return false
            delay(QUIESCE_POLL_INTERVAL_MS)
        }
    }

    private fun clearForRestoreLocked(context: Context) {
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

    private fun stuckAckTrashEvidenceLocked(context: Context): List<File> {
        val dir = spoolDir(context.applicationContext)
        if (!dir.isDirectory) return emptyList()
        // P1-6：根目录枚举统一走 [listDir]（测试可注入失败）；失败（null）即明确抛错，
        // 绝不当作“没有 trash”返回（否则 export 会成功遗漏全部 stuck 证据）。
        val rootFiles = listDir(dir)
            ?: throw IOException("cannot enumerate spool directory for ack trash: ${dir.absolutePath}")
        // 先完整枚举结果，再对每个候选做 NOFOLLOW 目录验证（File.isDirectory 会跟随符号链接）
        return rootFiles
            .filter { f ->
                f.name.startsWith(ACK_TRASH_PREFIX) &&
                    !Files.isSymbolicLink(f.toPath()) &&
                    Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }
            .mapNotNull { trash ->
                // P1-6：trash 枚举失败（null）是明确失败，绝不当作空目录跳过该证据
                val files = listDir(trash)
                    ?: throw IOException("cannot enumerate ack trash directory: ${trash.name}")
                files.takeIf { it.isNotEmpty() }?.let { trash }
            }
            .sortedBy { it.name }
    }

    private suspend fun quarantineEvidenceLocked(context: Context): List<File> {
        val dir = spoolDir(context.applicationContext)
        val managed = readTombstoneLines(context).mapNotNull { line ->
            val entry = parseTombstoneLine(line) ?: return@mapNotNull null
            val file = File(dir, entry.file)
            // P1-2：只有身份可校验（MATCH）的受管段才作为 evidence 暴露——UNREADABLE 绝不
            // 出现在可导出/可 ack 的列表里（身份不可校验时 ack 无法安全删除），保留 manifest。
            file.takeIf { it.isFile && entry.identityCheck(file) == IdentityCheck.MATCH }
        }
        return (quarantineAreaFiles(dir) + managed).sortedBy { it.name }
    }

    /**
     * 完整证据区（quarantine_* 前缀 + seal 发布失败隔离的 seal_failed_*，不含受管失败段与
     * 有界元数据/sidecar）。P1-6：证据区是 export/ack/info 的证据来源，根目录枚举失败（null）
     * 时抛 [IOException]（fail-closed）——绝不把失败当作空证据区返回，否则 export 会在遗漏
     * 完整证据时仍成功。P2 终审：seal_failed_*（受管失败发布证据）与 quarantine_* 同等参与
     * 可见/计数/导出/ack——长期删除失败时用户可确认删除并释放容量，绝不无限隐藏占用。
     * 注意：这里**不走** [directoryListingForTest] seam（seam 只覆盖 sealed 队列/待处理判定
     * 与 ack/trash 安全路径，见 [listDir]），生产路径的原始枚举失败同样按 null 显式失败处理。
     */
    private fun quarantineAreaFiles(dir: File): List<File> {
        // 目录不存在 = 证据区尚未创建，空集是真实状态（append 准入在创建目录之前检查）；
        // 目录**存在**但枚举失败（null）才是 fail-closed 抛错场景。
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles()
            ?: throw IOException("cannot enumerate spool directory for quarantine evidence: ${dir.absolutePath}")
        return files
            .filter {
                it.isFile &&
                    (it.name.startsWith(QUARANTINE_PREFIX) || it.name.startsWith(SEAL_FAILED_PREFIX)) &&
                    it.name != QUARANTINE_SUMMARY_NAME &&
                    it.name != TOMBSTONE_MANIFEST_NAME &&
                    !it.name.startsWith("$QUARANTINE_SUMMARY_NAME.") &&
                    !it.name.startsWith("$TOMBSTONE_MANIFEST_NAME.")
            }
            .sortedBy { it.name }
    }

    /**
     * P1-1 append 准入检查（调用方持 lifecycleMutex）：受管失败集合
     * （entry 数/受管段字节）或完整证据区字节任一到达硬上限即拒绝新统计——此时新损坏段
     * 将无处可去（不能删除/重命名、受管集合已满），继续接收只会让磁盘/重扫无界。
     */
    private suspend fun managedFailureCapacityExceeded(context: Context): Boolean {
        val dir = spoolDir(context)
        val entries = readTombstoneLines(context).mapNotNull(::parseTombstoneLine)
        if (entries.isEmpty() && quarantineAreaFiles(dir).isEmpty()) return false
        return entries.size >= MAX_TOMBSTONE_ENTRIES ||
            entries.sumOf { it.bytes.coerceAtLeast(0L) } >= MAX_MANAGED_BYTES ||
            quarantineAreaFiles(dir).sumOf { it.length() } >= MAX_QUARANTINE_BYTES
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

    private suspend fun quarantineSummaryInfoLocked(context: Context): QuarantineSummaryInfo? {
        val file = File(spoolDir(context.applicationContext), QUARANTINE_SUMMARY_NAME)
        val content = try {
            readMetadata(summaryStore(file), file)
        } catch (e: Exception) {
            logE("statistics quarantine summary read failed", e)
            null
        } ?: return null
        val lines = content.lineSequence().filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return null
        return QuarantineSummaryInfo(
            recordCount = lines.size,
            summaryBytes = file.length(),
        )
    }

    private fun summaryStore(file: File) =
        AtomicRestoreMarkerStore(
            file,
            quarantineAtomicMoveForTest ?: ::atomicMoveReplacing,
            // P1-3 终审：spool 的 summary/manifest/ack state 统一走严格目录同步——write 只有
            // 目录项确认持久（[syncDir] == OK）才成功；read 的 sidecar 恢复 rename 同样严格。
            // P1-1 终审修复：任一非 OK 同时失效 bootstrap gate（[requireSpoolDirSync]）——
            // 元数据目录项未确认后下一次使用必须重新确认，绝不带着“已确认”标记继续。
            strictDirectorySync = { dir -> requireSpoolDirSync(dir) },
        )

    /**
     * 有界元数据读取（P1-2，调用方持 lifecycleMutex）：测试注入缝模拟读取失败（抛明确
     * IOException，调用方据此 fail-closed），生产路径委托 [AtomicRestoreMarkerStore.read]
     * （崩溃安全恢复 canonical/.new/.bak/tmp 完整值）。
     */
    private suspend fun readMetadata(store: AtomicRestoreMarkerStore, file: File): String? {
        if (metadataReadErrorForTest?.invoke(file) == true) {
            throw IOException("statistics metadata read failed (injected): ${file.name}")
        }
        return store.read()
    }

    /** 首选同目录原子替换（Windows MoveFileEx / POSIX rename）；不支持或失败返回 false 走回退。 */
    private fun atomicMoveReplacing(from: File, to: File): Boolean = try {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
        true
    } catch (e: AtomicMoveNotSupportedException) {
        false
    } catch (e: IOException) {
        false
    }

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
                        else -> when (entry.identityCheck(file)) {
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

    /** Explicit post-export acknowledgment for the bounded rolling summary and every sidecar. */
    private suspend fun deleteQuarantineSummaryLocked(context: Context) {
        val dir = spoolDir(context.applicationContext)
        val summaryFile = File(dir, QUARANTINE_SUMMARY_NAME)
        summaryStore(summaryFile).delete()
        val remaining = listDir(dir)
            ?: throw IOException("cannot verify quarantine summary deletion: ${dir.absolutePath}")
        if (remaining.any { it.name == QUARANTINE_SUMMARY_NAME || it.name.startsWith("$QUARANTINE_SUMMARY_NAME.") }) {
            throw IOException("statistics quarantine summary deletion failed: ${summaryFile.absolutePath}")
        }
    }

    /**
     * ack 的主 manifest 重写（P1-1，调用方持 lifecycleMutex）：发布前投影实际总量 + 最坏
     * sidecar 增量，超限有界失败不写文件；测试注入缝照常生效。失败抛 [IOException]。
     */
    private suspend fun rewriteAckManifestLocked(context: Context, manifestFile: File, newContent: String) {
        if (metadataWriteBudgetExceeded(context, newContent.toByteArray(Charsets.UTF_8).size)) {
            throw IOException("tombstone manifest rewrite refused: metadata budget over the total cap")
        }
        if (metadataWriteErrorForTest?.invoke(manifestFile) == true) {
            throw IOException("tombstone manifest write failed (injected)")
        }
        summaryStore(manifestFile).write(newContent)
    }

    /**
     * 写入 ack trash 状态文件（P1-2，调用方持 lifecycleMutex）：UNCOMMITTED + mapping（只含
     * 已 stage 文件）。P1-1：发布前投影预算，超限返回 false。写失败返回 false（调用方保留
     * 无状态 trash，维护 fail-closed 保留，绝不误删）。原子崩溃安全写入（sidecar 可恢复）。
     *
     * P2 终审：mapping 身份（bytes+sha256）必须从**实际当前所在文件**捕获——回滚 move 已
     * 可见但目录项 sync 失败时，文件可能已回到原路径（trash 内已无此文件），此时从已移走
     * 的 target 盲读会得到 0 字节/空哈希的伪身份，甚至使整个状态写入失败；因此 target
     * 存在读 target，否则读 original（两者内容同一，身份一致）。两者都不存在（文件消失，
     * 不可能的正常路径）→ 返回 false fail-closed，绝不写残缺 mapping。
     */
    private suspend fun writeUncommittedTrashState(
        context: Context,
        trashDir: File,
        staged: List<Pair<File, File>>,
    ): Boolean {
        return try {
            val mappingEntries = staged.map { (original, target) ->
                // P2 终审：身份从实际所在位置捕获（trash 或 original），绝不盲读已移走的 target。
                val location =
                    when {
                        target.exists() -> target
                        original.exists() -> original
                        else -> null
                    }
                if (location == null) {
                    logE(
                        "statistics ack trash state identity unavailable; refusing to write mapping: " +
                            "${original.name}",
                    )
                    return false
                }
                AckMappingEntry(
                    original = original.name,
                    trashName = target.name,
                    bytes = location.length(),
                    sha256 = sha256Hex(location.readBytes()),
                )
            }
            val stateContent = buildAckStateContent(ACK_STATE_UNCOMMITTED, mappingEntries)
            if (metadataWriteBudgetExceeded(context, stateContent.toByteArray(Charsets.UTF_8).size)) {
                logE("statistics ack trash state publish refused: metadata budget over the total cap")
                return false
            }
            summaryStore(File(trashDir, ACK_TRASH_STATE_FILE_NAME)).write(stateContent)
            true
        } catch (e: Exception) {
            logE("statistics ack trash state write failed", e)
            false
        }
    }

    /** P2 终审：ack 回滚的结构化结果。success=false = 有文件未能移回原位（trash 保留为可
     * 重试记录，由维护按 mapping 处置）；syncFailed=true = 存在目录项未确认持久的变更
     * （上层必须失败并保留 UNCOMMITTED/stuck 状态，绝不静默推进）。 */
    private data class RollbackStagedResult(
        val success: Boolean,
        val syncFailed: Boolean,
    )

    /**
     * ack 的 rename 回滚（reviewer P1 + P2 终审）：把已 stage 进 trash 的文件按逆序移回原位；
     * 全部成功且目录项全部确认持久才删除本轮 trash 目录（含状态/mapping 文件）。P2：每个
     * 移动/删除都是目录项变更——move 后必须严格 sync（[requireSpoolDirSync]，跨 spool 根与
     * trash 两个目录），任一非 OK 置 [RollbackStagedResult.syncFailed]：上层保留
     * UNCOMMITTED 状态并失败，绝不带着未确认状态声称回滚完成（变更可见时下一轮按
     * mapping+identity 幂等完成）。某个回滚失败时保留 trash 及其证据（状态仍为 UNCOMMITTED
     * + mapping，维护入口按状态机判定/回滚，绝不误删），同样报告失败。
     */
    private fun rollbackStagedRenames(
        staged: List<Pair<File, File>>,
        trashDir: File,
    ): RollbackStagedResult {
        val dir = trashDir.parentFile ?: return RollbackStagedResult(false, false)
        var allRolledBack = true
        var syncFailed = false
        for ((original, target) in staged.asReversed()) {
            if (!target.exists()) continue
            if (original.exists() || !atomicMoveForAck(target, original)) {
                allRolledBack = false
                logE(
                    "statistics ack rollback failed for ${original.name}; evidence stays in ${trashDir.name}",
                )
                continue
            }
            // P2 终审：回滚 move 跨 spool 根与 trash 两个目录——两者目录项都必须确认持久；
            // 非 OK 置 syncFailed（调用方保留 UNCOMMITTED 状态并失败）。
            if (!requireSpoolDirSync(dir, trashDir)) {
                logE("statistics ack rollback move not durable: ${original.name}")
                syncFailed = true
            }
        }
        if (allRolledBack && !syncFailed) {
            if (!deleteAckTrashDirNoFollow(trashDir)) {
                logE("statistics ack trash directory cleanup failed after rollback: ${trashDir.name}")
                allRolledBack = false
            } else if (!requireSpoolDirSync(dir)) {
                logE("statistics ack trash deletion not durable after rollback: ${trashDir.name}")
                syncFailed = true
            }
        }
        return RollbackStagedResult(allRolledBack, syncFailed)
    }

    /**
     * 目标路径归属预检（reviewer P1，防目录穿越）：ack 只接受 spool 根目录下的单层相对
     * 文件名——非空、不含路径分隔符、不是 "."/".."，且解析后父目录仍是 spool 根目录。
     */
    private fun requireSafeEvidenceName(dir: File, name: String) {
        if (!isSafeEvidenceName(dir, name)) {
            throw IOException("unsafe acknowledged evidence name: $name")
        }
    }

    /** 单层相对名检查（reviewer P1，防目录穿越）：非空、不含分隔符、解析后父目录是 dir。 */
    private fun isSafeEvidenceName(dir: File, name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\') &&
            File(dir, name).parentFile?.canonicalFile == dir.canonicalFile

    /** trash 内文件名检查（P1-2）：同 [isSafeEvidenceName]，父目录必须是 trash 目录本身。 */
    private fun isSafeTrashName(trash: File, name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            !name.contains('/') &&
            !name.contains('\\') &&
            File(trash, name).parentFile?.canonicalFile == trash.canonicalFile

    /** ack trash mapping 条目（P1-2）：spool 根原名 → trash 内名 + 稳定身份（bytes+sha256）。 */
    private data class AckMappingEntry(
        val original: String,
        val trashName: String,
        val bytes: Long,
        val sha256: String,
    )

    private fun ackMappingLine(entry: AckMappingEntry): String =
        JSONObject()
            .put("o", entry.original)
            .put("t", entry.trashName)
            .put("b", entry.bytes)
            .put("s", entry.sha256)
            .toString()

    private fun parseAckMappingLine(line: String): AckMappingEntry? = try {
        val obj = JSONObject(line)
        val original = obj.optString("o").takeIf { it.isNotEmpty() } ?: return null
        val trashName = obj.optString("t").takeIf { it.isNotEmpty() } ?: return null
        AckMappingEntry(
            original = original,
            trashName = trashName,
            bytes = obj.optLong("b", -1L),
            sha256 = obj.optString("s", ""),
        )
    } catch (_: Exception) {
        null
    }

    /**
     * P1-2 修复：状态 mapping 的**全有或全无**解析（调用方持 lifecycleMutex）。任一条件失败
     * 返回 null，调用方对整个 trash fail-closed 保留（不执行 delete/rollback/manifest 改动）：
     * - header 之后的每一行都必须解析成功（mapNotNull 静默丢弃损坏行会漏掉未回滚的证据，
     *   导致 trash 被整体删除）；
     * - bytes/sha256 必须完整（缺失即身份不可校验）；
     * - 原名/trash 名必须单层安全（防穿越），且无重复（同名两份证据无法可靠处置）；
     * - trash 内所有普通文件（排除状态文件及其 `.new`/`.bak`/`.tmp*` sidecar）都必须被
     *   mapping 覆盖（mapping 数量与证据文件集合完整对应）；存在符号链接/特殊文件也 fail-closed。
     *
     * mapping 条目引用的 trash 文件**缺失**是允许的（该文件可能已在先前一次回滚中移回原槽位，
     * 由 [rollbackUncommittedTrash] 按原槽位身份判定），因此这里只校验“trash 里的每个文件都
     * 有 mapping”，不要求反向一一对应。
     */
    private fun parseAckMappingStrict(
        dir: File,
        trash: File,
        lines: List<String>,
    ): List<AckMappingEntry>? {
        val entries = mutableListOf<AckMappingEntry>()
        val originals = HashSet<String>()
        val trashNames = HashSet<String>()
        for (raw in lines.drop(1)) {
            val entry = parseAckMappingLine(raw) ?: return null
            if (entry.bytes < 0L || entry.sha256.isEmpty()) return null
            if (!isSafeEvidenceName(dir, entry.original)) return null
            if (!isSafeTrashName(trash, entry.trashName)) return null
            if (!originals.add(entry.original)) return null
            if (!trashNames.add(entry.trashName)) return null
            entries += entry
        }
        val stateBase = ACK_TRASH_STATE_FILE_NAME
        // P1-5 fail-closed：trash 目录枚举失败（null）时内部证据集合不可知——mapping 无法
        // 证明覆盖了全部证据文件，任何 rollback 后对 trash 的整体删除都会丢失未枚举的证据，
        // 立即返回 null 使整个 trash 被保留。
        val trashFiles = listDir(trash)
        if (trashFiles == null) {
            logE("statistics ack trash directory enumeration failed; retaining trash: ${trash.name}")
            return null
        }
        val unaccounted = trashFiles.any { file ->
            val name = file.name
            val isStateSidecar =
                name == stateBase ||
                    name == "$stateBase.new" ||
                    name == "$stateBase.bak" ||
                    name.startsWith("$stateBase.tmp")
            if (!Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // 符号链接/特殊文件：无法按身份管理，fail-closed
                true
            } else if (isStateSidecar) {
                false
            } else {
                name !in trashNames
            }
        }
        return if (unaccounted) null else entries
    }

    /** 状态文件内容：首行状态 + 每行一条 mapping（状态与 mapping 一次原子写入，无半写窗口）。 */
    private fun buildAckStateContent(state: String, entries: List<AckMappingEntry>): String =
        state + "\n" + entries.joinToString("\n") { ackMappingLine(it) } + "\n"

    /** ack 只管理 spool 根目录中的普通文件，不跟随符号链接或其他特殊路径。 */
    private fun requireManageableEvidenceFile(dir: File, file: File) {
        val dirPath = dir.canonicalFile.toPath()
        val filePath = file.toPath()
        if (file.parentFile?.canonicalFile?.toPath() != dirPath ||
            Files.isSymbolicLink(filePath) ||
            !Files.isRegularFile(filePath, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            file.canonicalFile.parentFile?.toPath() != dirPath
        ) {
            throw IOException("unmanageable acknowledged evidence path: ${file.name}")
        }
    }

    /**
     * P1-3：stuck ack trash 目录的删除前校验（防目录穿越/符号链接）：只接受 spool 根内匹配
     * [ACK_TRASH_PREFIX] 的真实普通目录（NOFOLLOW_LINKS），拒绝符号链接与特殊路径。
     */
    private fun requireAckTrashDirForDelete(dir: File, trash: File) {
        if (!trash.name.startsWith(ACK_TRASH_PREFIX)) {
            throw IOException("not an ack trash directory: ${trash.name}")
        }
        if (Files.isSymbolicLink(trash.toPath())) {
            throw IOException("ack trash must not be a symbolic link: ${trash.name}")
        }
        if (!Files.isDirectory(trash.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("ack trash must be a real directory: ${trash.name}")
        }
        if (trash.canonicalFile.parentFile?.toPath() != dir.canonicalFile.toPath()) {
            throw IOException("ack trash escapes the spool root: ${trash.name}")
        }
    }

    /**
     * P1-3：递归删除 ack trash 目录（NOFOLLOW）：不跟随符号链接（链接本身被删除，绝不触及
     * 其目标），只删除普通文件与空目录；任何遍历/删除失败返回 false（调用方保留并报错）。
     */
    private fun deleteAckTrashDirNoFollow(trash: File): Boolean {
        return try {
            Files.walkFileTree(
                trash.toPath(),
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        throw exc
                    }
                },
            )
            true
        } catch (e: Exception) {
            logE("statistics ack trash no-follow deletion failed: ${trash.name}", e)
            false
        }
    }

    /** prepare/rollback 必须是同 filesystem 的原子 move，且绝不覆盖同名目标。 */
    private fun atomicMoveForAck(from: File, to: File): Boolean {
        ackAtomicMoveForTest?.invoke(from, to)?.let { return it }
        // 兼容现有故障注入缝；生产为 null 时仍走真正的 ATOMIC_MOVE。
        segmentRenameForTest?.invoke(from, to)?.let { return it }
        if (to.exists()) return false
        return try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: AtomicMoveNotSupportedException) {
            false
        } catch (e: IOException) {
            false
        }
    }

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
    private fun scheduleDrain(context: Context, delayMs: Long = 0L) {
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
    private fun consumeDrainRequest(generation: Long): Boolean = synchronized(stateLock) {
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
    private fun runDrain(context: Context, generation: Long) {
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

    private fun completeInitialDrainWaitersLocked(success: Boolean) {
        if (initialDrainWaiters.isEmpty()) return
        initialDrainWaiters.forEach { waiter ->
            if (waiter.isActive) waiter.complete(success)
        }
        initialDrainWaiters.clear()
    }

    /** Called with lifecycleMutex held. */
    private suspend fun drainCore(context: Context, generation: Long): Boolean {
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

    private suspend fun sealAndList(context: Context): List<File>? {
        val dir = spoolDir(context)
        if (!dir.isDirectory) return emptyList()
        val active = File(dir, ACTIVE_FILE_NAME)
        if (active.isFile && active.length() > 0L && !sealActive(context, dir)) {
            return null
        }
        // P1-2：tombstone 按稳定 identity（file+bytes+sha256）跳过，绝不只信文件名
        return sealedFilesToProcess(context, dir, readTombstoneLines(context))
    }

    private suspend fun hasPendingSegments(context: Context): Boolean {
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
    private suspend fun hasQuarantineEvidenceForSnapshotLocked(context: Context): Boolean {
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
    private suspend fun sealedFilesToProcess(context: Context, dir: File, rawLines: List<String>): List<File> {
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
                        else -> when (entry.identityCheck(file)) {
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

    /**
     * Seal 采用文件系统级原子“不替换”发布（P1-8 + P1 终审持久化协议）：
     *
     * 1. 首选 `Files.createLink(target, active)`：同目录硬链接，目标创建原子且已存在时抛
     *    [FileAlreadyExistsException]（绝不替换既有 sealed 段）；链接建立后按 P1 终审顺序
     *    持久化：sync 目录（链接目录项）→ 删除 active → sync 目录（删除持久化）。崩溃窗口
     *    （链接已建、active 删除未发生或未持久化）两个名字指向同一 inode，由
     *    [recoverSealDuplicates] 在下次 append/drain 时识别并删除 sealed 副本（内容保留在
     *    active，绝不重复拼接——向 active 追加会连带改写已 seal 段）。
     * 2. 硬链接不受支持（FAT/exFAT 等）或临时失败时回退 copy 发布（[publishSealedByCopy]）：
     *    Android/Linux 的 Unix provider 以 O_CREAT|O_EXCL 原子创建目标（已存在即抛
     *    [FileAlreadyExistsException]），Windows 以 CREATE_NEW 同样原子不替换。这比不带
     *    REPLACE 的 `Files.move` 更强：Android 的普通 move 先做存在性预检再 rename(2)
     *    （rename 会静默替换预检之后出现的目标），保留 TOCTOU，不可单独依赖；ATOMIC_MOVE
     *    在目标已存在时语义实现相关，同样不可依赖。copy 回退的崩溃窗口（复制完成、active
     *    未删）产生两个内容相同的独立文件，同样由 [recoverSealDuplicates] 按内容识别去重。
     *
     * 枚举失败（null）或恢复无法确认无重复时 seal 明确失败（fail-closed），绝不发布。
     */
    private suspend fun sealActive(context: Context, dir: File): Boolean {
        val active = File(dir, ACTIVE_FILE_NAME)
        if (!active.isFile || active.length() == 0L) return true
        if (!recoverSealDuplicates(dir, active)) {
            logE("statistics spool segment seal failed: seal recovery could not confirm no duplicates")
            return false
        }
        // P1-7 fail-closed：seal 编号枚举失败（null）→ seal 明确失败，绝不回退到编号 1
        // （枚举失败时回退 1 会重名覆盖 sealed_1 等既有段，销毁其证据）。枚举成功但目标
        // 已被占用（异常残留）→ 递增到下一个安全编号，找不到则失败，绝不覆盖任何既有段。
        val index = nextSealIndex(dir)
        if (index == null) {
            logE("statistics spool segment seal failed: cannot enumerate spool directory: ${dir.absolutePath}")
            return false
        }
        var candidate = index
        while (true) {
            val target = File(dir, "$SEALED_PREFIX$candidate$SEALED_SUFFIX")
            // 可控 publication seam（P1-8）：测试可在此创建同名不同内容的目标文件模拟冲突，
            // 真实发布路径必须检测到占用并选择下一编号，目标原字节保持不变。
            if (beforeSealPublishForTest?.invoke(target) == false) {
                logE("statistics spool segment seal failed: pre-publish hook refused: ${target.name}")
                return false
            }
            when (publishSealedNoReplace(context, dir, active, target)) {
                SealPublishResult.PUBLISHED -> return true
                SealPublishResult.EXISTS -> {
                    candidate += 1L
                    if (candidate <= 0L) {
                        // Long 溢出防御：不再有可用编号 → 失败（绝不覆盖）
                        logE("statistics spool segment seal failed: no free sealed index: ${dir.absolutePath}")
                        return false
                    }
                }
                SealPublishResult.FAILED -> {
                    logE("statistics spool segment seal failed: ${target.name}")
                    return false
                }
            }
        }
    }

    /** seal 原子发布结果：成功 / 目标已存在（调用方换下一编号）/ 其他失败（终止本轮）。 */
    private enum class SealPublishResult { PUBLISHED, EXISTS, FAILED }

    /**
     * 原子“不替换”发布 active → target（调用方持 lifecycleMutex，契约见 [sealActive]）：
     * 首选硬链接；不受支持时回退 copy 发布（[publishSealedByCopy]）。目标已存在只返回
     * [SealPublishResult.EXISTS]，绝不修改、替换或删除既有目标。
     *
     * 持久化契约（P1 终审）：两种路径都保证“target 的 data + 目录项（创建/链接/删除）已
     * fsync 确认后才可能返回 PUBLISHED”；任何前置失败保留 active（数据持有者）并返回
     * FAILED；删除 active 后的目录同步失败返回 FAILED 但保留已 durable 的 target，由
     * [recoverSealDuplicates] 恢复。
     */
    private suspend fun publishSealedNoReplace(
        context: Context,
        dir: File,
        active: File,
        target: File,
    ): SealPublishResult {
        val linked = if (sealHardLinkForTest?.invoke(active, target) != false) {
            try {
                Files.createLink(target.toPath(), active.toPath())
                true
            } catch (e: FileAlreadyExistsException) {
                return SealPublishResult.EXISTS
            } catch (e: Exception) {
                // 平台/文件系统不支持硬链接或临时失败 → 回退 copy 发布
                false
            }
        } else {
            // 测试注入：强制模拟硬链接不受支持
            false
        }
        if (linked) return publishSealedAfterHardLink(dir, active, target)
        return publishSealedByCopy(context, dir, active, target)
    }

    /**
     * 硬链接发布后置持久化（P1 终审）：createLink 已原子建立同 inode 链接（active 数据在
     * append 时已 fsync）。顺序：sync 目录（持久化链接目录项）→ 删除 active → sync 目录
     * （持久化删除）。
     *
     * - 删除 active 之前的任何失败：active 是唯一数据持有者，保留 active、回滚链接并返回
     *   FAILED，绝不声称 PUBLISHED（否则崩溃窗口里 append 可能写进已 seal 段）。
     * - 删除 active 之后的目录同步失败：链接已 data+creation durable，active 删除可能未
     *   持久化——保留明确恢复状态（崩溃后 active 以同 inode 重现时由 [recoverSealDuplicates]
     *   去重；未重现则 target 正常排空），返回 FAILED 阻止本轮后续 append 写入，绝不回滚
     *   已 durable 的 target。
     *
     * P1 终审：只有 [DirSyncResult.OK] 才能继续；[DirSyncResult.UNSUPPORTED] 与 FAILED 一样
     * fail-closed——目录项未确认持久时**绝不**删除唯一 fsynced active 或返回 PUBLISHED。
     */
    private fun publishSealedAfterHardLink(
        dir: File,
        active: File,
        target: File,
    ): SealPublishResult {
        if (!requireSpoolDirSync(dir)) {
            rollbackSealTarget(dir, target, "hardlink")
            return SealPublishResult.FAILED
        }
        if (!deleteActiveAfterPublish(active)) {
            // 链接已建但 active 删除失败：同 inode 重复。先尝试回滚链接；回滚也失败时
            // 保留给 [recoverSealDuplicates] 下次识别（内容仍在 active）。绝不可带着
            // active 返回成功——否则后续 drain 会把同一内容排空两次。
            rollbackSealTarget(dir, target, "hardlink")
            return SealPublishResult.FAILED
        }
        if (!requireSpoolDirSync(dir)) {
            logE(
                "statistics spool seal hardlink: dir sync after active removal failed; " +
                    "durable link will be recovered: ${target.name}",
            )
            return SealPublishResult.FAILED
        }
        return SealPublishResult.PUBLISHED
    }

    /**
     * copy 回退发布（P1 终审 + P2）：O_CREAT|O_EXCL / CREATE_NEW 原子创建目标（绝不替换
     * 既有目标；[FileAlreadyExistsException] → EXISTS 让调用方选下一编号）。
     *
     * 持久化顺序：copy 目标 → fsync 目标数据（[syncFile]）→ sync 目录（目标创建持久）→
     * 删除 active → sync 目录（删除持久）。
     * - 删除 active 之前的任何失败：active 是完整内容持有者，保留 active，并按 P2 处置本次
     *   目标（[disposeFailedCopyTarget]：identity 确认后隔离到 seal_failed_<uuid> 或安全
     *   删除；两者都失败则 tombstone skip，绝不当 normal sealed 排空），返回 FAILED。
     * - 删除 active 之后的目录同步失败：目标已 data+creation durable，active 删除可能未
     *   持久化（崩溃后 active 以原内容重现 → [recoverSealDuplicates] 按内容去重；未重现则
     *   target 正常排空）——保留该明确恢复状态并返回 FAILED，阻止本轮后续 append 污染，
     *   绝不回滚已 durable 的 target。
     *
     * P1 终审：只有 [DirSyncResult.OK] 才能继续；[DirSyncResult.UNSUPPORTED] 与 FAILED 一样
     * fail-closed——目录项未确认持久时**绝不**删除唯一 fsynced active 或返回 PUBLISHED。
     */
    private suspend fun publishSealedByCopy(
        context: Context,
        dir: File,
        active: File,
        target: File,
    ): SealPublishResult {
        val injected = sealCopyForTest?.invoke(active, target)
        if (injected != null) {
            if (!injected) {
                if (!disposeFailedCopyTarget(context, dir, target, active)) {
                    logE(
                        "statistics spool seal copy failed; partial target disposal not durable: ${target.name}",
                    )
                }
                return SealPublishResult.FAILED
            }
        } else {
            try {
                Files.copy(active.toPath(), target.toPath())
            } catch (e: FileAlreadyExistsException) {
                return SealPublishResult.EXISTS
            } catch (e: Exception) {
                if (!disposeFailedCopyTarget(context, dir, target, active)) {
                    logE(
                        "statistics spool seal copy failed; partial target disposal not durable: ${target.name}",
                    )
                }
                return SealPublishResult.FAILED
            }
        }
        if (!syncFile(target)) {
            // 目标数据未确认 durable：保留 active，处置本次目标
            disposeFailedCopyTarget(context, dir, target, active)
            return SealPublishResult.FAILED
        }
        if (!requireSpoolDirSync(dir)) {
            // 目标创建未确认持久：保留 active，处置本次目标
            if (!disposeFailedCopyTarget(context, dir, target, active)) {
                logE(
                    "statistics spool seal copy failed; partial target disposal not durable: ${target.name}",
                )
            }
            return SealPublishResult.FAILED
        }
        if (!deleteActiveAfterPublish(active)) {
            // 复制完成、active 未删：两个独立文件同内容。目标已 durable（data+creation），
            // 删除目标放弃 sealed 副本（active 仍是完整内容持有者，无数据损失）；回滚失败
            // 留给 [recoverSealDuplicates] 按内容去重。
            rollbackSealTarget(dir, target, "copy")
            return SealPublishResult.FAILED
        }
        if (!requireSpoolDirSync(dir)) {
            logE(
                "statistics spool seal copy: dir sync after active removal failed; " +
                    "durable target will be recovered: ${target.name}",
            )
            return SealPublishResult.FAILED
        }
        return SealPublishResult.PUBLISHED
    }

    private fun deleteActiveAfterPublish(active: File): Boolean =
        sealActiveDeleteForTest?.invoke(active) ?: active.delete()

    /**
     * seal 前置失败回滚（P2 终审）：删除刚发布的 target（active 仍是完整内容持有者，删除
     * 安全无数据损失），删除后必须经 [requireSpoolDirSync] 确认目录项持久——删除是目录项
     * 变更，未确认持久绝不视为回滚完成（P1-1：非 OK 同时失效 bootstrap gate，下一次使用
     * 重新确认）。返回 false 表示回滚未完成/未确认（target 删除失败或目录项未确认持久），
     * 调用方保持失败状态；残留由 [recoverSealDuplicates] 按 inode/内容去重兜底。
     */
    private fun rollbackSealTarget(dir: File, target: File, kind: String): Boolean {
        if (!target.delete()) {
            logE("statistics spool seal rollback failed ($kind); duplicate will be recovered: ${target.name}")
            return false
        }
        if (!requireSpoolDirSync(dir)) {
            logE(
                "statistics spool seal rollback deletion not durable ($kind); " +
                    "gate invalidated, duplicate will be recovered: ${target.name}",
            )
            return false
        }
        return true
    }

    /**
     * P2 终审修复：seal copy 失败后的部分目标处置（调用方持 lifecycleMutex）。身份前提：候选
     * 编号在 copy 前由 [nextSealIndex] 确认不存在、copy 无 REPLACE 语义、lifecycleMutex 内无本
     * 进程并发——异常后目标若存在只可能是本次 copy 的部分写入；[isPrefixOf] 前缀校验防御外部
     * 进程并发占用该名字时的误隔离（identity 确认）。处置顺序：
     * 1. 原子 rename 到 `seal_failed_<uuid>`（scanner 忽略该前缀、计入递归总 cap、维护清理、
     *    作为受管失败发布证据可见/导出/ack）；rename 后目录项 sync 非 OK——隔离文件本身即受管
     *    证据，另按候选 sealed 身份写 tombstone（崩溃后该名字以同内容重现时 scanner 跳过，绝不
     *    普通排空），返回 false（调用方失败，绝不静默）。
     * 2. rename 失败 → 安全删除（active 保留完整内容，删除部分副本无数据损失）；删除后目录项
     *    sync 非 OK——删除可见但未确认：按候选 sealed 身份写 tombstone 保护崩溃后可能重现的
     *    名字，返回 false。
     * 3. rename/delete 都失败 → tombstone skip（记录稳定身份，scanner 跳过该具体文件，绝不当
     *    normal sealed 排空）；tombstone 写失败返回 false——drain 退避重试，不做任何破坏性决策。
     *
     * @return true = 已留下受管证据（seal_failed 隔离文件/tombstone 条目）或已安全删除且目录项
     *   确认持久；false = 存在目录项未确认持久的变更（tombstone 已尽力写入受管证据），调用方
     *   必须失败，绝不只记录日志。
     */
    private suspend fun disposeFailedCopyTarget(
        context: Context,
        dir: File,
        target: File,
        active: File,
    ): Boolean {
        if (!target.exists()) return true
        if (!isPrefixOf(target, active)) {
            logE(
                "statistics spool seal copy failure target identity mismatch; " +
                    "leaving file untouched: ${target.name}",
            )
            return true
        }
        val isolated = File(dir, "$SEAL_FAILED_PREFIX${UUID.randomUUID().toString().replace("-", "")}")
        if (renameForTest(target, isolated)) {
            if (!requireSpoolDirSync(dir)) {
                logE(
                    "statistics spool seal copy failed; isolated target rename not durable: ${isolated.name}",
                )
                // P2：隔离文件本身已是受管证据（seal_failed_*，可 UI/导出/ack/维护）；再按
                // 候选 sealed 身份写 tombstone，崩溃后该名字以同内容重现时 scanner 跳过。
                tombstonePartialTarget(context, target, isolated)
                return false
            }
            logE("statistics spool seal copy failed; partial target isolated: ${isolated.name}")
            return true
        }
        // rename 失败：先取原始字节（删除成功后将无法再读取），再尝试安全删除
        val rawBytes = try {
            target.readBytes()
        } catch (e: Exception) {
            logE(
                "statistics spool seal copy failed; partial target unreadable, cannot tombstone: ${target.name}",
                e,
            )
            null
        }
        if (segmentDeleteForTest?.invoke(target) ?: target.delete()) {
            if (!requireSpoolDirSync(dir)) {
                logE(
                    "statistics spool seal copy failed; partial target deletion not durable: ${target.name}",
                )
                // P2：删除可见但未确认——按候选 sealed 身份写 tombstone，崩溃后该名字
                // 以同内容重现时 scanner 跳过，绝不普通排空。
                if (rawBytes != null) {
                    tombstoneSegment(context, target, rawBytes, overCap = false)
                }
                return false
            }
            logE("statistics spool seal copy failed; partial target deleted: ${target.name}")
            return true
        }
        if (rawBytes == null) {
            logE(
                "statistics spool seal copy failed; partial target unreadable, cannot tombstone: ${target.name}",
            )
            return false
        }
        return when (tombstoneSegment(context, target, rawBytes, overCap = false)) {
            TombstoneResult.RECORDED, TombstoneResult.CAPACITY_FULL -> {
                logE(
                    "statistics spool seal copy failed; partial target tombstoned, scanner will skip: ${target.name}",
                )
                true
            }
            TombstoneResult.FAILED -> {
                logE(
                    "statistics spool seal copy failed; partial target tombstone write failed; drain will retry: ${target.name}",
                )
                false
            }
        }
    }

    /**
     * P2：seal copy 失败目标的 tombstone 写入（调用方持 lifecycleMutex）。目标可能已被改名/
     * 删除，[bytesSource] 提供其原始字节；[tombstoneSegment] 按稳定身份（bytes+sha256）记录
     * [nameFile]（候选 sealed 名），崩溃后该名字以同内容重现时 scanner 跳过。写失败仅记录——
     * 调用方本就返回失败，drain 退避重试。
     */
    private suspend fun tombstonePartialTarget(
        context: Context,
        nameFile: File,
        bytesSource: File,
    ) {
        val rawBytes = try {
            bytesSource.readBytes()
        } catch (e: Exception) {
            logE(
                "statistics spool seal copy failed; partial target unreadable, cannot tombstone: ${nameFile.name}",
                e,
            )
            return
        }
        when (tombstoneSegment(context, nameFile, rawBytes, overCap = false)) {
            TombstoneResult.RECORDED, TombstoneResult.CAPACITY_FULL -> Unit
            TombstoneResult.FAILED -> {
                logE(
                    "statistics spool seal copy failed; partial target tombstone write failed; drain will retry: ${nameFile.name}",
                )
            }
        }
    }

    /**
     * 部分目标 identity 确认（P2）：目标必须是 source（active）的前缀（长度 ≤ 且逐字节
     * 相等）才允许处置；读取失败返回 false（fail-closed，绝不隔离不可确认的文件）。
     */
    private fun isPrefixOf(partial: File, source: File): Boolean {
        if (partial.length() > source.length()) return false
        if (partial.length() == 0L) return true
        return try {
            partial.inputStream().use { pIn ->
                source.inputStream().use { sIn ->
                    val bufP = ByteArray(64 * 1024)
                    val bufS = ByteArray(64 * 1024)
                    var remaining = partial.length()
                    while (remaining > 0L) {
                        val want = minOf(bufP.size.toLong(), remaining).toInt()
                        val nP = pIn.read(bufP, 0, want)
                        if (nP <= 0) return false
                        val nS = sIn.read(bufS, 0, nP)
                        if (nS != nP) return false
                        if (!bufP.copyOfRange(0, nP).contentEquals(bufS.copyOfRange(0, nS))) {
                            return false
                        }
                        remaining -= nP
                    }
                    true
                }
            }
        } catch (e: Exception) {
            logE("statistics spool seal copy failure identity check failed", e)
            false
        }
    }

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
    private fun syncDir(dir: File): DirSyncResult {
        val seam = dirSyncForTest
        if (seam != null) return seam(dir) ?: realSyncDir(dir)
        return realSyncDir(dir)
    }

    private fun realSyncDir(dir: File): DirSyncResult {
        return try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            DirSyncResult.OK
        } catch (e: AccessDeniedException) {
            logE(
                "statistics spool directory fsync unsupported on this platform; " +
                    "fail-closed: no directory entry is confirmed durable",
                e,
            )
            DirSyncResult.UNSUPPORTED
        } catch (e: Exception) {
            logE("statistics spool directory fsync failed: ${dir.absolutePath}", e)
            DirSyncResult.FAILED
        }
    }

    /**
     * P1-1 终审修复：spool 目录项持久确认的统一入口（调用方持 lifecycleMutex）。任一目录
     * sync 非 OK 立即把 bootstrap gate 标记 [directoryDurabilityConfirmedThisProcess] 置 false
     * ——此后任何声称 durable 前都必须重新确认目录项，绝不带着“已确认”内存标记继续。
     * 所有 spool 目录项变更（新建/rename/delete，含 ack 跨 spool 根与 trash 两个目录）后的
     * 目录 sync 都必须经本入口确认。
     */
    private fun requireSpoolDirSync(vararg dirs: File): Boolean {
        val ok = dirs.all { syncDir(it) == DirSyncResult.OK }
        if (!ok) directoryDurabilityConfirmedThisProcess = false
        return ok
    }

    /**
     * 文件 fsync（P1 终审）：`FileChannel.force(true)` 持久化数据与元数据；失败返回 false
     * （调用方保留 active、处置目标、返回 FAILED，绝不声称 PUBLISHED）。
     */
    private fun syncFile(file: File): Boolean {
        val seam = fileSyncForTest
        if (seam != null) return seam(file) ?: realSyncFile(file)
        return realSyncFile(file)
    }

    private fun realSyncFile(file: File): Boolean = try {
        FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        true
    } catch (e: Exception) {
        logE("statistics spool file fsync failed: ${file.name}", e)
        false
    }

    /**
     * 恢复 seal 崩溃窗口的重复（P1-8，调用方持 lifecycleMutex）：active 与某个 sealed 段
     * 指向同一 inode（硬链接窗口：createLink 成功但 active 删除失败/崩溃）或内容完全相同
     * （copy 回退窗口：目标复制完成、active 删除未发生，两个独立 inode 同内容）时，删除
     * sealed 副本、保留 active 为唯一内容持有者——后续 append 才不会被连带写进已 seal 段，
     * 同一内容也只会被排空一次。
     *
     * 合法内容重复不可能发生（事件行含唯一 eventId，active 内容严格单调增长），因此
     * 内容相等只可能来自上述崩溃窗口；即使病理情况下误删副本，内容仍从 active 重新
     * seal 并排空，不丢数据。
     *
     * fail-closed：spool 根枚举失败（null）、任一 sealed 候选无法 stat/读取或删除失败时
     * 返回 false——调用方（append/seal）拒绝继续，绝不带着“可能还有重复”的状态写入或发布。
     */
    private fun recoverSealDuplicates(dir: File, active: File): Boolean {
        val files = listDir(dir) ?: return false
        val activeKey = try {
            Files.readAttributes(
                active.toPath(),
                BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            ).fileKey()
        } catch (e: Exception) {
            logE("statistics spool cannot stat active for seal recovery", e)
            return false
        }
        var ok = true
        var deletedAny = false
        for (file in files) {
            if (!file.isFile || !file.name.startsWith(SEALED_PREFIX) || !file.name.endsWith(SEALED_SUFFIX)) {
                continue
            }
            val key = try {
                Files.readAttributes(
                    file.toPath(),
                    BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS,
                ).fileKey()
            } catch (e: Exception) {
                logE("statistics spool cannot stat sealed segment for seal recovery: ${file.name}", e)
                ok = false
                continue
            }
            val sameInode = key != null && key == activeKey
            val contentDuplicate = if (sameInode) null else contentsEqual(file, active)
            when {
                sameInode || contentDuplicate == true -> {
                    if (!file.delete()) {
                        logE("statistics spool seal duplicate removal failed: ${file.name}")
                        ok = false
                    } else {
                        deletedAny = true
                    }
                }
                contentDuplicate == null -> {
                    // 无法确认是否重复（读取失败）→ fail-closed，绝不带着未知状态继续
                    logE("statistics spool cannot compare sealed segment for seal recovery: ${file.name}")
                    ok = false
                }
            }
        }
        // P1-3 终审：重复副本删除是目录项变更——未确认持久绝不报告恢复完成（append/seal
        // 据此 fail-closed）。active 仍是完整内容持有者，删除可见但未确认时崩溃后副本重现，
        // 由下次恢复按 inode/内容幂等重删，绝不丢数据。P1-1：非 OK 同时失效 gate。
        if (deletedAny && !requireSpoolDirSync(dir)) {
            logE("statistics spool seal duplicate removal not durable; recovery unconfirmed")
            ok = false
        }
        return ok
    }

    /** 逐字节比较（长度先短路；读失败返回 null，调用方按 fail-closed 处理）。 */
    private fun contentsEqual(a: File, b: File): Boolean? {
        if (a.length() != b.length()) return false
        if (a.length() == 0L) return true
        return try {
            a.inputStream().use { aIn ->
                b.inputStream().use { bIn ->
                    var equal = true
                    val bufA = ByteArray(64 * 1024)
                    val bufB = ByteArray(64 * 1024)
                    while (true) {
                        val nA = aIn.read(bufA)
                        val nB = bIn.read(bufB)
                        if (nA != nB) {
                            equal = false
                            break
                        }
                        if (nA < 0) break
                        if (!bufA.copyOfRange(0, nA).contentEquals(bufB.copyOfRange(0, nB))) {
                            equal = false
                            break
                        }
                    }
                    equal
                }
            }
        } catch (e: Exception) {
            logE("statistics spool seal duplicate content compare failed", e)
            null
        }
    }

    /**
     * A complete line always ends with '\n'; every append writes whole lines, so a file ending
     * with '\n' has no partial tail. Only the final write of a crash can leave a tail without one.
     */
    private fun activeEndsWithLineBreak(active: File): Boolean =
        RandomAccessFile(active, "r").use { raf ->
            raf.seek(raf.length() - 1L)
            raf.read() == '\n'.code
        }

    /** 下一个建议的 sealed 编号；目录枚举失败（null）返回 null → seal 必须失败（P1-7）。 */
    private fun nextSealIndex(dir: File): Long? {
        val files = listDir(dir) ?: return null
        return files.mapNotNull { file ->
            Regex("(?:quarantine_[^_]+_)?sealed_(\\d+)\\.jsonl").matchEntire(file.name)
                ?.groupValues?.get(1)?.toLongOrNull()
        }.maxOrNull()?.plus(1L) ?: 1L
    }

    private fun File.sealIndex(): Long =
        name.removePrefix(SEALED_PREFIX).removeSuffix(SEALED_SUFFIX).toLongOrNull() ?: Long.MAX_VALUE

    private suspend fun drainSegment(
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

    private fun renameForTest(from: File, to: File): Boolean =
        segmentRenameForTest?.invoke(from, to) ?: from.renameTo(to)
    /**
     * 超限损坏段的硬边界替换（P1-1/P1-2）：崩溃安全地发布“已裁剪到双上限”的新完整摘要
     * （旧完整或新完整，绝不截断），随后把段移出 sealed 扫描队列；段删除失败绝不阻塞健康
     * 排空（改为 pending-delete 证据或 tombstone 跳过）。摘要发布失败抛异常 → 保留旧摘要
     * 与待处理段，返回 false，绝不声称成功。
     */
    private suspend fun summarizeOverCapSegment(
        context: Context,
        segment: File,
        rawBytes: ByteArray,
        text: String,
        corruptLineCount: Int,
    ): Boolean {
        val summaryFile = File(spoolDir(context), QUARANTINE_SUMMARY_NAME)
        val store = summaryStore(summaryFile)
        return try {
            val sha = sha256Hex(rawBytes)
            // 崩溃安全读取旧完整摘要（中断残留会被恢复），绝不基于半写内容裁剪
            val oldContent = readMetadata(store, summaryFile)
            val oldLines =
                oldContent?.lineSequence()?.filter { it.isNotEmpty() }?.toList() ?: emptyList()
            val record =
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("file", segment.name)
                    .put("bytes", segment.length())
                    .put("sha256", sha)
                    .put("lineCount", text.lineSequence().filter { it.isNotEmpty() }.count())
                    .put("corruptLines", corruptLineCount)
                    .toString()
            // 崩溃重试幂等：同一段已有记录且未超限则不再追加；超限旧摘要仍会被裁剪自愈
            val alreadyRecorded =
                oldLines.any { line ->
                    try {
                        val obj = JSONObject(line)
                        obj.optString("file") == segment.name && obj.optString("sha256") == sha
                    } catch (_: Exception) {
                        false
                    }
                }
            val withinCaps =
                oldLines.size <= MAX_QUARANTINE_SUMMARY_LINES &&
                    oldLines.sumOf { utf8RecordBytes(it) } <= MAX_QUARANTINE_SUMMARY_BYTES
            if (!alreadyRecorded || !withinCaps) {
                val newContent = buildTrimmedSummary(oldLines, record)
                // P1-1：发布前投影实际总量 + 最坏 sidecar 增量（canonical/.new/.bak/tmp 四
                // 槽位），超限有界失败：保留旧摘要与待处理段，返回 false 让 drain 退避重试，
                // 绝不发布任何正式文件。
                if (metadataWriteBudgetExceeded(context, newContent.toByteArray(Charsets.UTF_8).size)) {
                    logE(
                        "statistics quarantine summary publish refused: metadata budget over the " +
                            "total cap; keeping old summary and pending segment: ${segment.name}",
                    )
                    return false
                }
                store.write(newContent)
            }
            if (disposeOverCapSegment(context, segment, rawBytes) == TombstoneResult.FAILED) {
                return false
            }
            logE("statistics quarantine hard cap: over-cap corrupt segment summarized and removed: ${segment.name}")
            true
        } catch (e: Exception) {
            logE("statistics quarantine summary write failed: ${segment.name}", e)
            false
        }
    }

    /**
     * 构建“已裁剪到双上限”的新完整摘要内容（行数与 UTF-8 字节总数都满足上限，保留最新记录）。
     * 真实记录约 200 字节，单行不可能超过字节上限；循环只保证至少保留最新一行。
     */
    private fun buildTrimmedSummary(oldLines: List<String>, record: String): String {
        var keep = oldLines.map(::normalizeOversizedSummaryLine) + record
        while (keep.size > 1 &&
            (keep.size > MAX_QUARANTINE_SUMMARY_LINES ||
                keep.sumOf { utf8RecordBytes(it) } > MAX_QUARANTINE_SUMMARY_BYTES)
        ) {
            keep = keep.drop(1)
        }
        return keep.joinToString("\n") + "\n"
    }

    /**
     * P2-1：摘要上限按 UTF-8 实际字节计（Kotlin String.length 是 UTF-16 code unit，非 ASCII
     * 字符会低估）；单行 UTF-8 字节超上限时替换为固定 ASCII 缩略记录（hash/bytes，不含正文），
     * 输出恒 ≤ [MAX_QUARANTINE_SUMMARY_BYTES]。
     */
    private fun utf8RecordBytes(line: String): Int =
        line.toByteArray(Charsets.UTF_8).size + 1

    private fun normalizeOversizedSummaryLine(line: String): String {
        if (utf8RecordBytes(line) <= MAX_QUARANTINE_SUMMARY_BYTES) return line
        val bytes = line.toByteArray(Charsets.UTF_8)
        return JSONObject()
            .put("truncated", true)
            .put("bytes", bytes.size)
            .put("sha256", sha256Hex(bytes))
            .toString()
    }

    private suspend fun disposeOverCapSegment(
        context: Context,
        segment: File,
        rawBytes: ByteArray,
    ): TombstoneResult {
        if (segmentDeleteForTest?.invoke(segment) ?: segment.delete()) {
            // P1-2 终审：删除是目录项变更——sync 非 OK 绝不返回 RECORDED（否则本轮声称成功
            // 而崩溃后段可能复活；下一轮按摘要身份幂等重删）。P1-1：非 OK 同时失效 gate。
            if (!requireSpoolDirSync(segment.parentFile!!)) {
                logE("statistics over-cap segment deletion not durable: ${segment.name}")
                return TombstoneResult.FAILED
            }
            return TombstoneResult.RECORDED
        }
        if (!segment.exists()) {
            // 段已消失（上一轮可见删除）：先确认删除持久才允许推进队列，绝不基于未确认
            // 状态返回 RECORDED。
            if (!requireSpoolDirSync(segment.parentFile!!)) {
                logE("statistics over-cap segment absence not durable: ${segment.name}")
                return TombstoneResult.FAILED
            }
            return TombstoneResult.RECORDED
        }
        val fitsBudget =
            quarantineEvidenceLocked(context).sumOf { it.length() } + segment.length() <= MAX_QUARANTINE_BYTES
        if (fitsBudget) {
            val pending = File(
                segment.parentFile,
                "$PENDING_DELETE_PREFIX${UUID.randomUUID().toString().replace("-", "")}_${segment.name}",
            )
            if (renameForTest(segment, pending)) {
                // P1-2 终审：rename 后目录项必须确认持久，非 OK 返回 FAILED（本轮退避重试；
                // rename 可见时下一轮直接跳过/按身份幂等处置，崩溃后 pending 名重现由维护重放）
                if (!requireSpoolDirSync(segment.parentFile!!)) {
                    logE("statistics over-cap pending-delete rename not durable: ${pending.name}")
                    return TombstoneResult.FAILED
                }
                logE("statistics over-cap segment deletion failed; retained as pending-delete evidence: ${pending.name}")
                return TombstoneResult.RECORDED
            }
        } else {
            logE("statistics over-cap segment deletion failed and full evidence exceeds the hard cap; summary retains hash/bytes: ${segment.name}")
        }
        logE("statistics over-cap segment pending-delete rename failed; tombstoning: ${segment.name}")
        return tombstoneSegment(context, segment, rawBytes, overCap = true)
    }

    /**
     * 读取 tombstone manifest（崩溃安全恢复）得到原始行；解析交给 [parseTombstoneLine]。
     * P1-3：不设 canonical isFile 前置——canonical 缺失而内容只在 `.new`/`.bak` sidecar
     * 时也必须先经 [AtomicRestoreMarkerStore.read] 恢复完整值再返回；否则仅 sidecar 存在
     * 时 info/ack/容量/扫描会误判为空。
     * P1-2 fail-closed：读取失败必须抛明确 [IOException]（不返回 empty）——调用方（append
     * 容量检查、scanner、快照、维护）据此中止并退避；返回空只允许出现在“manifest 不存在
     * （无受管记录）”这一真实状态。
     */
    private suspend fun readTombstoneLines(context: Context): List<String> {
        val manifestFile = File(spoolDir(context), TOMBSTONE_MANIFEST_NAME)
        val content = readMetadata(summaryStore(manifestFile), manifestFile) ?: return emptyList()
        return content.lineSequence().filter { it.isNotBlank() }.toList()
    }

    private fun parseTombstoneLine(line: String): TombstoneEntry? = try {
        val obj = JSONObject(line)
        val file = obj.optString("file").takeIf { it.isNotEmpty() } ?: return null
        TombstoneEntry(
            file = file,
            bytes = obj.optLong("bytes", -1L),
            sha256 = obj.optString("sha256", ""),
            overCap = obj.optBoolean("overCap", false),
        )
    } catch (_: Exception) {
        null
    }

    /** 段身份校验结果（P1-2）：读取失败 = UNREADABLE，绝不误判为陈旧而删/隔离/清理。 */
    private enum class IdentityCheck { MATCH, MISMATCH, UNREADABLE }

    /**
     * P1-2：稳定身份校验——文件名相同且字节数相同且原始字节 SHA-256 相同才是同一段
     * （MATCH）。bytes/sha256 缺失的旧条目（无身份）永不匹配 → MISMATCH（陈旧记录被清理）。
     * 原始字节读取失败 → UNREADABLE（保留 manifest 条目，调用方跳过或失败，不做破坏性决策）。
     * P1-1：SHA 永远现场从原始字节计算，绝不复用 length+mtime 缓存——同名同长同 mtime
     * 的替换内容必须被识别为不同身份（陈旧记录被清理，健康段绝不删/跳/隔离）。
     */
    private fun TombstoneEntry.identityCheck(file: File): IdentityCheck {
        if (sha256.isEmpty() || !file.isFile || file.length() != bytes) return IdentityCheck.MISMATCH
        if (segmentReadErrorForTest?.invoke(file) == true) return IdentityCheck.UNREADABLE
        return try {
            if (sha256Hex(file.readBytes()) == sha256) IdentityCheck.MATCH else IdentityCheck.MISMATCH
        } catch (e: Exception) {
            IdentityCheck.UNREADABLE
        }
    }

    /**
     * 有界 skip/tombstone manifest 更新（P1-1/P1-2）：**不滚动**的活跃受管失败集合，条目
     * 只在文件物理消失/身份变化后由维护入口移除；达到 entry/字节硬上限时返回
     * [TombstoneResult.CAPACITY_FULL]（调用方跳过该段继续健康，新统计 append 随后被拒绝），
     * 写失败返回 [TombstoneResult.FAILED]（drain 退避重试），绝不静默放行。
     */
    private suspend fun tombstoneSegment(
        context: Context,
        segment: File,
        rawBytes: ByteArray,
        overCap: Boolean,
    ): TombstoneResult {
        val manifestFile = File(spoolDir(context), TOMBSTONE_MANIFEST_NAME)
        val store = summaryStore(manifestFile)
        return try {
            val oldLines =
                readMetadata(store, manifestFile)?.lineSequence()?.filter { it.isNotEmpty() }?.toList()
                    ?: emptyList()
            val sha = sha256Hex(rawBytes)
            val entry =
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("file", segment.name)
                    // P2 终审：字节数按原始字节计算——候选文件可能已被隔离/删除（目录项 sync
                    // 失败路径），File.length() 对不存在的文件恒为 0，会让崩溃后重现文件的
                    // 身份判定失效；rawBytes 是调用方现场读取的稳定身份。
                    .put("bytes", rawBytes.size.toLong())
                    .put("sha256", sha)
                    .put("overCap", overCap)
                    .toString()
            // 崩溃重试幂等：同一身份已记录则不再追加（同一文件同一内容）
            val alreadyRecorded =
                oldLines.any { line ->
                    val existing = parseTombstoneLine(line)
                    existing?.file == segment.name && existing?.sha256 == sha
                }
            if (alreadyRecorded) return TombstoneResult.RECORDED
            val wouldBeEntries = oldLines.size + 1
            val wouldBeBytes = (oldLines + entry).sumOf { utf8RecordBytes(it) }
            if (wouldBeEntries > MAX_TOMBSTONE_ENTRIES || wouldBeBytes > MAX_TOMBSTONE_MANIFEST_BYTES) {
                logE(
                    "statistics tombstone capacity full; segment stays in queue for a later retry: ${segment.name}",
                )
                return TombstoneResult.CAPACITY_FULL
            }
            val newContent = (oldLines + entry).joinToString("\n") + "\n"
            // P1-1：发布前投影实际总量 + 最坏 sidecar 增量，超限有界失败（FAILED → drain
            // 退避重试，绝不发布任何正式文件）。
            if (metadataWriteBudgetExceeded(context, newContent.toByteArray(Charsets.UTF_8).size)) {
                logE(
                    "statistics tombstone manifest publish refused: metadata budget over the " +
                        "total cap; segment stays in queue: ${segment.name}",
                )
                return TombstoneResult.FAILED
            }
            store.write(newContent)
            TombstoneResult.RECORDED
        } catch (e: Exception) {
            logE("statistics quarantine tombstone manifest write failed: ${segment.name}", e)
            TombstoneResult.FAILED
        }
    }

    /**
     * 移除/裁剪后崩溃安全重写 manifest；写失败仅记录（下一次 drain 会再尝试）。
     * P1-1：发布前投影实际总量 + 最坏 sidecar 增量，超限有界失败（只记录，不写文件）。
     */
    private suspend fun rewriteTombstoneManifest(context: Context, remainingRawLines: List<String>) {
        try {
            val content = remainingRawLines.joinToString("\n") + if (remainingRawLines.isEmpty()) "" else "\n"
            if (metadataWriteBudgetExceeded(context, content.toByteArray(Charsets.UTF_8).size)) {
                logE("statistics quarantine tombstone manifest rewrite refused: metadata budget over the total cap")
                return
            }
            summaryStore(File(spoolDir(context), TOMBSTONE_MANIFEST_NAME)).write(content)
        } catch (e: Exception) {
            logE("statistics quarantine tombstone manifest rewrite failed", e)
        }
    }

    /**
     * P1-2 维护/后台重试：清理删除失败被隔离的残留。ack trash 按持久状态机处置（**只有显式
     * COMMITTED 有界补删**；UNCOMMITTED 一律按 mapping+identity 回滚——P1-1：绝不根据主
     * manifest 缺失推断已提交；状态缺失/损坏或回滚长期失败的 trash 保留为 StuckAckEvidence
     * 由 UI 管理，绝不自动删除）；pending-delete 证据（容量内来源）移回完整证据区；tombstoned
     * 段按记录的处置动作重试（over-cap → 删除，容量内 → 移回完整证据区），处置前必须按稳定
     * identity（file+bytes+sha256）校验当前文件仍是记录的段（P1-2：同名不同 hash 的健康新段
     * 绝不删/移，只移除陈旧记录）。成功后从 manifest 移除记录；文件已物理消失也移除记录
     * （除非身份仍停留在未提交 trash 中——P1-2：绝不让崩溃窗口判定把未确认的证据误判为已提交
     * 而删除）。P1-4：存在无法完整严格解析/读取的 UNCOMMITTED ack trash（
     * [scanUncommittedTrashHolds] 的 hasUnknown）时，本轮**整轮跳过** stale 判定与 manifest
     * 重写（缺失/不匹配条目的身份可能正被其持有），记录日志并有界返回。
     *
     * P1-2 终审：**任何目录项变更（rename/delete）后 syncDir 非 OK 都不推进状态**——
     * 不返回 RECORDED、不移除 manifest 条目、不把变更视为完成：pending-delete 恢复 rename
     * 非 OK 时尽力把文件移回 pending-delete 名（重建明确可重试记录）、seal_failed 删除与
     * tombstone 处置非 OK 时保留 manifest 条目，并返回 false 让 drain 退避重试。由于文件
     * 操作可能已可见，下一轮 bootstrap gate（[ensureDirectoryDurabilityConfirmed]）sync OK
     * 后按 identity 幂等完成，绝不丢证据。失败仅记录（tombstone 本身就是有界可见错误证据），
     * 绝不阻塞健康排空——本函数返回 true 时 drain 继续处理健康段。
     *
     * @return false 表示本轮存在目录项未确认持久的变更（调用方 [drainCore] 退避重试）；
     *   其它失败（rename/delete 返回 false、状态无效、枚举失败）保留对应可重试记录并返回 true。
     */
    private suspend fun retryPendingCleanup(context: Context): Boolean {
        val dir = spoolDir(context)
        if (!dir.isDirectory) return true
        var roundOk = true
        // P1-3：不设 canonical isFile 前置——仅 sidecar 存在时也必须先恢复再处置受管段。
        // P1-2 fail-closed：manifest 不可读则维护中止并抛明确 IOException（drain 退避重试），
        // 绝不当作“无受管记录”继续——那会让扫描器把受管段当健康段处理；ack trash 的“已提交”
        // 判定也依赖主 manifest，不能拿空集冒充。manifest 不存在（从未有受管记录）视为空集。
        val manifestFile = File(dir, TOMBSTONE_MANIFEST_NAME)
        val rawLines = readMetadata(summaryStore(manifestFile), manifestFile)
        val lines = rawLines?.lineSequence()?.filter { it.isNotBlank() }?.toList() ?: emptyList()
        // reviewer P1：ack trash 状态机（只有显式 COMMITTED 有界补删；UNCOMMITTED 一律按
        // mapping+identity 回滚——P1-1：绝不根据主 manifest 缺失推断已提交，普通 quarantine
        // 证据从不在 manifest 中，缺失恒成立，旧推断会误删未确认的证据）。符号链接目录绝不
        // 进入处置（跟随链接可能删除链接目标的内容）。
        // P1-5：spool 根枚举失败（null）时本轮跳过 trash 处置并记录——枚举失败绝不是
        // “没有 trash 目录”，绝不静默放行；身份持有判定由 [scanUncommittedTrashHolds] 的
        // hasUnknown fail-closed 另行兜底（stale 清理整轮跳过）。
        val ackTrashCandidates = listDir(dir)
        if (ackTrashCandidates == null) {
            logE("statistics spool directory enumeration failed; deferring ack trash disposal")
        } else {
            ackTrashCandidates
                .filter { f ->
                    f.isDirectory &&
                        f.name.startsWith(ACK_TRASH_PREFIX) &&
                        !Files.isSymbolicLink(f.toPath())
                }
                .forEach { trash ->
                    if (!handleAckTrashDir(dir, trash)) roundOk = false
                }
        }
        // P1-2/P1-4：处置后仍停留在未提交 trash 中的身份 → manifest 条目绝不按 stale 移除；
        // 存在无法完整严格解析的 UNCOMMITTED trash 时（hasUnknown）本轮保守跳过 stale 处置
        val trashHold = scanUncommittedTrashHolds(context)
        val heldInTrash = trashHold.known
        dir.listFiles { f -> f.isFile && f.name.startsWith(PENDING_DELETE_PREFIX) }
            ?.forEach { file ->
                val target = File(
                    dir,
                    "$QUARANTINE_PREFIX${file.name.removePrefix(PENDING_DELETE_PREFIX)}",
                )
                if (renameForTest(file, target)) {
                    if (!requireSpoolDirSync(dir)) {
                        // P1-2 终审：rename 已可见但目录项未确认持久——不推进状态：尽力把文件
                        // 移回 pending-delete 名（重建明确可重试记录），并让本轮失败退避；崩溃
                        // 后文件在任一名字下都保留证据，下一轮按名字/身份幂等完成。P1-1：
                        // 非 OK 同时失效 gate。
                        logE(
                            "statistics pending-delete evidence restore rename not durable; " +
                                "restoring retryable record: ${target.name}",
                        )
                        val reverseRenamed = target.exists() && renameForTest(target, file)
                        if (reverseRenamed) {
                            // P2 终审：反向 rename 同样是目录项变更——未确认持久绝不算
                            // “已重建可重试记录”（变更可见时下一轮 bootstrap 重新确认后按
                            // 名字幂等完成）；失败保留 pending 记录并退避，绝不静默。
                            if (!requireSpoolDirSync(dir)) {
                                logE(
                                    "statistics pending-delete evidence restore reverse rename " +
                                        "not durable; keeping retryable record: ${file.name}",
                                )
                            }
                        } else if (target.exists()) {
                            logE(
                                "statistics pending-delete evidence restore reverse rename failed: ${file.name}",
                            )
                        }
                        roundOk = false
                    } else {
                        logE("statistics pending-delete evidence restored to quarantine: ${target.name}")
                    }
                }
            }
        // P2：seal copy 失败隔离的部分目标（seal_failed_*，scanner 忽略）：active 保留完整
        // 内容，删除隔离副本安全无数据损失；删除失败只记录（文件作为受管失败发布证据计入
        // 证据区，占用可见且有界——quarantineEvidence/导出/ack 可管理），下一轮维护再试。
        // P1-2 终审：删除成功但目录项未确认持久 → 本轮不推进（roundOk=false，退避重试）。
        dir.listFiles { f -> f.isFile && f.name.startsWith(SEAL_FAILED_PREFIX) }
            ?.forEach { file ->
                if (!(segmentDeleteForTest?.invoke(file) ?: file.delete())) {
                    logE(
                        "statistics spool seal-failed target cleanup deferred; " +
                            "visible as managed failed-publication evidence: ${file.name}",
                    )
                } else if (!requireSpoolDirSync(dir)) {
                    logE("statistics spool seal-failed target deletion not durable: ${file.name}")
                    roundOk = false
                }
            }
        if (lines.isEmpty()) return roundOk
        // P1-4 fail-closed：hasUnknown 时，缺失（!file.exists()）或不匹配（MISMATCH）条目
        // 的身份可能正被无法解析的 ack trash 持有——本轮绝不移除任何这类 manifest 条目。
        // 整轮跳过 MATCH 处置与 manifest 重写（简单正确），记录日志并有界返回（不持锁
        // 等待）；trash 状态恢复后下一轮维护再清理。
        if (trashHold.hasUnknown) {
            logE(
                "statistics ack trash state partially unknown; deferring tombstone stale " +
                    "cleanup and manifest rewrite this round",
            )
            return roundOk
        }
        val remaining = lines.filterNot { line ->
            val entry = parseTombstoneLine(line) ?: return@filterNot false
            val file = File(dir, entry.file)
            when {
                // 物理消失：P1-2 先查未提交 trash——身份在其中时条目必须保留（证据仍存在，
                // 等待回滚或提交判定），绝不按 stale 移除。P1-2 终审：删除/移动可能在上轮
                // 可见但未确认持久——本轮先 sync 确认“消失”持久才允许移除条目。P1-1：
                // 非 OK 同时失效 gate。
                !file.exists() -> {
                    val confirmed = requireSpoolDirSync(dir)
                    if (!confirmed) {
                        logE(
                            "statistics tombstone entry absence not durable; retaining entry: ${entry.file}",
                        )
                        roundOk = false
                    }
                    confirmed && heldInTrash[entry.file] != (entry.bytes to entry.sha256)
                }
                else -> when (entry.identityCheck(file)) {
                    // P1-2：身份不可校验（UNREADABLE）→ 保留记录与文件，本轮不处置
                    IdentityCheck.UNREADABLE -> false
                    // 身份不匹配：同名新文件 ≠ 陈旧记录的前提是旧身份已无处可寻；旧身份仍
                    // 停留在未提交 trash 中时条目必须保留（崩溃窗口判定依赖它），只处置新文件
                    IdentityCheck.MISMATCH -> heldInTrash[entry.file] != (entry.bytes to entry.sha256)
                    IdentityCheck.MATCH ->
                        if (entry.overCap) {
                            val deleted =
                                (segmentDeleteForTest?.invoke(file) ?: file.delete()) || !file.exists()
                            // P1-2 终审：删除成功但目录项未确认持久 → 保留 manifest 条目
                            // （可重试记录）并让本轮失败退避；绝不带着未确认删除推进状态。
                            if (!deleted) {
                                false
                            } else if (!requireSpoolDirSync(dir)) {
                                logE(
                                    "statistics tombstone over-cap segment deletion not durable; " +
                                        "keeping entry: ${file.name}",
                                )
                                roundOk = false
                                false
                            } else {
                                true
                            }
                        } else {
                            // 容量内：重试移回完整证据区（不超硬 cap 才允许）
                            val target =
                                File(dir, "$QUARANTINE_PREFIX${UUID.randomUUID().toString().replace("-", "")}_${entry.file}")
                            val fits =
                                quarantineEvidenceLocked(context).sumOf { it.length() } + file.length() <= MAX_QUARANTINE_BYTES
                            val renamed = fits && renameForTest(file, target)
                            // P1-2 终审：rename 成功但目录项未确认持久 → 保留 manifest 条目
                            // （可重试记录）并让本轮失败退避；rename 可见时下一轮按消失条目
                            // 路径 sync 确认后幂等移除。
                            if (!renamed) {
                                false
                            } else if (!requireSpoolDirSync(dir)) {
                                logE(
                                    "statistics tombstone evidence restore rename not durable; " +
                                        "keeping entry: ${target.name}",
                                )
                                roundOk = false
                                false
                            } else {
                                true
                            }
                        }
                }
            }
        }
        if (remaining.size == lines.size) return roundOk
        try {
            val newContent = remaining.joinToString("\n") + if (remaining.isEmpty()) "" else "\n"
            // P1-1：发布前投影实际总量 + 最坏 sidecar 增量，超限有界失败（只记录，不写文件）
            if (metadataWriteBudgetExceeded(context, newContent.toByteArray(Charsets.UTF_8).size)) {
                logE("statistics quarantine tombstone manifest rewrite refused: metadata budget over the total cap")
                return roundOk
            }
            // P1-2 终审：manifest 重写是目录项变更（严格 store）——写失败即保留全部条目
            // （不移除 manifest），并让本轮失败退避重试。
            summaryStore(manifestFile).write(newContent)
        } catch (e: Exception) {
            logE("statistics quarantine tombstone manifest rewrite failed", e)
            roundOk = false
        }
        return roundOk
    }

    /**
     * ack trash 状态机处置（P1-2，调用方持 lifecycleMutex）。trash 目录内的原子状态文件
     * [ACK_TRASH_STATE_FILE_NAME] 首行为 UNCOMMITTED/COMMITTED，后续为 mapping 行
     * （原名 → trash 名 + bytes + sha256）。规则（P1-1 修复）：
     * - 状态文件尚未写入（无 canonical 与 sidecar）且目录为空：staging 严格发生在状态写入
     *   成功之后，此时不可能有已 stage 的证据 → 空目录直接删除（无证据损失）。
     * - COMMITTED：唯一允许后台删除的状态——有界补删，失败下次再试。
     * - UNCOMMITTED：**一律**按 mapping+identity 回滚到原路径，绝不根据主 manifest 缺失推断
     *   已提交（普通 quarantine 证据从不在 manifest 中，缺失恒成立，旧推断会把未确认的证据
     *   误删；主 manifest 已发布但 COMMITTED marker 未写时，回滚的损坏 sealed 会被扫描器重新
     *   隔离，ack 视失败但不丢证据）。mapping 必须全有或全无有效（P1-2：逐行解析、无重复、
     *   无穿越、与 trash 内证据文件集合完整对应），任一失败 → 整个 trash fail-closed 保留
     *   （由 UI 作为 StuckAckEvidence 管理），绝不执行 delete/rollback/manifest 改动。
     * - 状态文件缺失/不可读/无效：非空 trash 保留并报告（fail-closed），绝不删除。
     *
     * P1-2 终审：删除成功（空目录/COMMITTED）或回滚移动后目录项必须确认持久——
     * [syncDir] 非 OK 返回 false（调用方 [retryPendingCleanup] 令本轮退避重试，绝不推进）；
     * 删除返回 false 只记录（trash 本身就是可重试记录，下一轮再试）。
     *
     * @return false 表示本轮存在目录项未确认持久的变更；其余情形（含删除失败、状态无效）
     *   返回 true（保留可重试记录，不阻塞健康排空）。
     */
    private suspend fun handleAckTrashDir(dir: File, trash: File): Boolean {
        val stateFile = File(trash, ACK_TRASH_STATE_FILE_NAME)
        val store = summaryStore(stateFile)
        val content: String? =
            if (stateFile.exists() ||
                File(trash, "$ACK_TRASH_STATE_FILE_NAME.new").exists() ||
                File(trash, "$ACK_TRASH_STATE_FILE_NAME.bak").exists()
            ) {
                try {
                    readMetadata(store, stateFile)
                } catch (e: Exception) {
                    logE("statistics ack trash state unreadable; retaining trash: ${trash.name}", e)
                    null
                }
            } else if (listDir(trash)?.isEmpty() == true) {
                // 崩溃于 stage 开始之前：trash 内没有任何证据文件（stage 是原子移动，空目录
                // = 无证据可保护），删除空目录无损失。枚举失败（listDir 返回 null）绝不当空
                // 目录——内容不可知时走下方 fail-closed 保留分支，绝不删除。stage 已开始或
                // 完成后崩溃（目录非空、状态未写）→ 同样走到保留分支。
                if (!(spoolDeleteForTest?.invoke(trash) ?: deleteAckTrashDirNoFollow(trash))) {
                    logE("statistics empty ack trash cleanup deferred: ${trash.name}")
                } else if (!requireSpoolDirSync(dir)) {
                    logE("statistics empty ack trash deletion not durable: ${trash.name}")
                    return false
                }
                return true
            } else {
                null
            }
        if (content == null) {
            logE("statistics ack trash state missing; retaining trash: ${trash.name}")
            return true
        }
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        return when (lines.firstOrNull()) {
            ACK_STATE_COMMITTED -> {
                if (!(spoolDeleteForTest?.invoke(trash) ?: deleteAckTrashDirNoFollow(trash))) {
                    logE("statistics ack trash cleanup deferred: ${trash.name}")
                    true
                } else if (!requireSpoolDirSync(dir)) {
                    logE("statistics ack trash deletion not durable: ${trash.name}")
                    false
                } else {
                    true
                }
            }
            ACK_STATE_UNCOMMITTED -> {
                // P1-2：全有或全无解析——任一损坏/重复/穿越/对应缺失都使整个 trash
                // fail-closed 保留，绝不基于部分 mapping 做破坏性决策。
                val entries = parseAckMappingStrict(dir, trash, lines)
                    ?: run {
                        logE("statistics ack trash state mapping invalid; retaining trash: ${trash.name}")
                        return true
                    }
                // P1-1：UNCOMMITTED 永远尝试回滚（identity 验证，目标被不同内容占用绝不覆盖）。
                val result = rollbackUncommittedTrash(dir, trash, entries)
                if (!result.allResolved) {
                    logE(
                        "statistics ack trash rollback not fully resolved; " +
                            "retaining retryable trash: ${trash.name}",
                    )
                }
                // P1-2 终审：回滚移动/删除的目录项未确认持久 → 本轮失败退避（trash 保留为
                // 可重试记录，下一轮按 identity 幂等完成）；普通回滚失败（移动失败、槽位被
                // 占用等）保留记录并继续本轮，绝不阻塞健康排空。
                !result.syncFailed
            }
            else -> {
                logE("statistics ack trash state invalid (${lines.firstOrNull() ?: "<empty>"}); retaining trash: ${trash.name}")
                true
            }
        }
    }

    /**
     * 未提交 trash 回滚结果（P1-2 终审）：allResolved=false 表示有文件无法恢复（trash 保留
     * 为可重试记录）；syncFailed=true 表示存在目录项未确认持久的变更（本轮必须退避重试）。
     */
    private data class TrashRollbackResult(
        val allResolved: Boolean,
        val syncFailed: Boolean,
    )

    /**
     * 未提交 trash 回滚（P1-2，调用方持 lifecycleMutex）：按 mapping 逐条 identity 验证后
     * 恢复。trash 内文件必须仍与 mapping 身份（bytes+sha256）一致才允许移动；原槽位被不同
     * 内容占用时绝不覆盖（保留 trash 证据并 fail-closed）；全部恢复成功才删除 trash 目录，
     * 否则保留（递归容量统计计入占用）并报告。无法恢复的文件绝不删除。
     *
     * P1-2 终审：每个移动/删除都是目录项变更——成功后 [syncDir] 非 OK 置 syncFailed
     * （调用方本轮退避；变更可见时下一轮按 identity 幂等完成，崩溃后 trash 重现由状态机
     * 重放），绝不基于未确认状态声称已恢复。
     */
    private fun rollbackUncommittedTrash(
        dir: File,
        trash: File,
        entries: List<AckMappingEntry>,
    ): TrashRollbackResult {
        var allResolved = true
        var syncFailed = false
        for (entry in entries) {
            // 防御：mapping 名字必须是 spool 根/trash 内的合法单层文件名
            if (!isSafeEvidenceName(dir, entry.original) || !isSafeTrashName(trash, entry.trashName)) {
                allResolved = false
                logE("statistics ack trash mapping has unsafe names; retaining trash: ${trash.name}")
                continue
            }
            val trashFile = File(trash, entry.trashName)
            val original = File(dir, entry.original)
            if (!trashFile.exists()) {
                // mapping 有记录但 trash 中无此文件：文件从未被 stage（状态/映射写于 staging
                // 之前）。原槽位同身份即视为已恢复；否则无法验证 → 保留。
                if (!(original.exists() && identityMatches(original, entry))) {
                    allResolved = false
                    logE("statistics ack trash rollback cannot verify ${entry.original}; retaining trash: ${trash.name}")
                }
                continue
            }
            if (!identityMatches(trashFile, entry)) {
                allResolved = false
                logE("statistics ack trash file identity mismatch; retaining evidence: ${entry.original}")
                continue
            }
            when {
                !original.exists() -> {
                    if (!atomicMoveForAck(trashFile, original)) {
                        allResolved = false
                        logE("statistics ack trash rollback move failed for ${entry.original}; evidence stays in ${trash.name}")
                    } else if (!requireSpoolDirSync(dir, trash)) {
                        logE("statistics ack trash rollback move not durable: ${entry.original}")
                        syncFailed = true
                    }
                }
                identityMatches(original, entry) -> {
                    // 原槽位已是同身份内容：trash 副本冗余，删除副本即可
                    if (!(segmentDeleteForTest?.invoke(trashFile) ?: trashFile.delete())) {
                        allResolved = false
                        logE("statistics ack trash redundant copy deletion failed: ${entry.original}")
                    } else if (!requireSpoolDirSync(trash)) {
                        logE("statistics ack trash redundant copy deletion not durable: ${entry.original}")
                        syncFailed = true
                    }
                }
                else -> {
                    // 原槽位被不同内容占用：绝不覆盖，保留 trash 证据并 fail-closed
                    allResolved = false
                    logE("statistics ack trash rollback target occupied by different content; retaining evidence: ${entry.original}")
                }
            }
        }
        if (allResolved) {
            if (!(spoolDeleteForTest?.invoke(trash) ?: deleteAckTrashDirNoFollow(trash))) {
                logE("statistics ack trash deletion failed after successful rollback: ${trash.name}")
            } else if (!requireSpoolDirSync(dir)) {
                logE("statistics ack trash deletion not durable after rollback: ${trash.name}")
                syncFailed = true
            }
        }
        return TrashRollbackResult(allResolved, syncFailed)
    }

    /** P1-2：文件与 mapping 身份比对（bytes + 原始字节 SHA-256，绝不跟随符号链接）。 */
    private fun identityMatches(file: File, entry: AckMappingEntry): Boolean {
        if (!file.isFile || file.length() != entry.bytes) return false
        if (Files.isSymbolicLink(file.toPath())) return false
        if (!Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
        if (segmentReadErrorForTest?.invoke(file) == true) return false
        return try {
            sha256Hex(file.readBytes()) == entry.sha256
        } catch (e: Exception) {
            false
        }
    }

    /** P1-4：未提交 ack trash 扫描结果：已知身份 + 是否存在无法完整严格解析/读取的 trash。 */
    private data class UncommittedTrashScan(
        val known: Map<String, Pair<Long, String>>,
        val hasUnknown: Boolean,
    )

    /**
     * P1-2：仍在未提交 trash 中的身份集合（原名 → bytes+sha256），供 manifest 条目的 stale
     * 判定使用——身份仍在 trash 中时条目绝不能移除，否则会把未确认的证据误判为已提交而删除。
     * 符号链接目录不读取（不跟随）。调用方持 lifecycleMutex。
     *
     * P1-4 fail-closed：返回结构化结果。任何**非空**、非明确 COMMITTED、无法完整严格解析/
     * 读取的 ack trash（状态缺失/不可读、首行非法、mapping 任一损坏/缺身份/不安全/重复、
     * trash 内存在 mapping 未覆盖的证据文件）都会让 [UncommittedTrashScan.hasUnknown] = true，
     * 调用方据此保守处置（stale 清理整轮跳过、scanner 不按 MISMATCH 移除条目）——该 trash
     * 可能正持有已知集合之外的证据身份，绝不基于残缺信息做破坏性决策。mapping 完整严格
     * 解析成功时按全有或全无计入 [UncommittedTrashScan.known]（与 [parseAckMappingStrict]
     * 一致）。空目录不可能持有证据（stage 是原子移动，见 [handleAckTrashDir]），不贡献
     * 身份也不置 unknown。
     */
    private suspend fun scanUncommittedTrashHolds(context: Context): UncommittedTrashScan {
        val dir = spoolDir(context)
        if (!dir.isDirectory) return UncommittedTrashScan(emptyMap(), false)
        val result = HashMap<String, Pair<Long, String>>()
        var hasUnknown = false
        // P1-5 fail-closed：spool 根枚举失败（null）时，任何 ack trash 目录都可能存在但
        // 不可见——身份持有情况完全不可知，置 unknown 阻止调用方做 stale/删除类决策。
        val rootFiles = listDir(dir)
        if (rootFiles == null) {
            logE(
                "statistics spool directory enumeration failed; treating uncommitted ack trash " +
                    "state as unknown",
            )
            return UncommittedTrashScan(emptyMap(), true)
        }
        rootFiles
            .filter { f ->
                f.isDirectory &&
                    f.name.startsWith(ACK_TRASH_PREFIX) &&
                    !Files.isSymbolicLink(f.toPath())
            }
            .forEach { trash ->
                val files = listDir(trash)
                if (files == null) {
                    // 目录枚举失败：内部证据状态不可知 → fail-closed
                    hasUnknown = true
                    return@forEach
                }
                if (files.isEmpty()) return@forEach
                val stateFile = File(trash, ACK_TRASH_STATE_FILE_NAME)
                val content = try {
                    readMetadata(summaryStore(stateFile), stateFile)
                } catch (e: Exception) {
                    logE("statistics ack trash state unreadable during scan; treating as unknown", e)
                    null
                } ?: run {
                    // 状态缺失/不可读：非空 trash 中的证据身份不可知
                    hasUnknown = true
                    return@forEach
                }
                val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
                when (lines.firstOrNull()) {
                    // 显式 COMMITTED：删除已授权，无回滚保护义务，不贡献身份也不置 unknown
                    ACK_STATE_COMMITTED -> Unit
                    ACK_STATE_UNCOMMITTED -> {
                        val entries = parseAckMappingStrict(dir, trash, lines)
                        if (entries == null) {
                            // 全有或全无解析失败：该 trash 可能持有任意身份的证据
                            hasUnknown = true
                        } else {
                            entries.forEach { result[it.original] = it.bytes to it.sha256 }
                        }
                    }
                    // 首行非法/内容为空：无法判定状态 → fail-closed
                    else -> hasUnknown = true
                }
            }
        return UncommittedTrashScan(result, hasUnknown)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
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
    private suspend fun insertSafely(
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
    private fun resolveDaoSafely(context: Context): TokenStatsDao? {
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

    private fun spoolDir(context: Context) = File(context.filesDir, SPOOL_DIR_NAME)

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
    private fun listDir(dir: File): Array<File>? {
        val seam = directoryListingForTest
        return if (seam != null) seam(dir) else dir.listFiles()
    }

    /**
     * 全部 spool 管理文件的实际字节总和（P1-1 修复：递归）：覆盖 spool 根下所有子目录
     * （ack trash 等），只计 regular file，绝不跟随符号链接（NOFOLLOW_LINKS：链接按链接
     * 本身处理，符号链接目录不进入遍历）。总和超过 [cap] 或 Long 溢出时饱和返回 cap+1——
     * 调用方投影必拒绝，无需精确值；目录不存在返回 0；遍历失败按超限处理（fail-closed，
     * 绝不因扫描失败而低估容量）。文件数受总 cap 约束有界，无需维护缓存。
     */
    private fun totalSpoolBytes(dir: File, cap: Long): Long {
        if (!dir.isDirectory) return 0L
        val saturated = if (cap == Long.MAX_VALUE) cap else cap + 1L
        var total = 0L
        val visitor = object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) {
                    total += attrs.size()
                    if (total < 0L || total > cap) {
                        total = saturated
                        return FileVisitResult.TERMINATE
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                total = saturated
                return FileVisitResult.TERMINATE
            }
        }
        return try {
            Files.walkFileTree(
                dir.toPath(),
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                visitor,
            )
            total
        } catch (e: IOException) {
            saturated
        }
    }

    /**
     * 数据准入上限（P1-1）= 总上限 − 元数据预留。测试注入更小的总上限时预留同步收缩
     * （至少为数据保留一条完整行 [MAX_LINE_BYTES] 的空间，避免准入区间为负），生产值
     * 恒等于 [METADATA_RESERVE_BYTES]。
     */
    private fun dataAdmissionMaxBytes(cap: Long): Long {
        val reserve = minOf(METADATA_RESERVE_BYTES, cap - MAX_LINE_BYTES).coerceAtLeast(0L)
        return (cap - reserve).coerceAtLeast(0L)
    }

    /**
     * 元数据发布预算（P1-1，调用方持 lifecycleMutex）：发布 contentBytes 元数据时，最坏
     * 瞬时增量 = [METADATA_COPY_COUNT] × contentBytes（canonical/.new/.bak/tmp 四个槽位可能
     * 短暂同时各持一份完整副本）。投影“实际 [totalSpoolBytes]（递归含 ack trash）+ 该增量”
     * 仍 ≤ 总上限才允许发布，否则调用方有界失败且不写任何正式文件（sidecar 也不写）。spool
     * 内所有元数据读写都持 lifecycleMutex，任意时刻至多一个 AtomicRestoreMarkerStore 写进行中
     * （Atomic tmp 唯一文件并发数 = 1），因此按单写者投影即可证明全部实际字节恒 ≤ 总上限。
     */
    private fun metadataWriteBudgetExceeded(context: Context, contentBytes: Int): Boolean {
        val cap = totalSpoolMaxBytesForTest ?: TOTAL_SPOOL_MAX_BYTES
        return totalSpoolBytes(spoolDir(context), cap) + contentBytes.toLong() * METADATA_COPY_COUNT > cap
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

    private fun logE(message: String, error: Throwable? = null) {
        try {
            if (error == null) AppLogger.e(TAG, message) else AppLogger.e(TAG, message, error)
        } catch (_: Throwable) {
        }
    }
}

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

/** Internal Quarantine responsibilities extracted from [TokenStatSpool]. */
/** Stable identity of a managed failed segment. */
internal data class TombstoneEntry(
    val file: String,
    val bytes: Long,
    val sha256: String,
    val overCap: Boolean,
)

internal fun TokenStatSpool.tombstoneIdentityCheck(
    entry: TombstoneEntry,
    file: File,
): IdentityCheck {
    if (entry.sha256.isEmpty() || !file.isFile || file.length() != entry.bytes) {
        return IdentityCheck.MISMATCH
    }
    if (segmentReadErrorForTest?.invoke(file) == true) return IdentityCheck.UNREADABLE
    return try {
        if (sha256Hex(file.readBytes()) == entry.sha256) IdentityCheck.MATCH else IdentityCheck.MISMATCH
    } catch (e: Exception) {
        IdentityCheck.UNREADABLE
    }
}
internal enum class TombstoneResult { RECORDED, CAPACITY_FULL, FAILED }
internal suspend fun TokenStatSpool.quarantineEvidenceLocked(context: Context): List<File> {
    val dir = spoolDir(context.applicationContext)
    val managed = readTombstoneLines(context).mapNotNull { line ->
        val entry = parseTombstoneLine(line) ?: return@mapNotNull null
        val file = File(dir, entry.file)
        // P1-2：只有身份可校验（MATCH）的受管段才作为 evidence 暴露——UNREADABLE 绝不
        // 出现在可导出/可 ack 的列表里（身份不可校验时 ack 无法安全删除），保留 manifest。
        file.takeIf { it.isFile && tombstoneIdentityCheck(entry, file) == IdentityCheck.MATCH }
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
internal fun TokenStatSpool.quarantineAreaFiles(dir: File): List<File> {
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
internal suspend fun TokenStatSpool.managedFailureCapacityExceeded(context: Context): Boolean {
    val dir = spoolDir(context)
    val entries = readTombstoneLines(context).mapNotNull(::parseTombstoneLine)
    if (entries.isEmpty() && quarantineAreaFiles(dir).isEmpty()) return false
    return entries.size >= MAX_TOMBSTONE_ENTRIES ||
        entries.sumOf { it.bytes.coerceAtLeast(0L) } >= MAX_MANAGED_BYTES ||
        quarantineAreaFiles(dir).sumOf { it.length() } >= MAX_QUARANTINE_BYTES
}
internal suspend fun TokenStatSpool.quarantineSummaryInfoLocked(
    context: Context,
): TokenStatSpool.QuarantineSummaryInfo? {
    val file = File(spoolDir(context.applicationContext), QUARANTINE_SUMMARY_NAME)
    val content = try {
        readMetadata(summaryStore(file), file)
    } catch (e: Exception) {
        logE("statistics quarantine summary read failed", e)
        null
    } ?: return null
    val lines = content.lineSequence().filter { it.isNotEmpty() }.toList()
    if (lines.isEmpty()) return null
    return TokenStatSpool.QuarantineSummaryInfo(
        recordCount = lines.size,
        summaryBytes = file.length(),
    )
}
internal fun TokenStatSpool.summaryStore(file: File) =
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
internal suspend fun TokenStatSpool.readMetadata(store: AtomicRestoreMarkerStore, file: File): String? {
    if (metadataReadErrorForTest?.invoke(file) == true) {
        throw IOException("statistics metadata read failed (injected): ${file.name}")
    }
    return store.read()
}
/** 首选同目录原子替换（Windows MoveFileEx / POSIX rename）；不支持或失败返回 false 走回退。 */
internal fun TokenStatSpool.atomicMoveReplacing(from: File, to: File): Boolean = try {
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
/** Explicit post-export acknowledgment for the bounded rolling summary and every sidecar. */
internal suspend fun TokenStatSpool.deleteQuarantineSummaryLocked(context: Context) {
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
 * 超限损坏段的硬边界替换（P1-1/P1-2）：崩溃安全地发布“已裁剪到双上限”的新完整摘要
 * （旧完整或新完整，绝不截断），随后把段移出 sealed 扫描队列；段删除失败绝不阻塞健康
 * 排空（改为 pending-delete 证据或 tombstone 跳过）。摘要发布失败抛异常 → 保留旧摘要
 * 与待处理段，返回 false，绝不声称成功。
 */
internal suspend fun TokenStatSpool.summarizeOverCapSegment(
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
internal fun TokenStatSpool.buildTrimmedSummary(oldLines: List<String>, record: String): String {
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
internal fun TokenStatSpool.utf8RecordBytes(line: String): Int =
    line.toByteArray(Charsets.UTF_8).size + 1
internal fun TokenStatSpool.normalizeOversizedSummaryLine(line: String): String {
    if (utf8RecordBytes(line) <= MAX_QUARANTINE_SUMMARY_BYTES) return line
    val bytes = line.toByteArray(Charsets.UTF_8)
    return JSONObject()
        .put("truncated", true)
        .put("bytes", bytes.size)
        .put("sha256", sha256Hex(bytes))
        .toString()
}
internal suspend fun TokenStatSpool.disposeOverCapSegment(
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
internal suspend fun TokenStatSpool.readTombstoneLines(context: Context): List<String> {
    val manifestFile = File(spoolDir(context), TOMBSTONE_MANIFEST_NAME)
    val content = readMetadata(summaryStore(manifestFile), manifestFile) ?: return emptyList()
    return content.lineSequence().filter { it.isNotBlank() }.toList()
}
internal fun TokenStatSpool.parseTombstoneLine(line: String): TombstoneEntry? = try {
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
internal enum class IdentityCheck { MATCH, MISMATCH, UNREADABLE }
/**
 * 有界 skip/tombstone manifest 更新（P1-1/P1-2）：**不滚动**的活跃受管失败集合，条目
 * 只在文件物理消失/身份变化后由维护入口移除；达到 entry/字节硬上限时返回
 * [TombstoneResult.CAPACITY_FULL]（调用方跳过该段继续健康，新统计 append 随后被拒绝），
 * 写失败返回 [TombstoneResult.FAILED]（drain 退避重试），绝不静默放行。
 */
internal suspend fun TokenStatSpool.tombstoneSegment(
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
internal suspend fun TokenStatSpool.rewriteTombstoneManifest(context: Context, remainingRawLines: List<String>) {
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

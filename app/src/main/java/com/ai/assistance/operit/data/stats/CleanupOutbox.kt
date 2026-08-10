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

/** Internal CleanupOutbox responsibilities extracted from [TokenStatSpool]. */
internal fun TokenStatSpool.stuckAckTrashEvidenceLocked(context: Context): List<File> {
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
/**
 * ack 的主 manifest 重写（P1-1，调用方持 lifecycleMutex）：发布前投影实际总量 + 最坏
 * sidecar 增量，超限有界失败不写文件；测试注入缝照常生效。失败抛 [IOException]。
 */
internal suspend fun TokenStatSpool.rewriteAckManifestLocked(context: Context, manifestFile: File, newContent: String) {
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
internal suspend fun TokenStatSpool.writeUncommittedTrashState(
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
internal data class RollbackStagedResult(
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
internal fun TokenStatSpool.rollbackStagedRenames(
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
internal fun TokenStatSpool.requireSafeEvidenceName(dir: File, name: String) {
    if (!isSafeEvidenceName(dir, name)) {
        throw IOException("unsafe acknowledged evidence name: $name")
    }
}
/** 单层相对名检查（reviewer P1，防目录穿越）：非空、不含分隔符、解析后父目录是 dir。 */
internal fun TokenStatSpool.isSafeEvidenceName(dir: File, name: String): Boolean =
    name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        File(dir, name).parentFile?.canonicalFile == dir.canonicalFile
/** trash 内文件名检查（P1-2）：同 [isSafeEvidenceName]，父目录必须是 trash 目录本身。 */
internal fun TokenStatSpool.isSafeTrashName(trash: File, name: String): Boolean =
    name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        !name.contains('/') &&
        !name.contains('\\') &&
        File(trash, name).parentFile?.canonicalFile == trash.canonicalFile
internal data class AckMappingEntry(
    val original: String,
    val trashName: String,
    val bytes: Long,
    val sha256: String,
)
internal fun TokenStatSpool.ackMappingLine(entry: AckMappingEntry): String =
    JSONObject()
        .put("o", entry.original)
        .put("t", entry.trashName)
        .put("b", entry.bytes)
        .put("s", entry.sha256)
        .toString()
internal fun TokenStatSpool.parseAckMappingLine(line: String): AckMappingEntry? = try {
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
internal fun TokenStatSpool.parseAckMappingStrict(
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
internal fun TokenStatSpool.buildAckStateContent(state: String, entries: List<AckMappingEntry>): String =
    state + "\n" + entries.joinToString("\n") { ackMappingLine(it) } + "\n"
/** ack 只管理 spool 根目录中的普通文件，不跟随符号链接或其他特殊路径。 */
internal fun TokenStatSpool.requireManageableEvidenceFile(dir: File, file: File) {
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
internal fun TokenStatSpool.requireAckTrashDirForDelete(dir: File, trash: File) {
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
internal fun TokenStatSpool.deleteAckTrashDirNoFollow(trash: File): Boolean {
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
internal fun TokenStatSpool.atomicMoveForAck(from: File, to: File): Boolean {
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
internal suspend fun TokenStatSpool.retryPendingCleanup(context: Context): Boolean {
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
            else -> when (tombstoneIdentityCheck(entry, file)) {
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
internal suspend fun TokenStatSpool.handleAckTrashDir(dir: File, trash: File): Boolean {
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
internal data class TrashRollbackResult(
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
internal fun TokenStatSpool.rollbackUncommittedTrash(
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
internal fun TokenStatSpool.identityMatches(file: File, entry: AckMappingEntry): Boolean {
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
internal data class UncommittedTrashScan(
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
internal suspend fun TokenStatSpool.scanUncommittedTrashHolds(context: Context): UncommittedTrashScan {
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

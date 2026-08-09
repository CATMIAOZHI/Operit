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

/** Internal SpoolRecovery responsibilities extracted from [TokenStatSpool]. */
internal enum class SealPublishResult { PUBLISHED, EXISTS, FAILED }
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
internal suspend fun TokenStatSpool.sealActive(context: Context, dir: File): Boolean {
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
internal suspend fun TokenStatSpool.publishSealedNoReplace(
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
internal fun TokenStatSpool.publishSealedAfterHardLink(
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
internal suspend fun TokenStatSpool.publishSealedByCopy(
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
internal fun TokenStatSpool.deleteActiveAfterPublish(active: File): Boolean =
    sealActiveDeleteForTest?.invoke(active) ?: active.delete()
/**
 * seal 前置失败回滚（P2 终审）：删除刚发布的 target（active 仍是完整内容持有者，删除
 * 安全无数据损失），删除后必须经 [requireSpoolDirSync] 确认目录项持久——删除是目录项
 * 变更，未确认持久绝不视为回滚完成（P1-1：非 OK 同时失效 bootstrap gate，下一次使用
 * 重新确认）。返回 false 表示回滚未完成/未确认（target 删除失败或目录项未确认持久），
 * 调用方保持失败状态；残留由 [recoverSealDuplicates] 按 inode/内容去重兜底。
 */
internal fun TokenStatSpool.rollbackSealTarget(dir: File, target: File, kind: String): Boolean {
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
internal suspend fun TokenStatSpool.disposeFailedCopyTarget(
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
internal suspend fun TokenStatSpool.tombstonePartialTarget(
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
internal fun TokenStatSpool.isPrefixOf(partial: File, source: File): Boolean {
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
internal fun TokenStatSpool.recoverSealDuplicates(dir: File, active: File): Boolean {
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
/**
 * A complete line always ends with '\n'; every append writes whole lines, so a file ending
 * with '\n' has no partial tail. Only the final write of a crash can leave a tail without one.
 */
internal fun TokenStatSpool.activeEndsWithLineBreak(active: File): Boolean =
    RandomAccessFile(active, "r").use { raf ->
        raf.seek(raf.length() - 1L)
        raf.read() == '\n'.code
    }
/** 下一个建议的 sealed 编号；目录枚举失败（null）返回 null → seal 必须失败（P1-7）。 */
internal fun TokenStatSpool.nextSealIndex(dir: File): Long? {
    val files = listDir(dir) ?: return null
    return files.mapNotNull { file ->
        Regex("(?:quarantine_[^_]+_)?sealed_(\\d+)\\.jsonl").matchEntire(file.name)
            ?.groupValues?.get(1)?.toLongOrNull()
    }.maxOrNull()?.plus(1L) ?: 1L
}

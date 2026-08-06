package com.ai.assistance.operit.data.backup

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 备份恢复完成 → 统计受控补导的登记协调器。
 *
 * 由 RawSnapshotBackupManager 的两个恢复入口（restoreFromBackupUri /
 * restoreFromBackupFile）在**目录/偏好替换完成后**共用调用
 * [registerAfterRestore]：
 *
 * 1. 先崩溃安全地登记 restore generation（[AtomicRestoreMarkerStore]，
 *    不依赖 android.util，JVM 可测）；
 * 2. 登记**成功后才**完成 recovery state（REPLACING/FAILED/NEEDS_RECOVERY →
 *    IDLE；RESTORE_REPLACED → 其 finalState）。若登记失败则抛异常，恢复状态
 *    保持 RESTORE_REPLACED，冷启动自动补登记（无需重新替换目录）。
 *
 * 因此“目录替换成功”与“登记成功”绑定为同一个状态转换原子前提：绝不会出现
 * 恢复已标记完成但补导信号丢失的情况。marker 文件位于 noBackupFilesDir（不被
 * 备份/恢复覆盖）。
 *
 * 消费端（TokenBaselineImportRunner.consumePendingRestore）：读取 generation 后
 * 在同一 Room 事务内受控补导并记录已应用 generation；空/损坏 marker 不静默删除
 * （会丢失信号），而是重新登记一个新 generation 安全重试。
 */
object RestoreCompletionCoordinator {
    private const val TAG = "RestoreCompletion"
    private const val MARKER_FILE = "token_stats_restore_pending.txt"

    /** 测试注入缝：null 时用真实文件实现（noBackupFilesDir）。 */
    internal var markerStoreProvider: ((Context) -> RestoreMarkerStore)? = null

    /**
     * 测试注入缝：默认实现与 MigrationStateStore 的 recovery 完成语义一致
     * （REPLACING/FAILED/NEEDS_RECOVERY → IDLE；RESTORE_REPLACED → finalState）。
     * 测试注入 fake 验证调用顺序。
     */
    internal var recoveryStateCompleter: (suspend (Context) -> Unit)? = null

    /**
     * 备份恢复成功路径的收尾：先崩溃安全登记 generation，再完成 recovery state。
     * @throws Exception marker 登记失败（调用方保持 RESTORE_REPLACED 状态，冷启动
     *   自动补登记，不完成 recovery）。
     */
    suspend fun registerAfterRestore(context: Context) {
        val store = markerStore(context)
        val generation = UUID.randomUUID().toString()
        store.write(generation)
        // 登记成功后才允许完成 recovery state：登记失败不会走到这里。
        completeRecoveryState(context)
        AppLogger.i(TAG, "备份恢复完成，登记受控补导 generation=$generation")
    }

    /**
     * 读取待消费的 restore generation。无 marker 返回 null。
     * 空/损坏 marker 不会静默删除（避免信号永久丢失）：重新登记一个新
     * generation（原子写回）并返回，视为安全重试。
     */
    suspend fun readPendingGeneration(context: Context): String? {
        val store = markerStore(context)
        val raw = store.read() ?: return null
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) return trimmed
        val generation = UUID.randomUUID().toString()
        store.write(generation)
        AppLogger.w(TAG, "受控补导 marker 为空/损坏，重新登记 generation=$generation")
        return generation
    }

    /** 消费完成后删除 marker（幂等锚点在数据库侧，此处删除仅清 pending）。 */
    suspend fun consumeMarker(context: Context) {
        markerStore(context).delete()
    }

    private suspend fun completeRecoveryState(context: Context) {
        val completer = recoveryStateCompleter
        if (completer != null) {
            completer(context)
            return
        }
        val snapshot = MigrationStateStore.read(context)
        when (snapshot.state) {
            MigrationStateStore.State.REPLACING,
            MigrationStateStore.State.FAILED,
            MigrationStateStore.State.NEEDS_RECOVERY ->
                MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.IDLE)
            MigrationStateStore.State.RESTORE_REPLACED ->
                MigrationStateStore.writeOrThrow(
                    context,
                    snapshot.finalState ?: MigrationStateStore.State.IDLE
                )
            else -> Unit
        }
    }

    private fun markerStore(context: Context): RestoreMarkerStore =
        markerStoreProvider?.invoke(context)
            ?: AtomicRestoreMarkerStore(
                File(context.noBackupFilesDir, MARKER_FILE)
            )
}

/** 恢复 marker 的读写抽象（测试可注入 fake / 临时目录实现）。 */
internal interface RestoreMarkerStore {
    /** 原子写入 generation；失败抛异常。 */
    suspend fun write(generation: String)

    /** 读取内容；无 marker 返回 null。 */
    suspend fun read(): String?

    /** 删除 marker。 */
    suspend fun delete()
}

/**
 * 崩溃安全的 marker 实现（跨 Android/Linux/Windows，JVM 单元测试可用）。
 *
 * 不变量：正式 marker 文件在任意中断后要么是完整旧 generation，要么是完整新
 * generation，**绝不截断**。协议分两段：
 *
 * 1. 首选同目录原子替换：`Files.move(.new → 目标, ATOMIC_MOVE, REPLACE_EXISTING)`
 *    （POSIX rename / Windows MoveFileEx 替换）。不支持或失败时进入下一段。
 * 2. old/new/backup 回退协议（Windows rename 无法覆盖已存在目标时使用）：
 *    a. 先写 `.tmp<随机>` 并 fsync，再改名到 `.new`——因此 `.new` 只要存在就
 *       一定是完整内容（半写只会留在 `.tmp`，不会成为候选值）；
 *    b. 目标存在时把目标改名到 `.bak`（同目录改名，Windows 下目标不存在即可
 *       成功）；
 *    c. `.new` 改名到目标（提交点）；
 *    d. 删除 `.bak`。
 *
 * 中断窗口（回退协议）：
 * - 写入 `.tmp`/fsync 期间崩溃：目标仍是完整旧值；
 * - b 之后 c 之前崩溃：目标缺失，`.bak`=完整旧、`.new`=完整新 → [read] 优先
 *   恢复 `.new`（提交点后的语义是新值）；
 * - c 之后 d 之前崩溃：目标是完整新值，`.bak` 是残留旧值 → 读取目标并清理。
 *
 * [read] 在目标缺失时先做残留恢复再返回，因此崩溃后下一次读取必然拿到完整的
 * 旧或新 generation，绝不会拿到 null 或半写内容而丢失 pending 信号。
 *
 * 严格目录项持久模式（P1-3 终审）：传入 [strictDirectorySync]（返回 true = 该
 * 目录的目录项已确认持久）后，**所有**目录项变更（rename/删除已存在文件）之后
 * 都必须经其确认；任一非 OK 抛 [IOException] fail-closed——write 只有目录项确认
 * 持久才成功（canonical 可能已可见，调用方失败后下次读取按完整值幂等重放），
 * read 的 sidecar 恢复 rename 同样严格同步。P1-2 终审：strict 模式下 [read] 在
 * **返回任何 canonical 内容前**都必须先确认目录项持久（canonical 可见不等于
 * 持久——上次 write 的提交 move 可能已可见但 sync 失败），非 OK 抛
 * [IOException]，持续失败下每次 read 都失败，恢复 OK 才信任并返回；sidecar 清理
 * 的删除 sync 失败同样抛错，绝不静默。默认 null 保持既有尽力而为行为
 * （目录项同步失败仅静默吞掉，write/read 仍按协议返回），供
 * [RestoreCompletionCoordinator] 等既有调用方使用。
 */
internal class AtomicRestoreMarkerStore(
    private val file: File,
    private val atomicMove: (File, File) -> Boolean = AtomicRestoreMarkerStore::defaultAtomicMove,
    private val strictDirectorySync: ((File) -> Boolean)? = null,
) : RestoreMarkerStore {

    private val parent: File
        get() = file.parentFile
            ?: throw IllegalStateException("Marker file has no parent directory")

    private val newFile: File
        get() = File(parent, "${file.name}.new")

    private val bakFile: File
        get() = File(parent, "${file.name}.bak")

    override suspend fun write(generation: String) {
        parent.mkdirs()
        // 上次中断残留恢复：目标缺失时先把完整旧/新值放回目标，之后清理才不会
        // 丢失信号；目标存在时 .new/.bak 都已被目标内容取代，可安全清理。
        if (!file.isFile) {
            when {
                newFile.isFile -> {
                    strictRename(newFile, file)
                    strictDelete(bakFile)
                }
                bakFile.isFile -> {
                    strictRename(bakFile, file)
                }
                else -> Unit
            }
        }
        strictDelete(bakFile)
        strictDelete(newFile)
        deleteStaleTmpFiles()

        // 1. 写完整新内容到唯一临时文件并 fsync：此后内容在断电/崩溃后仍完整。
        //    tmp 创建本身是临时暂存名（内容已 fsync），目录项不确认也不影响任何
        //    提交值，不需要同步；真正进入协议的改名在 [strictRename] 中确认。
        val tmp = File(parent, "${file.name}.tmp${UUID.randomUUID()}")
        try {
            FileOutputStream(tmp).use { output ->
                output.write(generation.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            strictRename(tmp, newFile)
        } catch (e: Exception) {
            deleteQuietly(tmp)
            throw e
        }

        // 2. 首选原子替换（目标已存在时也允许）；抛异常视为不支持，走回退协议。
        val atomicMoved = try {
            atomicMove(newFile, file)
        } catch (e: AtomicMoveNotSupportedException) {
            false
        } catch (e: IOException) {
            false
        }
        if (atomicMoved) {
            // 提交点：canonical 已替换为完整新值，目录项必须确认持久才允许成功；
            // sync 失败时 canonical 可能已可见——调用方失败，下次读取按完整值幂等。
            requireDirSyncDurable()
            return
        }

        // 3. old/new/backup 回退：任意中断后目标必为完整旧或完整新值。
        if (file.exists()) strictRename(file, bakFile)
        if (!newFile.renameTo(file)) {
            // 提交失败：尽力把旧值放回目标，保持可读的完整旧 generation。
            if (bakFile.exists()) {
                strictRename(bakFile, file)
            }
            throw IOException("Failed to move new marker into place: ${file.path}")
        }
        requireDirSyncDurable()
        strictDelete(bakFile)
    }

    override suspend fun read(): String? {
        if (file.isFile) {
            strictDelete(newFile)
            strictDelete(bakFile)
            deleteStaleTmpFiles()
            // P1-2 终审修复：返回 canonical 内容前必须确认目录项持久（strict 模式）——
            // canonical 可见不等于持久：上次 write 的提交 move 可能已可见但目录 sync 失败。
            // 非 OK 抛 IOException fail-closed（持续失败下每次 read 都失败，恢复 OK 才信任
            // canonical）；cleanup sidecar 的删除 sync 失败同样经 [strictDelete] 抛错，绝不
            // 静默。
            requireDirSyncDurable()
            return file.readText()
        }
        // 目标缺失：恢复完整值（.new 已 fsync，存在即完整；优先新值）。恢复 rename
        // 是目录项变更，strict 模式必须确认持久（P1-3），非 OK 抛 IOException 让
        // 调用方 fail-closed。
        return when {
            newFile.isFile -> {
                strictRename(newFile, file)
                strictDelete(bakFile)
                requireDirSyncDurable()
                file.readText()
            }
            bakFile.isFile -> {
                strictRename(bakFile, file)
                requireDirSyncDurable()
                file.readText()
            }
            else -> {
                deleteStaleTmpFiles()
                null
            }
        }
    }

    override suspend fun delete() {
        strictDelete(file)
        strictDelete(newFile)
        strictDelete(bakFile)
        deleteStaleTmpFiles()
    }

    private fun deleteStaleTmpFiles() {
        parent.listFiles { f -> f.name.startsWith("${file.name}.tmp") }?.forEach { strictDelete(it) }
    }

    /**
     * 目录项持久确认（strict 模式，P1-3 终审）：回调返回 false（真实失败或平台
     * 不支持，fail-closed）即抛 [IOException]。非 strict 模式不调用（保持尽力而为）。
     */
    private fun requireDirSyncDurable() {
        val strict = strictDirectorySync ?: return
        if (!strict(parent)) {
            throw IOException(
                "Directory entry not confirmed durable for marker: ${file.path}",
            )
        }
    }

    /**
     * strict 模式的重命名：rename 失败抛 [IOException]；成功后必须确认目录项持久。
     * 非 strict 模式与普通 renameTo 一致（失败返回 false 由调用方判断）。
     */
    private fun strictRename(from: File, to: File) {
        if (!from.renameTo(to)) {
            throw IOException("Failed to rename ${from.path} to ${to.path}")
        }
        requireDirSyncDurable()
    }

    /**
     * strict 模式的删除：文件不存在时无目录项变更（不要求 sync）；删除成功且 strict
     * 时必须确认目录项持久，非 OK 抛 [IOException]。删除失败静默（残留由下次清理）。
     */
    private fun strictDelete(f: File) {
        if (f.exists() && f.delete()) requireDirSyncDurable()
    }

    private fun deleteQuietly(f: File) {
        try {
            f.delete()
        } catch (_: Exception) {
        }
    }

    private companion object {
        fun defaultAtomicMove(from: File, to: File): Boolean = try {
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
    }
}

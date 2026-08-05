package com.ai.assistance.operit.data.backup

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
 */
internal class AtomicRestoreMarkerStore(
    private val file: File,
    private val atomicMove: (File, File) -> Boolean = AtomicRestoreMarkerStore::defaultAtomicMove,
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
                    if (!newFile.renameTo(file)) {
                        throw IOException("Failed to restore new marker after interruption: ${file.path}")
                    }
                    deleteQuietly(bakFile)
                }
                bakFile.isFile -> {
                    if (!bakFile.renameTo(file)) {
                        throw IOException("Failed to restore backup marker after interruption: ${file.path}")
                    }
                }
                else -> Unit
            }
        }
        deleteQuietly(bakFile)
        deleteQuietly(newFile)
        deleteStaleTmpFiles()

        // 1. 写完整新内容到唯一临时文件并 fsync：此后内容在断电/崩溃后仍完整。
        val tmp = File(parent, "${file.name}.tmp${UUID.randomUUID()}")
        try {
            FileOutputStream(tmp).use { output ->
                output.write(generation.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!tmp.renameTo(newFile)) {
                throw IOException("Failed to stage new marker: ${tmp.path}")
            }
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
            syncDirQuietly()
            return
        }

        // 3. old/new/backup 回退：任意中断后目标必为完整旧或完整新值。
        if (file.exists() && !file.renameTo(bakFile)) {
            throw IOException("Failed to move existing marker to backup: ${file.path}")
        }
        if (!newFile.renameTo(file)) {
            // 提交失败：尽力把旧值放回目标，保持可读的完整旧 generation。
            if (bakFile.exists() && !bakFile.renameTo(file)) {
                throw IOException("Marker replacement failed and backup restore also failed: ${file.path}")
            }
            throw IOException("Failed to move new marker into place: ${file.path}")
        }
        deleteQuietly(bakFile)
        syncDirQuietly()
    }

    override suspend fun read(): String? {
        if (file.isFile) {
            deleteQuietly(newFile)
            deleteQuietly(bakFile)
            deleteStaleTmpFiles()
            return file.readText()
        }
        // 目标缺失：恢复完整值（.new 已 fsync，存在即完整；优先新值）。
        return when {
            newFile.isFile -> {
                if (!newFile.renameTo(file)) {
                    throw IOException("Failed to restore new marker: ${file.path}")
                }
                deleteQuietly(bakFile)
                file.readText()
            }
            bakFile.isFile -> {
                if (!bakFile.renameTo(file)) {
                    throw IOException("Failed to restore backup marker: ${file.path}")
                }
                file.readText()
            }
            else -> {
                deleteStaleTmpFiles()
                null
            }
        }
    }

    override suspend fun delete() {
        file.delete()
        deleteQuietly(newFile)
        deleteQuietly(bakFile)
        deleteStaleTmpFiles()
    }

    private fun deleteStaleTmpFiles() {
        parent.listFiles { f -> f.name.startsWith("${file.name}.tmp") }?.forEach { deleteQuietly(it) }
    }

    private fun deleteQuietly(f: File) {
        try {
            f.delete()
        } catch (_: Exception) {
        }
    }

    /** 目录项持久化（rename 提交的落盘）：尽力而为，Windows 不支持目录 channel。 */
    private fun syncDirQuietly() {
        try {
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
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

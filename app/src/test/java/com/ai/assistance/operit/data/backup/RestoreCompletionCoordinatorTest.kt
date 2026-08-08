package com.ai.assistance.operit.data.backup

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * RestoreCompletionCoordinator 状态转换测试（纯 JVM）。
 *
 * RawSnapshotBackupManager 的 restoreFromBackupUri / restoreFromBackupFile 两个
 * 入口共用同一个 [RestoreCompletionCoordinator.registerAfterRestore]，因此本测试
 * 直接验证协调器即覆盖两个入口的公共成功/失败路径：
 * - 登记成功 → marker 存在且为 UUID，recovery state 才完成（complete 只调用一次）；
 * - marker 登记失败 → 抛异常，recovery state 不完成（恢复保持可重试）；
 * - 空/损坏 marker → 不静默删除，重新登记新 generation（安全重试）；
 * - consumeMarker → 删除 marker。
 */
class RestoreCompletionCoordinatorTest {

    private val completions = mutableListOf<String>()

    private fun mockContext(filesDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.noBackupFilesDir).thenReturn(File(filesDir, "no_backup"))
        return context
    }

    private fun installCoordinator(tempDir: File): Pair<File, RestoreMarkerStore> {
        val markerFile = File(File(tempDir, "no_backup"), "token_stats_restore_pending.txt")
        RestoreCompletionCoordinator.markerStoreProvider = { AtomicRestoreMarkerStore(markerFile) }
        RestoreCompletionCoordinator.recoveryStateCompleter = { completions += "completed" }
        return markerFile to AtomicRestoreMarkerStore(markerFile)
    }

    private fun clearCoordinator() {
        RestoreCompletionCoordinator.markerStoreProvider = null
        RestoreCompletionCoordinator.recoveryStateCompleter = null
    }

    @Test
    fun `registerAfterRestore writes marker then completes recovery state`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("restore-coordinator").toFile()
            val (markerFile, store) = installCoordinator(tempDir)
            try {
                val context = mockContext(tempDir)

                org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use { RestoreCompletionCoordinator.registerAfterRestore(context) }

                // marker 已原子写入且内容为合法 UUID
                assertTrue(markerFile.isFile)
                val generation = store.read()
                assertNotNull(generation)
                assertNotNull(UUID.fromString(generation!!.trim()))
                // recovery state 仅在登记成功后完成，且只完成一次
                assertEquals(listOf("completed"), completions)
            } finally {
                clearCoordinator()
            }
        }

    @Test
    fun `marker write failure keeps recovery retryable and does not complete state`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("restore-coordinator").toFile()
            val markerFile = File(File(tempDir, "no_backup"), "token_stats_restore_pending.txt")
            val failingStore =
                object : RestoreMarkerStore {
                    override suspend fun write(generation: String) {
                        throw java.io.IOException("disk full")
                    }

                    override suspend fun read(): String? = null
                    override suspend fun delete() = Unit
                }
            RestoreCompletionCoordinator.markerStoreProvider = { failingStore }
            RestoreCompletionCoordinator.recoveryStateCompleter = { completions += "completed" }
            try {
                val context = mockContext(tempDir)

                try {
                    org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use { RestoreCompletionCoordinator.registerAfterRestore(context) }
                    fail("expected marker write failure to propagate")
                } catch (e: java.io.IOException) {
                    assertEquals("disk full", e.message)
                }

                // 登记失败：recovery state 不得完成（恢复保持可重试状态）
                assertTrue(completions.isEmpty())
                assertFalse(markerFile.exists())
            } finally {
                clearCoordinator()
            }
        }

    @Test
    fun `corrupt or empty marker is re-registered instead of silently deleted`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("restore-coordinator").toFile()
            val (markerFile, store) = installCoordinator(tempDir)
            try {
                val context = mockContext(tempDir)

                // 空 marker（模拟崩溃留下的半写/损坏文件）
                markerFile.parentFile.mkdirs()
                markerFile.writeText("")
                val regenerated = org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use { RestoreCompletionCoordinator.readPendingGeneration(context) }
                assertNotNull("corrupt marker must not be silently dropped", regenerated)
                val persisted = store.read()!!
                assertEquals(regenerated, persisted.trim())
                assertNotNull(UUID.fromString(persisted.trim()))
                // marker 仍存在（未被删除）
                assertTrue(markerFile.isFile)

                // 空白 marker 同样重新登记
                markerFile.writeText("   \n")
                val regenerated2 = org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use { RestoreCompletionCoordinator.readPendingGeneration(context) }
                assertNotNull(regenerated2)
                assertTrue(markerFile.isFile)
            } finally {
                clearCoordinator()
            }
        }

    @Test
    fun `missing marker returns null and consumeMarker deletes it`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("restore-coordinator").toFile()
            val (markerFile, store) = installCoordinator(tempDir)
            try {
                val context = mockContext(tempDir)

                // 无 marker（Room-only 恢复/未恢复）→ null
                assertNull(org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use { RestoreCompletionCoordinator.readPendingGeneration(context) })

                // 消费后删除
                store.write("gen-1")
                assertTrue(markerFile.exists())
                RestoreCompletionCoordinator.consumeMarker(context)
                assertFalse(markerFile.exists())
            } finally {
                clearCoordinator()
            }
        }

    @Test
    fun `marker survives process-style rewrite without truncation`() =
        runBlocking {
            val tempDir = kotlin.io.path.createTempDirectory("restore-coordinator").toFile()
            val (markerFile, store) = installCoordinator(tempDir)
            try {
                // 连续多次原子写（模拟重复恢复调用）：文件始终是完整 UUID
                repeat(5) { index ->
                    store.write("generation-$index")
                    val content = markerFile.readText()
                    assertTrue(content.startsWith("generation-"))
                }
                assertEquals("generation-4", store.read()?.trim())
            } finally {
                clearCoordinator()
            }
        }
}

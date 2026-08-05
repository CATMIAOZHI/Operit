package com.ai.assistance.operit.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * RawSnapshot 恢复编排的入口级测试（纯 JVM，真实状态持久化）。
 *
 * 两个公开入口（restoreFromBackupUri / restoreFromBackupFile）只委托
 * [RawSnapshotBackupManager.runRestoreEntry] 这一条编排；本测试对编排的真实
 * 状态转换做验证（MigrationStateStore + AtomicRestoreMarkerStore + 协调器默认
 * recovery 完成路径全部真实执行，仅替换文件 IO 后端为 JVM 实现），覆盖：
 * - 初始 IDLE 成功：REPLACING → RESTORE_REPLACED → IDLE，marker 为完整 UUID，
 *   processRestartRequired 为 true；
 * - 恢复前为 COMPLETED 时登记完成后保持 COMPLETED（官方迁移标记不丢失）；
 * - 偏好/数据库目录替换失败：状态停在 REPLACING，无 marker，不要求重启；
 * - 目录替换成功但 marker 登记失败：状态停在 RESTORE_REPLACED 且记录 finalState，
 *   要求重启——冷启动自动补登记，无需重新替换目录；
 * - 冷启动自动补登记：RESTORE_REPLACED → 登记新 generation → 完成状态；
 * - URI/File 两个公开入口端到端接线一致（真实 zip 替换目录）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RawSnapshotRestoreOrchestrationTest {

    private lateinit var tempDir: File
    private lateinit var context: Context

    @Before
    fun installSeams() {
        tempDir = kotlin.io.path.createTempDirectory("restore-orchestration").toFile()
        listOf("cache", "data", "files", "external", "no_backup").forEach { File(tempDir, it).mkdirs() }
        context = mockContext(tempDir)
        MigrationStateStore.fileIoProvider = { PlainFileStateIo(File(it.noBackupFilesDir, "official_operit_migration_state.txt")) }
        RestoreCompletionCoordinator.markerStoreProvider = { ctx ->
            AtomicRestoreMarkerStore(File(ctx.noBackupFilesDir, "token_stats_restore_pending.txt"))
        }
        // 真实 recovery 完成路径（读/写 MigrationStateStore）
        RestoreCompletionCoordinator.recoveryStateCompleter = null
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun clearSeams() {
        MigrationStateStore.fileIoProvider = null
        RestoreCompletionCoordinator.markerStoreProvider = null
        RestoreCompletionCoordinator.recoveryStateCompleter = null
        Dispatchers.resetMain()
        resetProcessRestartRequired()
    }

    /** 进程内重启要求标志跨测试重置（生产代码无公开 setter，测试用反射清理）。 */
    private fun resetProcessRestartRequired() {
        val field = RawSnapshotBackupManager.javaClass.getDeclaredField("processRestartRequired")
        field.isAccessible = true
        field.set(RawSnapshotBackupManager, false)
    }

    private fun mockContext(tempDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(File(tempDir, "files"))
        whenever(context.dataDir).thenReturn(File(tempDir, "data"))
        whenever(context.cacheDir).thenReturn(File(tempDir, "cache"))
        whenever(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        whenever(context.getExternalFilesDir(null)).thenReturn(File(tempDir, "external"))
        val resolver = mock<ContentResolver>()
        whenever(context.contentResolver).thenReturn(resolver)
        whenever(resolver.openInputStream(any())).thenAnswer { FileInputStream(zipForOpen) }
        return context
    }

    private lateinit var zipForOpen: File

    private fun state(): MigrationStateStore.Snapshot = MigrationStateStore.read(context)

    private fun markerFile(): File = File(File(tempDir, "no_backup"), "token_stats_restore_pending.txt")

    private fun assertMarkerIsFullUuid() {
        assertTrue("marker must exist", markerFile().isFile)
        val content = markerFile().readText().trim()
        assertNotNull("marker must be a complete UUID, got: $content", UUID.fromString(content))
    }

    // ==== 编排状态转换（runRestoreEntry） ====

    @Test
    fun `initial IDLE restore succeeds and completes to IDLE with registered marker`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                assertEquals(MigrationStateStore.State.IDLE, state().state)
                RawSnapshotBackupManager.runRestoreEntry(
                    context = context,
                    finalState = RawSnapshotBackupManager.restoreFinalState(context),
                    performRestore = {
                        // 与真实入口的 prepareForReplacement 一致：替换前先持久化 REPLACING
                        MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.REPLACING)
                        assertEquals("inside replacement the state must be REPLACING", MigrationStateStore.State.REPLACING, state().state)
                    }
                )
                assertEquals(MigrationStateStore.State.IDLE, state().state)
                assertMarkerIsFullUuid()
                assertTrue("restart must be required after restore", RawSnapshotBackupManager.isProcessRestartRequired())
            }
        }

    @Test
    fun `restore from COMPLETED completes to COMPLETED preserving migration marker`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.COMPLETED)
                RawSnapshotBackupManager.runRestoreEntry(
                    context = context,
                    finalState = RawSnapshotBackupManager.restoreFinalState(context),
                    performRestore = {
                        MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.REPLACING)
                    }
                )
                assertEquals(
                    "official migration COMPLETED marker must survive a user restore",
                    MigrationStateStore.State.COMPLETED,
                    state().state
                )
                assertMarkerIsFullUuid()
                assertTrue(RawSnapshotBackupManager.isProcessRestartRequired())
            }
        }

    @Test
    fun `replacement failure leaves REPLACING with no marker and no restart requirement`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    RawSnapshotBackupManager.runRestoreEntry(
                        context = context,
                        finalState = RawSnapshotBackupManager.restoreFinalState(context),
                        performRestore = {
                            MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.REPLACING)
                            throw java.io.IOException("databases replacement failed")
                        }
                    )
                    fail("replacement failure must propagate")
                } catch (e: java.io.IOException) {
                    assertEquals("databases replacement failed", e.message)
                }
                assertEquals(MigrationStateStore.State.REPLACING, state().state)
                assertFalse("no marker when replacement failed", markerFile().exists())
                assertFalse("restart must not be required on failure", RawSnapshotBackupManager.isProcessRestartRequired())
            }
        }

    @Test
    fun `replacement success but marker failure leaves RESTORE_REPLACED with recorded finalState`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                RestoreCompletionCoordinator.markerStoreProvider = {
                    object : RestoreMarkerStore {
                        override suspend fun write(generation: String) {
                            throw java.io.IOException("marker disk full")
                        }

                        override suspend fun read(): String? = null
                        override suspend fun delete() = Unit
                    }
                }
                try {
                    RawSnapshotBackupManager.runRestoreEntry(
                        context = context,
                        finalState = RawSnapshotBackupManager.restoreFinalState(context),
                        performRestore = {
                            MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.REPLACING)
                        }
                    )
                    fail("marker write failure must propagate")
                } catch (e: java.io.IOException) {
                    assertEquals("marker disk full", e.message)
                }
                // 持久状态必须允许冷启动自动补登记：目录无需重新替换
                assertEquals(MigrationStateStore.State.RESTORE_REPLACED, state().state)
                assertEquals(MigrationStateStore.State.IDLE, state().finalState)
                assertTrue("restart must be required so cold start auto-registers", RawSnapshotBackupManager.isProcessRestartRequired())
            }
        }

    @Test
    fun `cold start auto registration completes without re-replacement`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                // 进程在“目录替换成功、登记未完成”后死亡：持久状态 RESTORE_REPLACED
                MigrationStateStore.writeOrThrow(
                    context,
                    MigrationStateStore.State.RESTORE_REPLACED,
                    finalState = MigrationStateStore.State.IDLE
                )
                assertFalse("no marker yet", markerFile().exists())

                RawSnapshotBackupManager.completePendingRestoreRegistration(context)

                assertEquals(MigrationStateStore.State.IDLE, state().state)
                assertMarkerIsFullUuid()
                assertTrue(RawSnapshotBackupManager.isProcessRestartRequired())
                // 已应用/待应用的旧 generation 不受影响：每次补登记都是全新 UUID
                assertNotNull(UUID.fromString(markerFile().readText().trim()))
            }
        }

    @Test
    fun `cold start auto registration from COMPLETED lands COMPLETED`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                MigrationStateStore.writeOrThrow(
                    context,
                    MigrationStateStore.State.RESTORE_REPLACED,
                    finalState = MigrationStateStore.State.COMPLETED
                )
                RawSnapshotBackupManager.completePendingRestoreRegistration(context)
                assertEquals(MigrationStateStore.State.COMPLETED, state().state)
                assertMarkerIsFullUuid()
            }
        }

    @Test
    fun `cold start auto registration rejects non RESTORE_REPLACED state`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    RawSnapshotBackupManager.completePendingRestoreRegistration(context)
                    fail("auto registration must reject IDLE state")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("No pending restore registration"))
                }
            }
        }

    @Test
    fun `entry points refuse another restore while process restart is required`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                MigrationStateStore.writeOrThrow(context, MigrationStateStore.State.RESTORE_REPLACED)
                RawSnapshotBackupManager.completePendingRestoreRegistration(context)
                // 重启门在公开入口（check(!processRestartRequired)）：任何替换都不得执行
                try {
                    RawSnapshotBackupManager.restoreFromBackupFile(context, File(tempDir, "must-not-open.zip"))
                    fail("restore must be blocked until process restart")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("restart required"))
                }
                assertFalse("replacement must never run", File(tempDir, "data/datastore").exists())
            }
        }

    // ==== 真实 zip 端到端：两个公开入口共用同一编排 ====

    private fun createSnapshotZip(zipFile: File) {
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(
                """
                {"formatVersion":1,"packageName":"com.ai.assistance.operit","createdAt":123,
                "includes":["payload/files/","payload/external_files/","payload/shared_prefs/",
                "payload/datastore/","payload/databases/"],"includeTerminalData":false}
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("payload/datastore/api_settings.preferences_pb"))
            zos.write("restored-datastore-content".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }

    private fun assertRealRestoreOutcome() {
        Mockito.mockStatic(AppLogger::class.java).use {
            assertEquals(MigrationStateStore.State.IDLE, state().state)
            assertMarkerIsFullUuid()
            assertTrue("restart must be required", RawSnapshotBackupManager.isProcessRestartRequired())
            val replaced = File(tempDir, "data/datastore/api_settings.preferences_pb")
            assertTrue("datastore must be replaced by the shared machinery", replaced.isFile)
            assertEquals("restored-datastore-content", replaced.readText())
        }
    }

    @Test
    fun `restoreFromBackupUri runs the shared orchestration end to end`() =
        runBlocking {
            val zip = File(tempDir, "snapshot.zip")
            createSnapshotZip(zip)
            zipForOpen = zip
            // android.net.Uri 在 JVM 上不可构造（stub）：mock 一个 Uri 实例，
            // 入口只把它传给 contentResolver（同样为 mock），不解析内容。
            val uri = mock<Uri>()
            // mockito-inline 的 mockStatic 是线程绑定的：必须在与恢复相同
            // （Dispatchers.IO）的线程上创建，AppLogger 调用才会被拦截。
            withContext(Dispatchers.IO) {
                Mockito.mockStatic(AppLogger::class.java).use {
                    RawSnapshotBackupManager.restoreFromBackupUri(context, uri)
                }
            }
            assertRealRestoreOutcome()
        }

    @Test
    fun `restoreFromBackupFile runs the shared orchestration end to end`() =
        runBlocking {
            val zip = File(tempDir, "snapshot.zip")
            createSnapshotZip(zip)
            zipForOpen = zip
            withContext(Dispatchers.IO) {
                Mockito.mockStatic(AppLogger::class.java).use {
                    RawSnapshotBackupManager.restoreFromBackupFile(context, zip)
                }
            }
            assertRealRestoreOutcome()
        }

    @Test
    fun `uri entry with corrupt zip fails before replacement and leaves state untouched`() =
        runBlocking {
            val zip = File(tempDir, "corrupt.zip")
            zip.writeText("not a zip")
            zipForOpen = zip
            withContext(Dispatchers.IO) {
                Mockito.mockStatic(AppLogger::class.java).use {
                    try {
                        RawSnapshotBackupManager.restoreFromBackupUri(context, mock<Uri>())
                        fail("corrupt zip must fail")
                    } catch (e: Exception) {
                        // 预期：解压/清单校验失败
                    }
                }
            }
            Mockito.mockStatic(AppLogger::class.java).use {
                assertEquals("validation failure must not touch state", MigrationStateStore.State.IDLE, state().state)
                assertFalse("no marker", markerFile().exists())
                assertFalse("no restart requirement", RawSnapshotBackupManager.isProcessRestartRequired())
            }
        }
}

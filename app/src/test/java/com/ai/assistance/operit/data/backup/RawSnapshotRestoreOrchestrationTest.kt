package com.ai.assistance.operit.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.stats.ProviderUsageSnapshot
import com.ai.assistance.operit.data.stats.TokenStatCategory
import com.ai.assistance.operit.data.stats.TokenStatRequestContext
import com.ai.assistance.operit.data.stats.TokenStatStatus
import com.ai.assistance.operit.data.stats.TokenStatsLedger
import com.ai.assistance.operit.data.stats.TokenStatSpool
import com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Settings URI 入口只持久化 PENDING 并由冷启动运行；恢复面的 File 入口与
 * 冷启动 runner 共用 [RawSnapshotBackupManager.runRestoreEntry] 编排。本测试对编排的真实
 * 状态转换做验证（MigrationStateStore + AtomicRestoreMarkerStore + 协调器默认
 * recovery 完成路径全部真实执行，仅替换文件 IO 后端为 JVM 实现），覆盖：
 * - 初始 IDLE 成功：REPLACING → RESTORE_REPLACED → IDLE，marker 为完整 UUID，
 *   processRestartRequired 为 true；
 * - 恢复前为 COMPLETED 时登记完成后保持 COMPLETED（官方迁移标记不丢失）；
 * - 偏好/数据库目录替换失败：状态停在 REPLACING，无 marker，要求重启；
 * - 目录替换成功但 RESTORE_REPLACED 状态写入失败：重启门立即生效（替换成功即
 *   要求重启），状态文件停留在 REPLACING、无 marker；
 * - 目录替换成功但 marker 登记失败：状态停在 RESTORE_REPLACED 且记录 finalState，
 *   要求重启——冷启动自动补登记，无需重新替换目录；
 * - 冷启动自动补登记：RESTORE_REPLACED → 登记新 generation → 完成状态；
 * - URI 先落 PENDING、不在 live process 替换，冷启动 runner 与 File 入口均真实
 *   替换 zip 目录。
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
        MigrationStateStore.uriParserForTest = { mock<Uri>() }
        RestoreCompletionCoordinator.markerStoreProvider = { ctx ->
            AtomicRestoreMarkerStore(File(ctx.noBackupFilesDir, "token_stats_restore_pending.txt"))
        }
        // 真实 recovery 完成路径（读/写 MigrationStateStore）
        RestoreCompletionCoordinator.recoveryStateCompleter = null
        TokenStatSpool.spoolDeleteForTest = null
        TokenStatSpool.rejectDrainScheduleForTest = false
        TokenStatSpool.clearPendingStateForTest()
        // P1 终审：Windows JVM 测试统一注入目录 fsync 成功（生产 Android/Linux 支持目录
        // fd fsync；本类不测试 UNSUPPORTED 平台行为，真实探测在 Windows 会恒返回 UNSUPPORTED）
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun clearSeams() {
        MigrationStateStore.fileIoProvider = null
        MigrationStateStore.uriParserForTest = null
        RestoreCompletionCoordinator.markerStoreProvider = null
        RestoreCompletionCoordinator.recoveryStateCompleter = null
        TokenStatSpool.spoolDeleteForTest = null
        TokenStatSpool.rejectDrainScheduleForTest = false
        TokenStatSpool.dirSyncForTest = null
        TokenStatSpool.clearPendingStateForTest()
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
                    performRestore = { prepare ->
                        prepare()
                        assertEquals("inside replacement the state must be REPLACING", MigrationStateStore.State.REPLACING, state().state)
                        assertTrue("gate must close before replacement", RawSnapshotBackupManager.isProcessRestartRequired())
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
                    performRestore = { prepare ->
                        prepare()
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
    fun `replacement failure after prepare leaves REPLACING and requires restart`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    RawSnapshotBackupManager.runRestoreEntry(
                        context = context,
                        finalState = RawSnapshotBackupManager.restoreFinalState(context),
                        performRestore = { prepare ->
                            prepare()
                            throw java.io.IOException("databases replacement failed")
                        }
                    )
                    fail("replacement failure must propagate")
                } catch (e: java.io.IOException) {
                    assertEquals("databases replacement failed", e.message)
                }
                assertEquals(MigrationStateStore.State.REPLACING, state().state)
                assertFalse("no marker when replacement failed", markerFile().exists())
                assertTrue("restart must be required after entering replacement", RawSnapshotBackupManager.isProcessRestartRequired())
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
                        performRestore = { prepare ->
                            prepare()
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
    fun `replacement success but RESTORE_REPLACED write failure still requires restart`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                // 状态写入在 RESTORE_REPLACED 时失败（模拟持久化磁盘满）：
                // REPLACING（performRestore 内）仍走真实文件 IO，便于断言状态。
                val realIo = { ctx: Context -> PlainFileStateIo(File(ctx.noBackupFilesDir, "official_operit_migration_state.txt")) }
                MigrationStateStore.fileIoProvider = { ctx ->
                    object : MigrationStateFileIo {
                        override fun read(): String? = realIo(ctx).read()

                        override fun write(payload: String) {
                            if (payload.contains("RESTORE_REPLACED")) {
                                throw java.io.IOException("state disk full")
                            }
                            realIo(ctx).write(payload)
                        }
                    }
                }
                try {
                    RawSnapshotBackupManager.runRestoreEntry(
                        context = context,
                        finalState = RawSnapshotBackupManager.restoreFinalState(context),
                        performRestore = { prepare ->
                            prepare()
                        }
                    )
                    fail("state write failure must propagate")
                } catch (e: IllegalStateException) {
                    // MigrationStateStore.write 吞掉底层 IOException 返回 false，
                    // writeOrThrow 以 IllegalStateException 终止编排。
                    assertTrue(e.message!!.contains("Failed to persist migration state RESTORE_REPLACED"))
                }
                // 替换已成功：即使 RESTORE_REPLACED 写入失败，重启门也必须立即生效，
                // 本进程绝不允许再对已替换的数据目录执行任何替换/导出。
                assertTrue(
                    "restart must be required once replacement succeeded, even if state write failed",
                    RawSnapshotBackupManager.isProcessRestartRequired()
                )
                assertFalse("no marker when state write failed", markerFile().exists())
                // 写失败不产生半状态：状态文件停留在 performRestore 已持久化的 REPLACING
                assertEquals(MigrationStateStore.State.REPLACING, state().state)
            }
        }

    @Test
    fun `REPLACING write failure does not require restart`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            MigrationStateStore.fileIoProvider = {
                object : MigrationStateFileIo {
                    override fun read(): String? = null
                    override fun write(payload: String) = throw java.io.IOException("state disk full")
                }
            }
            try {
                RawSnapshotBackupManager.runRestoreEntry(
                    context = context,
                    finalState = MigrationStateStore.State.IDLE,
                    performRestore = { prepare -> prepare() },
                )
                fail("REPLACING write failure must propagate")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("Failed to persist migration state REPLACING"))
            }
            assertFalse(RawSnapshotBackupManager.isProcessRestartRequired())
            assertFalse(markerFile().exists())
        }
    }

    @Test
    fun `public restore REPLACING write failure preserves spool epoch and event acceptance`() = runBlocking {
        val zip = File(tempDir, "snapshot.zip")
        createSnapshotZip(zip)
        zipForOpen = zip
        val oldEpoch = TokenStatSpool.captureRestoreEpoch()
        val realIo = { ctx: Context -> PlainFileStateIo(File(ctx.noBackupFilesDir, "official_operit_migration_state.txt")) }
        MigrationStateStore.fileIoProvider = { ctx ->
            object : MigrationStateFileIo {
                override fun read(): String? = realIo(ctx).read()
                override fun write(payload: String) {
                    if (payload.contains("REPLACING")) throw java.io.IOException("state disk full")
                    realIo(ctx).write(payload)
                }
            }
        }
        TokenStatSpool.rejectDrainScheduleForTest = true

        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    RawSnapshotBackupManager.restoreFromBackupFile(context, zip)
                    fail("REPLACING write failure must propagate")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("Failed to persist migration state REPLACING"))
                }
            }
        }

        assertFalse(RawSnapshotBackupManager.isProcessRestartRequired())
        assertEquals(oldEpoch, TokenStatSpool.captureRestoreEpoch())
        assertTrue(TokenStatSpool.isAcceptingEvents())
        try {
            assertTrue(TokenStatSpool.append(context, "{\"eventId\":\"old-epoch\"}", "old-epoch", oldEpoch))
            val newEpoch = TokenStatSpool.captureRestoreEpoch()
            assertEquals(oldEpoch, newEpoch)
            assertTrue(TokenStatSpool.append(context, "{\"eventId\":\"new-request\"}", "new-request", newEpoch))
            val durableBytes = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
                .walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
            assertTrue(durableBytes.contains("old-epoch"))
            assertTrue(durableBytes.contains("new-request"))
        } finally {
            TokenStatSpool.rejectDrainScheduleForTest = false
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
    fun `URI restore is staged without replacement then applied by cold start runner`() =
        runBlocking {
            val zip = File(tempDir, "snapshot.zip")
            createSnapshotZip(zip)
            zipForOpen = zip
            val uri = mock<Uri>()
            whenever(uri.toString()).thenReturn("content://snapshot/raw")
            Mockito.mockStatic(AppLogger::class.java).use {
                assertTrue(RawSnapshotBackupManager.setPendingRawSnapshotRestore(context, uri))
            }
            assertEquals(MigrationStateStore.State.PENDING, state().state)
            assertEquals(MigrationStateStore.State.IDLE, state().finalState)
            assertFalse(
                "live Settings staging must not replace DataStore",
                File(tempDir, "data/datastore/api_settings.preferences_pb").exists(),
            )

            withContext(Dispatchers.IO) {
                Mockito.mockStatic(AppLogger::class.java).use {
                    RawSnapshotBackupManager.runPendingRestoreOperation(context)
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
    fun `restore fails in replacing state when old spool cannot be removed`() = runBlocking {
        val zip = File(tempDir, "snapshot.zip")
        createSnapshotZip(zip)
        zipForOpen = zip
        val spool = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME)
        spool.mkdirs()
        File(spool, "sealed_1.jsonl").writeText("pending")
        TokenStatSpool.spoolDeleteForTest = { false }
        val oldEpoch = TokenStatSpool.captureRestoreEpoch()

        withContext(Dispatchers.IO) {
            Mockito.mockStatic(AppLogger::class.java).use {
                try {
                    RawSnapshotBackupManager.restoreFromBackupFile(context, zip)
                    fail("spool cleanup failure must fail restore")
                } catch (e: java.io.IOException) {
                    assertTrue(e.message!!.contains("cleanup failed"))
                }
            }
        }
        assertEquals(MigrationStateStore.State.REPLACING, state().state)
        assertTrue("restart required once REPLACING was persisted", RawSnapshotBackupManager.isProcessRestartRequired())
        assertFalse("same process must stop accepting events", TokenStatSpool.isAcceptingEvents())
        assertTrue(TokenStatSpool.captureRestoreEpoch() > oldEpoch)
        assertFalse(
            "old generation must not revive after replacement starts",
            TokenStatSpool.append(context, "{\"eventId\":\"stale-after-restore\"}", "stale-after-restore", oldEpoch),
        )
        assertTrue(spool.exists())
    }

    @Test
    fun `cold start URI validation failure clears pending state before replacement`() =
        runBlocking {
            val zip = File(tempDir, "corrupt.zip")
            zip.writeText("not a zip")
            zipForOpen = zip
            val uri = mock<Uri>()
            whenever(uri.toString()).thenReturn("content://snapshot/corrupt")
            Mockito.mockStatic(AppLogger::class.java).use {
                assertTrue(RawSnapshotBackupManager.setPendingRawSnapshotRestore(context, uri))
            }
            withContext(Dispatchers.IO) {
                Mockito.mockStatic(AppLogger::class.java).use {
                    try {
                        RawSnapshotBackupManager.runPendingRestoreOperation(context)
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

    // ==== P1-2：恢复门闩与存活 insert 的隔离 ====

    @Test
    fun `raw replacement waits for the cross store cleanup sequence`() = runBlocking {
        val cleanupHeld = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val replacementEntered = CompletableDeferred<Unit>()
        val cleanupJob = launch(Dispatchers.Default) {
            TokenStatsResetCoordinator.withCleanupSnapshotAccess {
                cleanupHeld.complete(Unit)
                releaseCleanup.await()
            }
        }
        cleanupHeld.await()
        val restoreJob = launch(Dispatchers.Default) {
            RawSnapshotBackupManager.withTokenStatsRestoreIsolation(
                context = context,
                prepareBeforeCommit = {},
                commitReplacement = {},
            ) {
                replacementEntered.complete(Unit)
            }
        }
        try {
            assertNull(
                "replacement must not overlap Room -> DataStore -> Room cleanup",
                withTimeoutOrNull(250) { replacementEntered.await() },
            )
            releaseCleanup.complete(Unit)
            withTimeout(5_000) { replacementEntered.await() }
            restoreJob.join()
            cleanupJob.join()
        } finally {
            releaseCleanup.complete(Unit)
            restoreJob.cancel()
            cleanupJob.cancel()
        }
    }

    private fun request(id: String): TokenStatRequestContext =
        TokenStatRequestContext(
            eventId = id,
            category = TokenStatCategory.CHAT,
            configId = "cfg",
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            startedAtMs = 1_000L,
        ).apply {
            onUsage(ProviderUsageSnapshot(uncachedInputTokens = 1L, outputTokens = 1L, source = "test"))
            finish(TokenStatStatus.COMPLETED, 1_000L)
        }

    private suspend fun line(request: TokenStatRequestContext): String =
        TokenStatsLedger.prepareEventLine(context, request, request.toSpoolBaseJson())

    /** 模拟 SQLite 忽略线程中断但可释放的挂起：任何 cancel(true) 都无法终止，直到门闩
     *  打开才返回（释放后线程能真正终止，测试结束不留遗留线程）。 */
    private fun gateIgnoringInterrupts(gate: CountDownLatch) {
        while (true) {
            try {
                if (gate.await(1, TimeUnit.SECONDS)) return
            } catch (_: InterruptedException) {
            }
        }
    }

    /** 等待 spool 专属 worker 线程全部终止；超时即失败（测试结束必须无遗留线程）。 */
    private fun awaitNoSpoolWorkerThreads() {
        fun live(): List<String> =
            Thread.getAllStackTraces().entries
                .filter { it.key.isAlive && it.key.name.startsWith("operit-token-stats-") }
                .map { it.key.name }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (live().isEmpty()) return
            Thread.sleep(20)
        }
        fail("spool worker threads leaked: ${live()}")
    }

    @Test
    fun `restore entry with a live insert fails bounded before replacement and leaves state untouched`() =
        runBlocking {
            val zip = File(tempDir, "snapshot.zip")
            createSnapshotZip(zip)
            zipForOpen = zip
            // 必须在构造 spool 行之前安装 provider：line() 的价格解析走注入的 DAO，
            // 否则会落到真实 AppDatabase.getDatabase（JVM 上无框架 SQLite 驱动）
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockingDao = mock<TokenStatsDao>()
            whenever(blockingDao.insertIdentityIfAbsent(any())).thenAnswer {
                entered.countDown()
                gateIgnoringInterrupts(release)
                true
            }
            // 未打桩的 suspend 查询在 mock 上返回 null（擦除为 Object），
            // 价格解析必须拿到空覆盖列表而不是 null
            whenever(blockingDao.getAllPriceOverrides()).thenReturn(emptyList())
            val proxy = mock<AppDatabase>()
            whenever(proxy.tokenStatsDao()).thenReturn(blockingDao)
            TokenStatsLedger.databaseProvider = { proxy }
            TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
            val spool = File(context.filesDir, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            File(spool, "sealed_1.jsonl").writeText(line(request("stale-live")) + "\n")

            // 旧 insert 已通过 fence 并在 DAO 内永久挂起（模拟 SQLite 忽略中断）
            val previousInsert = TokenStatSpool.insertTimeoutMs
            val previousQuiesce = TokenStatSpool.exclusiveQuiesceTimeoutMs
            TokenStatSpool.insertTimeoutMs = 100
            // 0：barrier 第一次轮询即失败，awaitActiveInsertsEmpty 不进入 delay 挂起——
            // 恢复协程全程不离开安装线程局部 AppLogger mock 的 IO 线程（Mockito 5 的
            // mockStatic 是线程局部，delay 恢复可能落到其他 IO 线程使真实 Log.e 抛
            // "not mocked" 误失败）；"still active" 有界失败语义不变。
            TokenStatSpool.exclusiveQuiesceTimeoutMs = 0
            try {
                TokenStatSpool.replay(context)
                assertTrue("insert must be live inside Room", entered.await(10, TimeUnit.SECONDS))

                // 等首轮 drain 的 insert 超时（insertTimeoutMs）并释放 lifecycleMutex 后再进入
                // 恢复：insert 在门闩上阻塞的整个期间都保持登记（registry 不空），barrier 依然
                // 有界失败，语义不变。若不等待，恢复协程会在 barrier 的 lifecycleMutex 上挂起并
                // 被重新调度到 IO 池的其他线程——那里看不到线程局部的 AppLogger mock
                // （Mockito 5 的 mockStatic 是线程局部），真实 Log.e 会抛 "not mocked" 使测试
                // 误失败。释放时刻锚定于 entered（drain 必然先持锁再提交 insert），因此
                // entered + insertTimeoutMs + 200ms 保证恢复时锁已被释放。
                delay(TokenStatSpool.insertTimeoutMs + 200)

                withContext(Dispatchers.IO) {
                    Mockito.mockStatic(AppLogger::class.java).use {
                        try {
                            RawSnapshotBackupManager.restoreFromBackupFile(context, zip)
                            fail("restore must fail bounded while an old insert is live")
                        } catch (e: java.io.IOException) {
                            assertTrue("restore must report the live insert", e.message!!.contains("still active"))
                        }
                    }
                }
                // 替换从未开始：状态不被触碰、无 marker、不要求重启、目录未被覆盖
                assertEquals("replacement must never start", MigrationStateStore.State.IDLE, state().state)
                assertFalse("no marker", markerFile().exists())
                assertFalse("no restart requirement", RawSnapshotBackupManager.isProcessRestartRequired())
                assertFalse(
                    "datastore must not be replaced",
                    File(tempDir, "data/datastore/api_settings.preferences_pb").exists()
                )

                // 模拟重启前必须释放旧 insert 并确认其真实终止，绝不遗留线程
                release.countDown()
                val registryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (TokenStatSpool.activeInsertCountForTest() != 0 && System.nanoTime() < registryDeadline) {
                    delay(10)
                }
                assertEquals(0, TokenStatSpool.activeInsertCountForTest())
                TokenTrackingAIService.resetPricingExecutorForTest()
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.shutdownWriterForTest()
                awaitNoSpoolWorkerThreads()
            } finally {
                TokenStatsLedger.databaseProvider = null
                TokenStatsLedger.legacyPriceProvider = null
                TokenStatSpool.resetExecutorsForTest()
                TokenStatSpool.insertTimeoutMs = previousInsert
                TokenStatSpool.exclusiveQuiesceTimeoutMs = previousQuiesce
            }
        }
}

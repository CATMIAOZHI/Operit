package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.api.chat.llmprovider.TokenStatsPersistenceException
import com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.ui.features.settings.screens.QuarantineExportCleanup
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** File + Room tests for the stage-2 durability linearization points. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal abstract class TokenStatReliabilityTestBase {
    protected lateinit var root: File
    protected lateinit var context: Context
    protected lateinit var database: AppDatabase

    @Before
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("token-stat-reliability").toFile()
        context = mock<Context>().also { ctx ->
            whenever(ctx.applicationContext).thenReturn(ctx)
            whenever(ctx.packageName).thenReturn("com.ai.assistance.operit")
            whenever(ctx.filesDir).thenReturn(root)
            whenever(ctx.getDatabasePath(any())).thenAnswer { File(root, it.getArgument<String>(0)) }
        }
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
                .allowMainThreadQueries()
                .build()
        TokenStatsLedger.databaseProvider = { database }
        TokenStatsLedger.legacyPriceProvider = { _, _ -> null }
        TokenStatSpool.clearPendingStateForTest()
        TokenTrackingAIService.resetPricingExecutorForTest()
        TokenStatSpool.afterSegmentReadForTest = null
        TokenStatSpool.spoolDeleteForTest = null
        TokenStatSpool.segmentDeleteForTest = null
        TokenStatSpool.segmentRenameForTest = null
        TokenStatSpool.quarantineAtomicMoveForTest = null
        TokenStatSpool.metadataReadErrorForTest = null
        TokenStatSpool.metadataWriteErrorForTest = null
        TokenStatSpool.segmentReadErrorForTest = null
        TokenStatSpool.ackAtomicMoveForTest = null
        TokenStatSpool.directoryListingForTest = null
        TokenStatSpool.beforeSealPublishForTest = null
        TokenStatSpool.sealHardLinkForTest = null
        TokenStatSpool.sealActiveDeleteForTest = null
        TokenStatSpool.fileSyncForTest = null
        // P1 终审：Windows JVM 测试统一注入“目录 fsync 支持且成功”（平台无关）——生产
        // Android/Linux 支持目录 fd fsync；UNSUPPORTED/FAILED 只由显式 fail-closed 测试在
        // 测试体内注入并在 finally 还原，不存在“原地排空”平台模式。
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
        TokenStatSpool.sealCopyForTest = null
    }

    @After
    fun tearDown() {
        // P1-1 终审修复：测试可能以“目录项未确认持久”状态结束（gate=false），tearDown 的快照
        // barrier 会重新 bootstrap——必须先恢复“目录 fsync 支持且成功”的平台常态（Windows JVM
        // 真实探测恒为 UNSUPPORTED），否则 gate 在 tearDown 中失败并掩盖测试结果。
        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
        runBlocking {
            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = false) { }
        }
        TokenStatsLedger.databaseProvider = null
        TokenStatsLedger.legacyPriceProvider = null
        TokenStatSpool.afterSegmentReadForTest = null
        TokenStatSpool.spoolDeleteForTest = null
        TokenStatSpool.segmentDeleteForTest = null
        TokenStatSpool.segmentRenameForTest = null
        TokenStatSpool.quarantineAtomicMoveForTest = null
        TokenStatSpool.metadataReadErrorForTest = null
        TokenStatSpool.metadataWriteErrorForTest = null
        TokenStatSpool.segmentReadErrorForTest = null
        TokenStatSpool.ackAtomicMoveForTest = null
        TokenStatSpool.directoryListingForTest = null
        TokenStatSpool.beforeSealPublishForTest = null
        TokenStatSpool.sealHardLinkForTest = null
        TokenStatSpool.sealActiveDeleteForTest = null
        TokenStatSpool.fileSyncForTest = null
        TokenStatSpool.dirSyncForTest = null
        TokenStatSpool.sealCopyForTest = null
        TokenStatSpool.afterDrainRoundForTest = null
        TokenStatSpool.rejectDrainScheduleForTest = false
        TokenStatSpool.prepareTimeoutMs = 5_000L
        TokenStatSpool.insertTimeoutMs = 5_000L
        TokenStatSpool.exclusiveQuiesceTimeoutMs = 5_000L
        // 每个测试结束必须无遗留 spool worker 线程：shutdown 已释放的 worker 后确认终止
        TokenTrackingAIService.resetPricingExecutorForTest()
        TokenStatSpool.resetExecutorsForTest()
        TokenStatSpool.shutdownWriterForTest()
        awaitNoSpoolWorkerThreads()
        database.close()
    }

    protected fun request(
        id: String,
        generation: Long = 0L,
        startedAt: Long = 1_000L,
    ) = TokenStatRequestContext(
        eventId = id,
        category = TokenStatCategory.CHAT,
        configId = "cfg",
        provider = "DEEPSEEK",
        model = "deepseek-chat",
        startedAtMs = startedAt,
        acceptedGeneration = generation,
        // P1 终审：请求“开始”时同步捕获 restore epoch（与生产 newRequest 一致）；恢复屏障
        // 递增 epoch 后，捕获于屏障前的旧请求在 append 时被明确拒绝。
        sessionEpoch = TokenStatSpool.captureRestoreEpoch(),
    ).apply {
        onUsage(
            ProviderUsageSnapshot(
                uncachedInputTokens = 10L,
                cachedInputTokens = 0L,
                cacheWriteTokens = 0L,
                outputTokens = 5L,
                source = "test",
            ),
        )
        finish(TokenStatStatus.COMPLETED, startedAt)
    }

    protected suspend fun line(request: TokenStatRequestContext): String =
        TokenStatsLedger.prepareEventLine(context, request, request.toSpoolBaseJson())

    protected suspend fun awaitEvent(id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (database.tokenStatsDao().getEvent(id) == null && System.nanoTime() < deadline) delay(20)
    }

    /**
     * 模拟 SQLite 忽略线程中断但可释放的挂起：任何 cancel(true) 都无法终止，直到门闩
     * 打开才返回（释放后线程能真正终止，测试结束不留遗留线程）。
     */
    protected fun gateIgnoringInterrupts(gate: CountDownLatch) {
        while (true) {
            try {
                if (gate.await(1, TimeUnit.SECONDS)) return
            } catch (_: InterruptedException) {
            }
        }
    }

    /** 等待 spool 专属 worker 线程全部终止；超时即失败（测试结束必须无遗留线程）。 */
    protected fun awaitNoSpoolWorkerThreads() {
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

    protected suspend fun runDirSyncFailClosedScenario(
        result: TokenStatSpool.DirSyncResult,
        tag: String,
        scenario: suspend (TokenStatSpool.DirSyncResult, String) -> Unit,
    ) {
        val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
        Mockito.mockStatic(AppLogger::class.java).use {
            try {
                spool.deleteRecursively()
                TokenStatSpool.clearPendingStateForTest()
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                database.tokenStatsDao().deleteAllEvents()
                scenario(result, tag)
            } finally {
                TokenStatSpool.dirSyncForTest = null
                TokenStatSpool.segmentRenameForTest = null
                TokenStatSpool.segmentDeleteForTest = null
                TokenStatSpool.ackAtomicMoveForTest = null
                TokenStatSpool.spoolDeleteForTest = null
                spool.deleteRecursively()
            }
        }
    }

    protected fun padLineTo(line: String, targetBytes: Int): String {
        val overhead = ",\"pad\":\"\"".toByteArray(Charsets.UTF_8).size
        val current = (line + "\n").toByteArray(Charsets.UTF_8).size
        val padding = targetBytes - current - overhead
        check(padding >= 0) { "line too large to pad: $current + $overhead > $targetBytes" }
        return line + ",\"pad\":\"" + "x".repeat(padding) + "\""
    }

    /**
     * manifest 轮询安全读：drain 线程可能正在原子替换该文件，Windows 下同一瞬间的读取
     * 会以共享冲突失败；失败时短暂重试，持续失败返回 null 由调用方重试/断言兜底。
     */
    protected fun safeManifestText(manifest: File): String? {
        repeat(3) {
            try {
                return if (manifest.isFile) manifest.readText() else null
            } catch (e: Exception) {
                Thread.sleep(10)
            }
        }
        return null
    }

    protected fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }

    protected suspend fun awaitNoPendingEvidence(spool: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("quarantine_pending_delete_") }
        ) {
            delay(20)
        }
    }

    protected suspend fun awaitSegmentGone(spool: File, name: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && File(spool, name).exists()) delay(20)
    }

    protected suspend fun awaitSummaryPublishedAndSegmentGone(spool: File, segmentName: String) {
        val summary = File(spool, "quarantine_summary.jsonl")
        val segment = File(spool, segmentName)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && (!summary.isFile || segment.exists())) {
            delay(20)
        }
    }

    protected suspend fun awaitManifestWithout(spool: File, name: String) {
        val manifest = File(spool, "quarantine_skip_manifest.jsonl")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            safeManifestText(manifest)?.contains(name) == true
        ) {
            delay(20)
        }
        assertFalse(
            "tombstone entry must be removed after cleanup",
            safeManifestText(manifest)?.contains(name) == true,
        )
    }

    protected suspend fun awaitNoSealedSegments(spool: File) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline &&
            spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") }
        ) {
            delay(20)
        }
    }
}

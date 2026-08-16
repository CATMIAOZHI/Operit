package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.TokenStatCleanupOperationEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.data.preferences.applyLegacyCleanupMutation
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * legacy cleanup outbox 排空协议测试（阶段 5 P1 闭环）：
 *
 * - 删除事务（唯一线性化点）提交后，coordinator 在 Room 事务外排空：
 *   Room 读 PENDING → DataStore 单次 edit 精准清键 + 写 marker → Room ACK APPLIED；
 * - DataStore 失败（含取消）→ operation 保持 PENDING、异常/取消传播，重启排空重试；
 * - marker 已存在 → 幂等 no-op（崩溃后重放不二次清键、不写值；新累计值保留）；
 * - ALL kind 清全部累计键但不触碰价格/计费方式等配置键与 marker 键；
 * - 重启排空（无 PENDING）不触碰 DataStore。
 *
 * Windows DataStore 约束：模块级 `Context.apiDataStore` 委托在单个 JVM 内只创建
 * 一个 DataStore 实例，且同一文件每次真实写入在 Windows 上不稳定（rename 目标
 * 已存在时失败）。因此每个测试阶段最多一次真实写入：先用独立 DataStore 实例生成
 * 种子文件（[seedPreferencesFile]），复制到阶段目录（[restorePreferencesInto]），
 * 被测流程（排空的 apply）是阶段文件的唯一真实写入；失败路径用注入的
 * ApiPreferences mock 在边界模拟 DataStore 故障（协调器协议用真实 DAO 验证），
 * 键级幂等语义由纯函数 [applyLegacyCleanupMutation] 直接验证——不绕过生产协议。
 */
class TokenStatsCleanupOutboxTest {

    private val providerA = "DEEPSEEK:deepseek-chat"
    private val providerB = "OPENAI:gpt-4o"

    @Before
    fun isolate() {
        clearApiDataStoreSingleton()
        injectApiPreferences(null)
        TokenStatsResetCoordinator.daoProvider = null
        TokenBaselineImportRunner.databaseProvider = null
    }

    // ==== DataStore 单例隔离与种子文件（与 TokenBaselineImportRunnerTest 同套技术） ====

    private fun clearApiDataStoreSingleton() {
        val facade = Class.forName("com.ai.assistance.operit.data.preferences.ApiPreferencesKt")
        val delegateField = facade.getDeclaredField("apiDataStore\$delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(null)
        val instanceField =
            delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
        instanceField.set(delegate, null)
    }

    private fun injectApiPreferences(instance: ApiPreferences?) {
        val field =
            ApiPreferences::class.java
                .getDeclaredField("INSTANCE")
                .apply { isAccessible = true }
        field.set(null, instance)
    }

    private fun constructApiPreferences(context: Context): ApiPreferences {
        val constructor =
            ApiPreferences::class.java
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
        return constructor.newInstance(context)
    }

    private fun mockContext(filesDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.noBackupFilesDir).thenReturn(File(filesDir, "no_backup"))
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(filesDir, invocation.getArgument<String>(0))
        }
        return context
    }

    private fun openDatabase(filesDir: File): AppDatabase =
        Room.databaseBuilder(mockContext(filesDir), AppDatabase::class.java, "app_database")
            .setDriver(JdbcSQLiteDriver())
            .addMigrations(
                AppDatabase.MIGRATION_28_29,
                AppDatabase.MIGRATION_29_30,
                AppDatabase.MIGRATION_30_31,
                AppDatabase.MIGRATION_31_32,
            )
            .allowMainThreadQueries()
            .build()

    private fun seedPreferencesFile(seedFile: File, block: (MutablePreferences) -> Unit) {
        seedFile.parentFile?.mkdirs()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { seedFile },
            )
        runBlocking { store.edit { block(it) } }
    }

    private fun restorePreferencesInto(filesDir: File, seedFile: File) {
        val target = File(filesDir, "datastore/api_settings.preferences_pb")
        target.parentFile?.mkdirs()
        seedFile.copyTo(target, overwrite = true)
    }

    private suspend fun seedLegacyIdentity(
        dao: TokenStatsDao,
        identityId: String,
        configId: String,
        provider: String,
        model: String,
        displayModelId: String,
    ) {
        dao.insertIdentityIfAbsent(
            TokenStatIdentityEntity(
                identityId = identityId,
                configId = configId,
                provider = provider,
                model = model,
                displayModelId = displayModelId,
            )
        )
        dao.upsertDisplayModel(
            TokenStatDisplayModelEntity(
                displayModelId = displayModelId,
                normalizedModel = displayModelId,
                displayName = displayModelId,
            )
        )
    }

    /**
     * Windows DataStore 约束（与 VM 测试同款技术）：DataStore 1.0.0 以 renameTo 原子
     * 替换，Windows 上目标文件已存在时替换失败。先用一次读把种子文件内容载入
     * DataStore 内存缓存（后续 edit 基于缓存状态），再移除磁盘文件，使被测流程的
     * edit（rename 目标不存在）成为该文件的唯一真实写入——不绕过生产协议。
     */
    private suspend fun primeDatastoreForWrite(phase: File, prefs: ApiPreferences) {
        prefs.getInputTokensForProviderModel("DEEPSEEK:deepseek-chat")
        check(File(File(phase, "datastore"), "api_settings.preferences_pb").delete())
    }

    // ==== 排空协议 ====

    @Test
    fun `drain applies precise legacy keys for display group operation and acks`() =
        runBlocking {
            val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
            val phase = kotlin.io.path.createTempDirectory("outbox-phase").toFile()
            val database = openDatabase(dbDir)
            val dao = database.tokenStatsDao()
            TokenStatsResetCoordinator.daoProvider = { dao }
            try {
                // Room：group-x 含 legacy A 与配置身份 cfg-C；group-y 含 legacy B
                seedLegacyIdentity(dao, "x-legacy", "", "DEEPSEEK", "deepseek-chat", "group-x")
                seedLegacyIdentity(dao, "x-cfg", "cfg-c", "OPENAI", "gpt-4o", "group-x")
                seedLegacyIdentity(dao, "y-legacy", "", "OPENAI", "gpt-4o", "group-y")

                // DataStore：A 与 B 都有累计键，A 另有价格键与 request_count
                val seed = kotlin.io.path.createTempDirectory("outbox-seed").toFile()
                val seedFile = File(seed, "seed.preferences_pb")
                seedPreferencesFile(seedFile) { prefs ->
                    prefs[ApiPreferences.getTokenInputKey(providerA)] = 1_000_000L
                    prefs[ApiPreferences.getTokenCachedInputKey(providerA)] = 200_000L
                    prefs[ApiPreferences.getTokenOutputKey(providerA)] = 500_000L
                    prefs[ApiPreferences.getRequestCountKey(providerA)] = 7
                    prefs[ApiPreferences.getModelInputPriceKey(providerA)] = 2.0f
                    prefs[ApiPreferences.getTokenInputKey(providerB)] = 3_000_000L
                    prefs[ApiPreferences.getTokenOutputKey(providerB)] = 800_000L
                }
                restorePreferencesInto(phase, seedFile)
                val ctx = mockContext(phase)
                val prefs = constructApiPreferences(ctx)
                injectApiPreferences(prefs)
                primeDatastoreForWrite(phase, prefs)

                // 删除事务创建 operation（items 精确到 legacy A）
                val result = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true)
                val op = result.cleanupOperation!!
                assertEquals(1, dao.getCleanupItems(op.operationId).size)

                // 排空（该文件的唯一真实写入）
                Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }

                // DataStore：A 键精准清除，B 键与价格配置保留，marker 已写
                assertEquals(0L, prefs.getInputTokensForProviderModel(providerA))
                assertEquals(0L, prefs.getOutputTokensForProviderModel(providerA))
                assertEquals(0, prefs.getRequestCountForProviderModel(providerA))
                assertEquals("other model counts must survive", 3_000_000L, prefs.getInputTokensForProviderModel(providerB))
                assertEquals("price config must never be cleared", 2.0, prefs.getModelInputPrice(providerA), 1e-9)
                assertEquals(setOf(op.operationId), prefs.appliedLegacyCleanupMarkerIds())
                // Room：operation APPLIED、items 保留（lineage）
                assertEquals(TokenStatCleanupOperationEntity.STATUS_APPLIED, dao.getAllCleanupOperations().single().status)
                assertEquals(1, dao.getCleanupItems(op.operationId).size)
                assertEquals(0, dao.countPendingCleanupOperations())
            } finally {
                TokenStatsResetCoordinator.daoProvider = null
                injectApiPreferences(null)
                database.close()
            }
        }

    @Test
    fun `datastore failure keeps operation pending and restart drain completes it`() =
        runBlocking {
            val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
            val phase = kotlin.io.path.createTempDirectory("outbox-phase").toFile()
            val database = openDatabase(dbDir)
            val dao = database.tokenStatsDao()
            TokenStatsResetCoordinator.daoProvider = { dao }
            try {
                seedLegacyIdentity(dao, "x-legacy", "", "DEEPSEEK", "deepseek-chat", "group-x")
                val seed = kotlin.io.path.createTempDirectory("outbox-seed").toFile()
                val seedFile = File(seed, "seed.preferences_pb")
                seedPreferencesFile(seedFile) { prefs ->
                    prefs[ApiPreferences.getTokenInputKey(providerA)] = 1_000_000L
                    prefs[ApiPreferences.getTokenOutputKey(providerA)] = 500_000L
                }
                restorePreferencesInto(phase, seedFile)
                val ctx = mockContext(phase)
                val op = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true).cleanupOperation!!

                // 第一次排空：DataStore 边界失败（模拟 edit 抛 IOException）→ 传播、保持 PENDING
                val failingPrefs = mock<ApiPreferences>()
                whenever(failingPrefs.applyLegacyCleanup(op.operationId, listOf(providerA)))
                    .thenAnswer { throw IOException("datastore down") }
                injectApiPreferences(failingPrefs)
                try {
                    Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }
                    fail("drain must propagate the DataStore failure")
                } catch (e: IOException) {
                    assertEquals("datastore down", e.message)
                }
                assertEquals(
                    "failed apply must keep the operation pending",
                    TokenStatCleanupOperationEntity.STATUS_PENDING,
                    dao.getPendingCleanupOperations().single().status,
                )

                // 模拟重启：注入真实 prefs（该文件的首个真实写入），排空重试成功
                val realPrefs = constructApiPreferences(ctx)
                injectApiPreferences(realPrefs)
                primeDatastoreForWrite(phase, realPrefs)
                Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }
                val applied = dao.getAllCleanupOperations().single()
                assertEquals(TokenStatCleanupOperationEntity.STATUS_APPLIED, applied.status)
                assertEquals(0L, ApiPreferences.getInstance(ctx).getInputTokensForProviderModel(providerA))
                assertEquals(setOf(op.operationId), ApiPreferences.getInstance(ctx).appliedLegacyCleanupMarkerIds())
            } finally {
                TokenStatsResetCoordinator.daoProvider = null
                injectApiPreferences(null)
                database.close()
            }
        }

    @Test
    fun `cancellation propagates through drain and operation stays pending`() = runBlocking {
        val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
        val phase = kotlin.io.path.createTempDirectory("outbox-phase").toFile()
        val database = openDatabase(dbDir)
        val dao = database.tokenStatsDao()
        TokenStatsResetCoordinator.daoProvider = { dao }
        try {
            seedLegacyIdentity(dao, "x-legacy", "", "DEEPSEEK", "deepseek-chat", "group-x")
            val seed = kotlin.io.path.createTempDirectory("outbox-seed").toFile()
            val seedFile = File(seed, "seed.preferences_pb")
            seedPreferencesFile(seedFile) { prefs ->
                prefs[ApiPreferences.getTokenInputKey(providerA)] = 1_000_000L
            }
            restorePreferencesInto(phase, seedFile)
            val ctx = mockContext(phase)
            val op = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true).cleanupOperation!!

            val cancellingPrefs = mock<ApiPreferences>()
            whenever(cancellingPrefs.applyLegacyCleanup(op.operationId, listOf(providerA)))
                .thenThrow(CancellationException("drain cancelled"))
            injectApiPreferences(cancellingPrefs)
            try {
                Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }
                fail("drain must propagate CancellationException")
            } catch (e: CancellationException) {
                assertEquals("drain cancelled", e.message)
            }
            assertEquals(1, dao.countPendingCleanupOperations())
            assertEquals(0, dao.ackCleanupOperation("never-acked"))
            assertEquals(1, dao.countPendingCleanupOperations())
        } finally {
            TokenStatsResetCoordinator.daoProvider = null
            injectApiPreferences(null)
            database.close()
        }
    }

    @Test
    fun `read failure inside deletion transaction fails without cleanup or drain`() =
        runBlocking {
            val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
            val database = openDatabase(dbDir)
            val dao = database.tokenStatsDao()
            // 事务内读取失败（成员解析抛错）：整个删除事务失败——不得继续排空、
            // 不得产生任何 operation（生产原子性由 DAO @Transaction 回滚保证）
            val failingDao = mock<TokenStatsDao>()
            whenever(failingDao.deleteDisplayModelEventsTx("group-x", true))
                .thenThrow(RuntimeException("member read failed"))
            TokenStatsResetCoordinator.daoProvider = { failingDao }
            val ctx = mockContext(kotlin.io.path.createTempDirectory("outbox-phase").toFile())
            val spyPrefs = mock<ApiPreferences>()
            injectApiPreferences(spyPrefs)
            try {
                val failure = runCatching {
                    TokenStatsResetCoordinator.deleteDisplayModel(ctx, "group-x", deleteBaselines = true)
                }
                assertTrue("read failure must propagate", failure.isFailure)
                verify(spyPrefs, never()).applyLegacyCleanup(any(), any())
                assertEquals(
                    "failed transaction must leave no pending operation",
                    0,
                    dao.countPendingCleanupOperations(),
                )
            } finally {
                TokenStatsResetCoordinator.daoProvider = null
                injectApiPreferences(null)
                database.close()
            }
        }

    @Test
    fun `all kind clears every cumulative key and keeps prices and markers`() = runBlocking {
        val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
        val phase = kotlin.io.path.createTempDirectory("outbox-phase").toFile()
        val database = openDatabase(dbDir)
        val dao = database.tokenStatsDao()
        TokenStatsResetCoordinator.daoProvider = { dao }
        try {
            val seed = kotlin.io.path.createTempDirectory("outbox-seed").toFile()
            val seedFile = File(seed, "seed.preferences_pb")
            seedPreferencesFile(seedFile) { prefs ->
                prefs[ApiPreferences.getTokenInputKey(providerA)] = 1_000_000L
                prefs[ApiPreferences.getTokenInputKey(providerB)] = 3_000_000L
                prefs[ApiPreferences.getModelInputPriceKey(providerA)] = 2.0f
                prefs[ApiPreferences.getBillingModeKey(providerB)] = "COUNT"
            }
            restorePreferencesInto(phase, seedFile)
            val ctx = mockContext(phase)
            val prefs = constructApiPreferences(ctx)
            injectApiPreferences(prefs)
            primeDatastoreForWrite(phase, prefs)

            val op = dao.deleteAllStatisticsTx(deleteBaselines = true).cleanupOperation!!
            assertEquals(TokenStatCleanupOperationEntity.SCOPE_ALL, op.scope)
            assertTrue(dao.getCleanupItems(op.operationId).isEmpty())

            Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }

            assertEquals(0L, prefs.getInputTokensForProviderModel(providerA))
            assertEquals(0L, prefs.getInputTokensForProviderModel(providerB))
            assertEquals("price config must survive ALL cleanup", 2.0, prefs.getModelInputPrice(providerA), 1e-9)
            assertEquals("billing config must survive ALL cleanup", "COUNT", prefs.getBillingModeForProviderModel(providerB).name)
            assertEquals(setOf(op.operationId), prefs.appliedLegacyCleanupMarkerIds())
            assertEquals(TokenStatCleanupOperationEntity.STATUS_APPLIED, dao.getAllCleanupOperations().single().status)
        } finally {
            TokenStatsResetCoordinator.daoProvider = null
            injectApiPreferences(null)
            database.close()
        }
    }

    @Test
    fun `restart drain with nothing pending never touches datastore`() = runBlocking {
        val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
        val database = openDatabase(dbDir)
        val dao = database.tokenStatsDao()
        TokenStatsResetCoordinator.daoProvider = { dao }
        try {
            val ctx = mockContext(kotlin.io.path.createTempDirectory("outbox-phase").toFile())
            // 无任何 operation
            val spyPrefs = mock<ApiPreferences>()
            injectApiPreferences(spyPrefs)
            Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }
            verify(spyPrefs, never()).applyLegacyCleanup(any(), any())
        } finally {
            TokenStatsResetCoordinator.daoProvider = null
            injectApiPreferences(null)
            database.close()
        }
    }

    @Test
    fun `marker present makes retry a strict no-op preserving re added counts`() = runBlocking {
        val dbDir = kotlin.io.path.createTempDirectory("outbox-db").toFile()
        val phase = kotlin.io.path.createTempDirectory("outbox-phase").toFile()
        val database = openDatabase(dbDir)
        val dao = database.tokenStatsDao()
        TokenStatsResetCoordinator.daoProvider = { dao }
        try {
            seedLegacyIdentity(dao, "x-legacy", "", "DEEPSEEK", "deepseek-chat", "group-x")
            val op = dao.deleteDisplayModelEventsTx("group-x", deleteBaselines = true).cleanupOperation!!

            // 崩溃窗口：marker 已写（apply 完成）但 ACK 未提交；随后新使用重新累计了计数
            val seed = kotlin.io.path.createTempDirectory("outbox-seed").toFile()
            val seedFile = File(seed, "seed.preferences_pb")
            seedPreferencesFile(seedFile) { prefs ->
                prefs[ApiPreferences.legacyCleanupMarkerKey(op.operationId)] = true
                prefs[ApiPreferences.getTokenInputKey(providerA)] = 42_000L
            }
            restorePreferencesInto(phase, seedFile)
            val ctx = mockContext(phase)
            val prefs = constructApiPreferences(ctx)
            injectApiPreferences(prefs)

            // 重试排空：marker 已存在 → 幂等 no-op（不二次清键、不写值），只 ACK
            Mockito.mockStatic(AppLogger::class.java).use { TokenStatsResetCoordinator.drainPendingCleanup(ctx) }
            assertEquals(TokenStatCleanupOperationEntity.STATUS_APPLIED, dao.getAllCleanupOperations().single().status)
            assertEquals(
                "fresh usage after the crash must be preserved (no double clear)",
                42_000L,
                prefs.getInputTokensForProviderModel(providerA),
            )
            assertEquals(setOf(op.operationId), prefs.appliedLegacyCleanupMarkerIds())
        } finally {
            TokenStatsResetCoordinator.daoProvider = null
            injectApiPreferences(null)
            database.close()
        }
    }

    // ==== 纯变更函数（键级语义，无 I/O） ====

    @Test
    fun `mutation clears exact provider models and writes marker`() = runBlocking {
        val prefs = androidx.datastore.preferences.core.preferencesOf(
            ApiPreferences.getTokenInputKey(providerA) to 1L,
            ApiPreferences.getTokenCachedInputKey(providerA) to 2L,
            ApiPreferences.getTokenOutputKey(providerA) to 3L,
            ApiPreferences.getRequestCountKey(providerA) to 4,
            ApiPreferences.getTokenInputKey(providerB) to 5L,
            ApiPreferences.getModelInputPriceKey(providerA) to 2.0f,
            ApiPreferences.getBillingModeKey(providerB) to "TOKEN",
        ).toMutablePreferences()

        applyLegacyCleanupMutation(prefs, "op-1", listOf(providerA))

        assertNull(prefs[ApiPreferences.getTokenInputKey(providerA)])
        assertNull(prefs[ApiPreferences.getTokenCachedInputKey(providerA)])
        assertNull(prefs[ApiPreferences.getTokenOutputKey(providerA)])
        assertNull(prefs[ApiPreferences.getRequestCountKey(providerA)])
        assertEquals(5L, prefs[ApiPreferences.getTokenInputKey(providerB)])
        assertEquals(2.0f, prefs[ApiPreferences.getModelInputPriceKey(providerA)])
        assertEquals("TOKEN", prefs[ApiPreferences.getBillingModeKey(providerB)])
        assertEquals(true, prefs[ApiPreferences.legacyCleanupMarkerKey("op-1")])
    }

    @Test
    fun `mutation ALL clears cumulative keys and keeps config and markers`() = runBlocking {
        val prefs = androidx.datastore.preferences.core.preferencesOf(
            ApiPreferences.getTokenInputKey(providerA) to 1L,
            ApiPreferences.getTokenOutputKey(providerB) to 3L,
            ApiPreferences.getRequestCountKey(providerB) to 4,
            ApiPreferences.getModelInputPriceKey(providerA) to 2.0f,
            ApiPreferences.legacyCleanupMarkerKey("op-old") to true,
        ).toMutablePreferences()

        applyLegacyCleanupMutation(prefs, "op-all", null)

        assertNull(prefs[ApiPreferences.getTokenInputKey(providerA)])
        assertNull(prefs[ApiPreferences.getTokenOutputKey(providerB)])
        assertNull(prefs[ApiPreferences.getRequestCountKey(providerB)])
        assertEquals("price config must survive ALL", 2.0f, prefs[ApiPreferences.getModelInputPriceKey(providerA)])
        assertEquals("old markers must survive ALL", true, prefs[ApiPreferences.legacyCleanupMarkerKey("op-old")])
        assertEquals("new marker must be written", true, prefs[ApiPreferences.legacyCleanupMarkerKey("op-all")])
    }

    @Test
    fun `mutation with existing marker is a strict no-op`() = runBlocking {
        val prefs = androidx.datastore.preferences.core.preferencesOf(
            ApiPreferences.legacyCleanupMarkerKey("op-1") to true,
            ApiPreferences.getTokenInputKey(providerA) to 42L,
        ).toMutablePreferences()

        applyLegacyCleanupMutation(prefs, "op-1", listOf(providerA))
        applyLegacyCleanupMutation(prefs, "op-1", null)

        assertEquals(
            "marker present must never re-clear keys",
            42L,
            prefs[ApiPreferences.getTokenInputKey(providerA)],
        )
        assertEquals(true, prefs[ApiPreferences.legacyCleanupMarkerKey("op-1")])
    }

    @Test
    fun `mutation with blank operationId is rejected`() = runBlocking {
        val prefs = androidx.datastore.preferences.core.preferencesOf().toMutablePreferences()
        try {
            applyLegacyCleanupMutation(prefs, "", listOf(providerA))
            fail("blank operationId must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

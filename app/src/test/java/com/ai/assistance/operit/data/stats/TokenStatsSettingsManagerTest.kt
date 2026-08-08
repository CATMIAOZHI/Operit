package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import java.io.File
import kotlinx.coroutines.runBlocking
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
import org.mockito.kotlin.whenever

/**
 * 统计页设置管理测试（阶段 4，真实 Room + JVM SQLite）：
 * 价格覆盖的非负有限校验/新增/编辑/删除，以及分组/别名的安全事务
 * （身份只走 UPDATE，绝不 REPLACE 级联删除事件；恢复默认分组）。
 */
class TokenStatsSettingsManagerTest {

    private lateinit var tempDir: File
    private lateinit var database: AppDatabase
    private lateinit var dao: TokenStatsDao
    private lateinit var manager: TokenStatsSettingsManager

    @Before
    fun setUp() {
        tempDir = kotlin.io.path.createTempDirectory("token-settings-test").toFile()
        val context = mockContext(tempDir)
        database =
            Room.databaseBuilder(context, AppDatabase::class.java, "app_database")
                .setDriver(JdbcSQLiteDriver())
                .addMigrations(AppDatabase.MIGRATION_28_29, AppDatabase.MIGRATION_29_30, AppDatabase.MIGRATION_30_31)
                .allowMainThreadQueries()
                .build()
        dao = database.tokenStatsDao()
        manager = TokenStatsSettingsManager(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun mockContext(filesDir: File): Context {
        val context = Mockito.mock(Context::class.java)
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.packageName).thenReturn("com.ai.assistance.operit")
        whenever(context.filesDir).thenReturn(filesDir)
        whenever(context.getDatabasePath(any())).thenAnswer { invocation ->
            File(filesDir, invocation.getArgument<String>(0))
        }
        return context
    }

    private suspend fun seedIdentity(
        identityId: String,
        configId: String = "cfg-1",
        provider: String = "OPENAI",
        model: String = "gpt-4o",
        displayModelId: String = TokenStatIdentityResolver.displayModelIdFor(model),
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
            com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity(
                displayModelId = displayModelId,
                normalizedModel = TokenStatIdentityResolver.normalizeModelName(model),
                displayName = model,
            )
        )
    }

    private suspend fun seedEvent(identityId: String, eventId: String) {
        dao.insertEvent(
            TokenStatEventEntity(
                eventId = eventId,
                statIdentityId = identityId,
                category = TokenStatCategory.CHAT.name,
                status = TokenStatStatus.COMPLETED.name,
                acceptedGeneration = 0L,
                startedAtMs = 1_000_000L,
                endedAtMs = 1_001_000L,
                firstTokenAtMs = null,
                uncachedInputTokens = 10L,
                cachedInputTokens = 0L,
                cacheWriteTokens = 0L,
                totalInputTokens = null,
                outputTokens = 5L,
                reasoningTokens = null,
                reasoningIncludedInOutput = true,
                cacheWriteSeparateBilling = false,
                billingMode = BillingMode.TOKEN.name,
                pricingCurrency = PricingCurrency.USD.name,
                inputPricePerMillion = 1.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 2.0,
                pricePerRequest = null,
                pricingSource = PricingSource.DEFAULT.name,
                costInPricingCurrency = 0.001,
                diagnosticsJson = null,
            )
        )
    }

    // ==== 价格覆盖 ====

    @Test
    fun `price override rejects negative and non-finite values without writing`() = runBlocking {
        val negative =
            runCatching {
                manager.upsertPriceOverride(
                    scope = PriceOverrideScope.PROVIDER_MODEL,
                    provider = "OPENAI",
                    model = "gpt-4o",
                    configId = null,
                    billingMode = BillingMode.TOKEN,
                    pricingCurrency = PricingCurrency.USD,
                    inputPricePerMillion = -1.0,
                    cachedInputPricePerMillion = null,
                    cacheWritePricePerMillion = null,
                    outputPricePerMillion = 2.0,
                    pricePerRequest = null,
                )
            }
        assertTrue("negative price must be rejected", negative.isFailure)
        assertEquals(0, dao.getAllPriceOverrides().size)

        val nan =
            runCatching {
                manager.upsertPriceOverride(
                    scope = PriceOverrideScope.PROVIDER_MODEL,
                    provider = "OPENAI",
                    model = "gpt-4o",
                    configId = null,
                    billingMode = BillingMode.TOKEN,
                    pricingCurrency = PricingCurrency.USD,
                    inputPricePerMillion = Double.NaN,
                    cachedInputPricePerMillion = null,
                    cacheWritePricePerMillion = null,
                    outputPricePerMillion = 2.0,
                    pricePerRequest = null,
                )
            }
        assertTrue("NaN price must be rejected", nan.isFailure)
        assertEquals(0, dao.getAllPriceOverrides().size)
    }

    @Test
    fun `price override upsert replaces same business key and normalizes fields`() = runBlocking {
        manager.upsertPriceOverride(
            scope = PriceOverrideScope.PROVIDER_MODEL,
            provider = " OPENAI ",
            model = " Gpt-4o ",
            configId = null,
            billingMode = BillingMode.TOKEN,
            pricingCurrency = PricingCurrency.USD,
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = null,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = 2.0,
            pricePerRequest = null,
        )
        // 同业务组合（规范化后相同）再次写入 → REPLACE 覆盖，仍只有一行
        manager.upsertPriceOverride(
            scope = PriceOverrideScope.PROVIDER_MODEL,
            provider = "openai",
            model = "gpt-4o",
            configId = null,
            billingMode = BillingMode.TOKEN,
            pricingCurrency = PricingCurrency.USD,
            inputPricePerMillion = 3.0,
            cachedInputPricePerMillion = null,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = 4.0,
            pricePerRequest = null,
        )
        val all = dao.getAllPriceOverrides()
        assertEquals(1, all.size)
        val row = all.single()
        assertEquals("openai", row.provider)
        assertEquals("gpt-4o", row.model)
        assertEquals("", row.configId)
        assertEquals(3.0, row.inputPricePerMillion!!, 0.0)
        assertEquals(4.0, row.outputPricePerMillion!!, 0.0)

        // 删除
        manager.deletePriceOverride(PriceOverrideScope.PROVIDER_MODEL, "openai", "gpt-4o", null)
        assertTrue(dao.getAllPriceOverrides().isEmpty())
    }

    @Test
    fun `price override drops fields from the inactive billing mode`() = runBlocking {
        manager.upsertPriceOverride(
            scope = PriceOverrideScope.PROVIDER_MODEL,
            provider = "OPENAI",
            model = "gpt-4o",
            configId = null,
            billingMode = BillingMode.TOKEN,
            pricingCurrency = PricingCurrency.USD,
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = 0.5,
            cacheWritePricePerMillion = 0.8,
            outputPricePerMillion = 2.0,
            pricePerRequest = 99.0,
        )
        val tokenRow = dao.getAllPriceOverrides().single()
        assertNull(tokenRow.pricePerRequest)

        manager.upsertPriceOverride(
            scope = PriceOverrideScope.PROVIDER_MODEL,
            provider = "OPENAI",
            model = "gpt-4o",
            configId = null,
            billingMode = BillingMode.COUNT,
            pricingCurrency = PricingCurrency.USD,
            inputPricePerMillion = 99.0,
            cachedInputPricePerMillion = 99.0,
            cacheWritePricePerMillion = 99.0,
            outputPricePerMillion = 99.0,
            pricePerRequest = 0.01,
        )
        val countRow = dao.getAllPriceOverrides().single()
        assertNull(countRow.inputPricePerMillion)
        assertNull(countRow.cachedInputPricePerMillion)
        assertNull(countRow.cacheWritePricePerMillion)
        assertNull(countRow.outputPricePerMillion)
        assertEquals(0.01, countRow.pricePerRequest!!, 0.0)
    }

    @Test
    fun `reading overrides repairs mixed fields saved by older versions`() = runBlocking {
        dao.upsertPriceOverride(
            scope = PriceOverrideScope.PROVIDER_MODEL.name,
            provider = "OPENAI",
            model = "gpt-4o",
            configId = null,
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = PricingCurrency.USD.name,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
            pricePerRequest = 99.0,
        )

        val repaired = manager.allPriceOverrides().single()

        assertNull(repaired.pricePerRequest)
        assertNull(dao.getAllPriceOverrides().single().pricePerRequest)
    }

    @Test
    fun `config scope override keeps configId and is independent from provider scope`() = runBlocking {
        manager.upsertPriceOverride(
            scope = PriceOverrideScope.CONFIG,
            provider = "OPENAI",
            model = "gpt-4o",
            configId = " cfg-9 ",
            billingMode = BillingMode.COUNT,
            pricingCurrency = PricingCurrency.CNY,
            inputPricePerMillion = null,
            cachedInputPricePerMillion = null,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = null,
            pricePerRequest = 0.01,
        )
        val row = dao.getAllPriceOverrides().single()
        assertEquals("cfg-9", row.configId)
        assertEquals(PriceOverrideScope.CONFIG.name, row.scope)
        assertEquals(BillingMode.COUNT.name, row.billingMode)
        assertEquals(0.01, row.pricePerRequest!!, 0.0)

        // 删除时同样按规范化组合匹配
        manager.deletePriceOverride(PriceOverrideScope.CONFIG, "OPENAI", "gpt-4o", "cfg-9")
        assertTrue(dao.getAllPriceOverrides().isEmpty())
    }

    @Test
    fun `edit keeps business key unchanged and rejects key changes`() = runBlocking {
        manager.upsertPriceOverride(
            scope = PriceOverrideScope.PROVIDER_MODEL,
            provider = "OPENAI",
            model = "gpt-4o",
            configId = null,
            billingMode = BillingMode.TOKEN,
            pricingCurrency = PricingCurrency.USD,
            inputPricePerMillion = 1.0,
            cachedInputPricePerMillion = null,
            cacheWritePricePerMillion = null,
            outputPricePerMillion = 2.0,
            pricePerRequest = null,
        )
        val existing = dao.getAllPriceOverrides().single()

        // 同业务键编辑（大小写/空白差异经规范化后一致）→ 更新成功，仍只有一行
        manager.updatePriceOverride(
            existing,
            TokenStatsPriceOverrideDraft(
                scope = PriceOverrideScope.PROVIDER_MODEL,
                provider = " openai ",
                model = "Gpt-4o",
                configId = null,
                billingMode = BillingMode.TOKEN,
                currency = PricingCurrency.USD,
                inputPricePerMillion = 3.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 4.0,
                pricePerRequest = null,
            ),
        )
        assertEquals(1, dao.getAllPriceOverrides().size)
        assertEquals(3.0, dao.getAllPriceOverrides().single().inputPricePerMillion!!, 0.0)

        // 改 provider → 拒绝，行不变
        val keyChanged =
            runCatching {
                manager.updatePriceOverride(
                    existing,
                    TokenStatsPriceOverrideDraft(
                        scope = PriceOverrideScope.PROVIDER_MODEL,
                        provider = "anthropic",
                        model = "gpt-4o",
                        configId = null,
                        billingMode = BillingMode.TOKEN,
                        currency = PricingCurrency.USD,
                        inputPricePerMillion = 5.0,
                        cachedInputPricePerMillion = null,
                        cacheWritePricePerMillion = null,
                        outputPricePerMillion = 5.0,
                        pricePerRequest = null,
                    ),
                )
            }
        assertTrue("changed provider must be rejected", keyChanged.isFailure)
        assertEquals(1, dao.getAllPriceOverrides().size)

        // 改 scope（CONFIG 且带新 configId）→ 拒绝，行不变
        val scopeChanged =
            runCatching {
                manager.updatePriceOverride(
                    existing,
                    TokenStatsPriceOverrideDraft(
                        scope = PriceOverrideScope.CONFIG,
                        provider = "openai",
                        model = "gpt-4o",
                        configId = "cfg-2",
                        billingMode = BillingMode.TOKEN,
                        currency = PricingCurrency.USD,
                        inputPricePerMillion = 5.0,
                        cachedInputPricePerMillion = null,
                        cacheWritePricePerMillion = null,
                        outputPricePerMillion = 5.0,
                        pricePerRequest = null,
                    ),
                )
            }
        assertTrue("changed scope must be rejected", scopeChanged.isFailure)
        assertEquals(1, dao.getAllPriceOverrides().size)
    }

    @Test
    fun `config scope with blank configId is rejected without writing`() = runBlocking {
        val blank = runCatching {
            manager.upsertPriceOverride(
                scope = PriceOverrideScope.CONFIG,
                provider = "OPENAI",
                model = "gpt-4o",
                configId = "   ",
                billingMode = BillingMode.TOKEN,
                pricingCurrency = PricingCurrency.USD,
                inputPricePerMillion = 1.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 2.0,
                pricePerRequest = null,
            )
        }
        assertTrue("blank configId must be rejected", blank.isFailure)
        assertEquals(0, dao.getAllPriceOverrides().size)

        // 编辑路径同样拒绝：CONFIG 空 configId 的草稿在规范化阶段即失败
        val nullConfig = runCatching {
            manager.upsertPriceOverride(
                scope = PriceOverrideScope.CONFIG,
                provider = "OPENAI",
                model = "gpt-4o",
                configId = null,
                billingMode = BillingMode.TOKEN,
                pricingCurrency = PricingCurrency.USD,
                inputPricePerMillion = 1.0,
                cachedInputPricePerMillion = null,
                cacheWritePricePerMillion = null,
                outputPricePerMillion = 2.0,
                pricePerRequest = null,
            )
        }
        assertTrue("null configId must be rejected", nullConfig.isFailure)
        assertEquals(0, dao.getAllPriceOverrides().size)
    }

    // ==== 分组 / 别名 ====

    @Test
    fun `moving identities to a group preserves events and never replaces identity rows`() =
        runBlocking {
            seedIdentity("id-1", model = "gpt-4o")
            seedIdentity("id-2", configId = "cfg-2", model = "gpt-4o")
            seedEvent("id-1", "e1")
            seedEvent("id-2", "e2")

            manager.createGroupAndMove("我的组", listOf("id-1", "id-2"))
            val groupId =
                dao.getAllDisplayModels()
                    .first { it.displayName == "我的组" && it.displayModelId.startsWith("custom-group-") }
                    .displayModelId

            // 身份只被 UPDATE：displayModelId 变更，identityId 不变
            val identities = dao.getAllIdentities().associateBy { it.identityId }
            assertEquals(groupId, identities.getValue("id-1").displayModelId)
            assertEquals(groupId, identities.getValue("id-2").displayModelId)

            // 事件未被级联删除（无 REPLACE）
            assertEquals(2, dao.getAllEvents().size)
            assertEquals(2, dao.getAllEvents().count { it.statIdentityId in setOf("id-1", "id-2") })
        }

    @Test
    fun `rename only changes displayName and keeps identities attached`() = runBlocking {
        seedIdentity("id-1", model = "gpt-4o")
        seedEvent("id-1", "e1")
        val defaultId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")

        manager.renameDisplayGroup(defaultId, "GPT-4 主力")
        val group = dao.getDisplayModel(defaultId)
        assertNotNull(group)
        assertEquals("GPT-4 主力", group!!.displayName)
        assertEquals(defaultId, dao.getIdentity("id-1")!!.displayModelId)
        assertEquals(1, dao.getAllEvents().size)
    }

    @Test
    fun `restore default groups moves each identity back to its own normalized model group`() =
        runBlocking {
            seedIdentity("id-a", model = "gpt-4o")
            seedIdentity("id-b", configId = "cfg-2", model = "claude-3-5-sonnet")
            seedEvent("id-a", "e1")
            seedEvent("id-b", "e2")

            // 先把两个身份手动合并到 gpt-4o 组
            manager.moveIdentitiesToGroup(
                listOf("id-a", "id-b"),
                TokenStatIdentityResolver.displayModelIdFor("gpt-4o"),
            )
            manager.restoreDefaultGroups(TokenStatIdentityResolver.displayModelIdFor("gpt-4o"))

            val identities = dao.getAllIdentities().associateBy { it.identityId }
            assertEquals(
                TokenStatIdentityResolver.displayModelIdFor("gpt-4o"),
                identities.getValue("id-a").displayModelId,
            )
            assertEquals(
                TokenStatIdentityResolver.displayModelIdFor("claude-3-5-sonnet"),
                identities.getValue("id-b").displayModelId,
            )
            // 事件完整保留
            assertEquals(2, dao.getAllEvents().size)
        }

    @Test
    fun `move to existing group is idempotent and blank names are rejected`() = runBlocking {
        seedIdentity("id-1", model = "gpt-4o")
        val defaultId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")
        manager.moveIdentitiesToGroup(listOf("id-1"), defaultId)
        manager.moveIdentitiesToGroup(listOf("id-1"), defaultId)
        assertEquals(defaultId, dao.getIdentity("id-1")!!.displayModelId)

        val blankRename = runCatching { manager.renameDisplayGroup(defaultId, "   ") }
        assertTrue("blank display name must be rejected", blankRename.isFailure)
        manager.renameDisplayGroup(defaultId, " 新名字 ")
        assertEquals("新名字", dao.getDisplayModel(defaultId)!!.displayName)

        val emptyMerge = runCatching { manager.moveIdentitiesToGroup(emptyList(), defaultId) }
        assertTrue("empty identity list must be rejected", emptyMerge.isFailure)
    }

    // ==== P1 修复：分组元数据与统计筛选无关 ====

    @Test
    fun `group models expose complete membership even when only one member has events`() =
        runBlocking {
            seedIdentity("id-1", model = "gpt-4o")
            seedIdentity("id-2", configId = "cfg-2", model = "claude-3-5-sonnet")
            // 只有 A 有事件：范围明细只能看到 A，但分组操作必须拿到完整成员
            seedEvent("id-1", "e1")
            manager.createGroupAndMove("组合", listOf("id-1", "id-2"))
            val groupId =
                dao.getAllDisplayModels()
                    .first { it.displayName == "组合" && it.displayModelId.startsWith("custom-group-") }
                    .displayModelId

            val groups = manager.groupModels()
            val merged = groups.first { it.displayModelId == groupId }
            assertEquals("组合", merged.displayName)
            assertEquals(setOf("id-1", "id-2"), merged.memberIdentityIds.toSet())
            assertEquals(
                setOf(
                    TokenStatsGroupMemberInfo("id-1", "cfg-1", "OPENAI", "gpt-4o"),
                    TokenStatsGroupMemberInfo("id-2", "cfg-2", "OPENAI", "claude-3-5-sonnet"),
                ),
                merged.members.toSet(),
            )

            // 默认分组（成员被移出后变空）仍在列表中：空组是合法合并目标
            val gptId = TokenStatIdentityResolver.displayModelIdFor("gpt-4o")
            val emptyGroup = groups.first { it.displayModelId == gptId }
            assertTrue(emptyGroup.memberIdentityIds.isEmpty())
        }

    @Test
    fun `group models include groups without display row and keep fallback name`() =
        runBlocking {
            // 身份引用的 displayModelId 没有对应展示行：组名回退到 displayModelId
            dao.insertIdentityIfAbsent(
                TokenStatIdentityEntity(
                    identityId = "id-3",
                    configId = "cfg-3",
                    provider = "ANTHROPIC",
                    model = "sonnet",
                    displayModelId = "orphan-group",
                )
            )
            val groups = manager.groupModels()
            val orphan = groups.first { it.displayModelId == "orphan-group" }
            assertEquals("orphan-group", orphan.displayName)
            assertEquals(listOf("id-3"), orphan.memberIdentityIds)
        }
}

package com.ai.assistance.operit.data.stats

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room schema 导出契约测试（v28 → v29 → v30 → v31）。
 *
 * 解析仓库提交的 schema JSON（app/schemas），校验：
 * 1. v29 只新增统计账本 5 张表，既有表 createSql 完全不变（防迁移回归）；
 * 2. v30 只给事件表增加脱敏诊断列，其余表 createSql 完全不变；
 * 3. v31 只新增范围删除 tombstone 表（token_stat_range_cutoffs）与 legacy cleanup
 *    outbox 两张表（token_stat_cleanup_operations/items），其余表不变；
 * 4. 新表的列/主键/索引/外键与迁移 SQL 一致（防迁移漏建索引导致
 *    Room identityHash 校验失败）；
 * 5. 新表与既有表位于同一个 app_database 文件中 —— 现有整库文件级
 *    备份/恢复（RoomDatabaseBackupManager/RoomDatabaseRestoreManager）
 *    自动覆盖这些表，无需逐表接线。
 */
class TokenStatSchemaContractTest {

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    private fun schemaFile(version: Int): File =
        File("schemas/com.ai.assistance.operit.data.db.AppDatabase/$version.json")

    private fun loadSchema(version: Int): RoomSchema {
        val file = schemaFile(version)
        assertTrue("schema export missing: ${file.absolutePath}", file.isFile)
        return json.decodeFromString(file.readText())
    }

    @Test
    fun `v29 bumps database version and only adds the statistics tables`() {
        val v28 = loadSchema(28)
        val v29 = loadSchema(29)

        assertEquals(29, v29.database.version)
        val v28Tables = v28.database.entities.map { it.tableName }.toSet()
        val v29Tables = v29.database.entities.map { it.tableName }.toSet()

        // 既有表全部保留且 createSql 完全一致
        v28Tables.forEach { table ->
            val before = v28.entity(table).normalizedCreateSql()
            val after = v29.entity(table).normalizedCreateSql()
            assertEquals("existing table changed: $table", before, after)
        }

        // 只新增 6 张统计/恢复相关表
        val expectedNew = setOf(
            "token_stat_events",
            "token_stat_identities",
            "token_stat_display_models",
            "token_stat_price_overrides",
            "token_stat_baselines",
            "token_stat_restore_generations",
        )
        assertEquals(v28Tables.size + expectedNew.size, v29Tables.size)
        assertEquals(expectedNew, v29Tables - v28Tables)
    }

    @Test
    fun `event table carries the ledger contract`() {
        val v30 = loadSchema(30)
        val event = v30.entity("token_stat_events")
        val createSql = event.normalizedCreateSql()

        // 稳定事件标识与时间
        assertContains(createSql, "`eventId` TEXT NOT NULL")
        assertContains(createSql, "`acceptedGeneration` INTEGER NOT NULL")
        assertContains(createSql, "`startedAtMs` INTEGER NOT NULL")
        assertContains(createSql, "`endedAtMs` INTEGER NOT NULL")
        assertContains(createSql, "`firstTokenAtMs` INTEGER")
        // 未知值用 null 表达，不静默为 0；缓存读取/写入与输出、推理并列
        assertContains(createSql, "`uncachedInputTokens` INTEGER")
        assertContains(createSql, "`cachedInputTokens` INTEGER")
        assertContains(createSql, "`cacheWriteTokens` INTEGER")
        assertContains(createSql, "`outputTokens` INTEGER")
        assertContains(createSql, "`reasoningTokens` INTEGER")
        assertContains(createSql, "`reasoningIncludedInOutput` INTEGER")
        // 结构化计费列（v30）：总输入与缓存写入计费模型，重估直接读取
        assertContains(createSql, "`totalInputTokens` INTEGER")
        assertContains(createSql, "`cacheWriteSeparateBilling` INTEGER")
        // 原币价格快照与原币成本（不冻结汇率）
        assertContains(createSql, "`pricingCurrency` TEXT NOT NULL")
        assertContains(createSql, "`inputPricePerMillion` REAL")
        assertContains(createSql, "`cachedInputPricePerMillion` REAL")
        assertContains(createSql, "`cacheWritePricePerMillion` REAL")
        assertContains(createSql, "`outputPricePerMillion` REAL")
        assertContains(createSql, "`costInPricingCurrency` REAL")
        assertContains(createSql, "PRIMARY KEY(`eventId`)")
        // 脱敏诊断列（阶段 2）：可空 TEXT，只存来源标签与计数
        assertContains(createSql, "`diagnosticsJson` TEXT")
        // 不保存正文/凭据
        assertContains(createSql, "FOREIGN KEY(`statIdentityId`) REFERENCES `token_stat_identities`(`identityId`)")

        // 迁移 SQL 必须包含全部事件索引（Room 打开时校验）
        val indexNames = event.indices.map { it.name }
        assertTrue(indexNames.contains("index_token_stat_events_statIdentityId_startedAtMs"))
        assertTrue(indexNames.contains("index_token_stat_events_startedAtMs"))
        assertTrue(indexNames.contains("index_token_stat_events_category_startedAtMs"))
    }

    @Test
    fun `v30 only adds structured billing columns diagnostics and reset cutoff table`() {
        val v29 = loadSchema(29)
        val v30 = loadSchema(30)

        assertEquals(30, v30.database.version)
        val v29Tables = v29.database.entities.map { it.tableName }.toSet()
        val v30Tables = v30.database.entities.map { it.tableName }.toSet()

        // v30 只新增 reset tombstone 表（P1-3：reset 与 spool 排空的一致同步边界）
        assertEquals(
            setOf("token_stat_reset_cutoffs"),
            v30Tables - v29Tables,
        )

        // 除 token_stat_events 外，其余 v29 表 createSql 完全一致
        v29Tables.filter { it != "token_stat_events" }.forEach { table ->
            val before = v29.entity(table).normalizedCreateSql()
            val after = v30.entity(table).normalizedCreateSql()
            assertEquals("table changed between v29 and v30: $table", before, after)
        }

        // 事件表只增加结构化列（totalInputTokens/cacheWriteSeparateBilling）与
        // diagnosticsJson 一列，不改变既有列
        val beforeEvent = v29.entity("token_stat_events").normalizedCreateSql()
        val afterEvent = v30.entity("token_stat_events").normalizedCreateSql()
        val beforeColumns = columnList(beforeEvent)
        val afterColumns = columnList(afterEvent)
        assertEquals(
            "only structured billing columns and diagnosticsJson may be added between v29 and v30",
            setOf(
                "`totalInputTokens` INTEGER",
                "`cacheWriteSeparateBilling` INTEGER",
                "`diagnosticsJson` TEXT",
                "`acceptedGeneration` INTEGER NOT NULL",
            ),
            afterColumns - beforeColumns,
        )
    }

    @Test
    fun `v31 only adds the range cutoff and cleanup outbox tables`() {
        val v30 = loadSchema(30)
        val v31 = loadSchema(31)

        assertEquals(31, v31.database.version)
        val v30Tables = v30.database.entities.map { it.tableName }.toSet()
        val v31Tables = v31.database.entities.map { it.tableName }.toSet()

        // v31 只新增范围删除 tombstone 表与 legacy cleanup outbox 两张表
        // （阶段 5：范围删除/跨存储清理与导入 fence 的持久化锚点）
        assertEquals(
            setOf(
                "token_stat_range_cutoffs",
                "token_stat_cleanup_operations",
                "token_stat_cleanup_items",
            ),
            v31Tables - v30Tables,
        )

        // 除新增表外，其余全部 v30 表 createSql 完全一致（防迁移回归）
        v30Tables.forEach { table ->
            val before = v30.entity(table).normalizedCreateSql()
            val after = v31.entity(table).normalizedCreateSql()
            assertEquals("table changed between v30 and v31: $table", before, after)
        }
    }

    @Test
    fun `cleanup outbox tables carry the operation and item contracts`() {
        val v31 = loadSchema(31)

        val op = v31.entity("token_stat_cleanup_operations")
        val opSql = op.normalizedCreateSql()
        assertContains(opSql, "`operationId` TEXT NOT NULL")
        assertContains(opSql, "`scope` TEXT NOT NULL")
        assertContains(opSql, "`targetRef` TEXT NOT NULL")
        assertContains(opSql, "`deleteBaselines` INTEGER NOT NULL")
        assertContains(opSql, "`status` TEXT NOT NULL")
        assertContains(opSql, "`createdAtMs` INTEGER NOT NULL")
        assertContains(opSql, "PRIMARY KEY(`operationId`)")
        assertTrue(op.indices.isEmpty())

        val item = v31.entity("token_stat_cleanup_items")
        val itemSql = item.normalizedCreateSql()
        assertContains(itemSql, "`operationId` TEXT NOT NULL")
        assertContains(itemSql, "`identityId` TEXT NOT NULL")
        assertContains(itemSql, "`provider` TEXT NOT NULL")
        assertContains(itemSql, "`model` TEXT NOT NULL")
        assertContains(itemSql, "PRIMARY KEY(`operationId`, `identityId`)")
        assertContains(
            itemSql,
            "FOREIGN KEY(`operationId`) REFERENCES `token_stat_cleanup_operations`(`operationId`)",
        )
        assertTrue(
            item.indices.any {
                it.name == "index_token_stat_cleanup_items_operationId" && !it.unique
            }
        )
    }

    @Test
    fun `range cutoff table is the durable range deletion tombstone anchor`() {
        val v31 = loadSchema(31)
        val cutoff = v31.entity("token_stat_range_cutoffs")
        val createSql = cutoff.normalizedCreateSql()

        assertContains(createSql, "`generation` INTEGER NOT NULL")
        assertContains(createSql, "`startMs` INTEGER NOT NULL")
        assertContains(createSql, "`endMs` INTEGER NOT NULL")
        assertContains(createSql, "PRIMARY KEY(`generation`)")
        assertTrue("cutoff table must not carry foreign keys", !createSql.contains("FOREIGN KEY"))
        assertTrue(cutoff.indices.isEmpty())
    }

    @Test
    fun `reset cutoff table is the durable reset tombstone anchor`() {
        val v30 = loadSchema(30)
        val cutoff = v30.entity("token_stat_reset_cutoffs")
        val createSql = cutoff.normalizedCreateSql()

        assertContains(createSql, "`kind` TEXT NOT NULL")
        assertContains(createSql, "`provider` TEXT NOT NULL")
        assertContains(createSql, "`model` TEXT NOT NULL")
        assertContains(createSql, "`generation` INTEGER NOT NULL")
        assertContains(createSql, "PRIMARY KEY(`kind`, `provider`, `model`)")
        assertTrue("cutoff table must not carry foreign keys", !createSql.contains("FOREIGN KEY"))
        assertTrue(cutoff.indices.isEmpty())
    }

    /** 从 CREATE TABLE 语句提取列定义集合（schema 导出为单行，按逗号切分）。 */
    private fun columnList(createSql: String): Set<String> {
        val inner = createSql.substringAfter("(").substringBeforeLast(")")
        return inner.split(",").map { it.trim() }.filter { it.startsWith("`") }.toSet()
    }

    @Test
    fun `baseline table marks estimates and carries idempotency fingerprint`() {
        val v29 = loadSchema(29)
        val baseline = v29.entity("token_stat_baselines")
        val createSql = baseline.normalizedCreateSql()

        assertContains(createSql, "`isEstimated` INTEGER NOT NULL")
        assertContains(createSql, "`fingerprint` TEXT NOT NULL")
        assertContains(createSql, "`requestCount` INTEGER NOT NULL")
        assertContains(createSql, "`costInPricingCurrency` REAL")
        assertContains(createSql, "PRIMARY KEY(`identityId`)")
        // 冻结价格快照（首次迁移冻结；无 sourceComplete 启发式字段）
        assertTrue(!createSql.contains("sourceComplete"))
        assertContains(createSql, "`frozenBillingMode` TEXT NOT NULL")
        assertContains(createSql, "`frozenInputPricePerMillion` REAL")
        assertContains(createSql, "`frozenCachedInputPricePerMillion` REAL")
        assertContains(createSql, "`frozenOutputPricePerMillion` REAL")
        assertContains(createSql, "`frozenPricePerRequest` REAL")
        // 展示分组不在此表重复保存（单一事实源是 token_stat_identities）
        assertTrue(!createSql.contains("displayModelId"))
        assertTrue(baseline.indices.isEmpty())
    }

    @Test
    fun `restore generation table is the idempotency anchor`() {
        val v29 = loadSchema(29)
        val generations = v29.entity("token_stat_restore_generations")
        val createSql = generations.normalizedCreateSql()

        assertContains(createSql, "`generation` TEXT NOT NULL")
        assertContains(createSql, "`appliedAtMs` INTEGER NOT NULL")
        assertContains(createSql, "PRIMARY KEY(`generation`)")
    }

    @Test
    fun `identity display model and price override tables keep their contracts`() {
        val v29 = loadSchema(29)
        val identity = v29.entity("token_stat_identities")
        assertContains(identity.normalizedCreateSql(), "`configId` TEXT NOT NULL")
        assertContains(identity.normalizedCreateSql(), "`provider` TEXT NOT NULL")
        assertContains(identity.normalizedCreateSql(), "`model` TEXT NOT NULL")
        assertTrue(
            identity.indices.any {
                it.name == "index_token_stat_identities_configId_provider_model" && it.unique
            }
        )

        val display = v29.entity("token_stat_display_models")
        assertContains(display.normalizedCreateSql(), "`displayName` TEXT NOT NULL")
        assertTrue(
            display.indices.any {
                it.name == "index_token_stat_display_models_normalizedModel" && it.unique
            }
        )

        val override = v29.entity("token_stat_price_overrides")
        val overrideSql = override.normalizedCreateSql()
        // 唯一性由规范化业务字段 (scope, provider, model, configId) 的唯一索引强制；
        // rowId 只是内部自增主键，不存在可伪造的业务主键
        assertContains(overrideSql, "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL")
        assertContains(overrideSql, "`scope` TEXT NOT NULL")
        assertContains(overrideSql, "`provider` TEXT NOT NULL")
        assertContains(overrideSql, "`model` TEXT NOT NULL")
        assertContains(overrideSql, "`configId` TEXT NOT NULL")
        assertContains(overrideSql, "`cacheWritePricePerMillion` REAL")
        assertTrue(!overrideSql.contains("businessKey"))
        assertTrue(
            override.indices.any {
                it.name == "index_token_stat_price_overrides_scope_provider_model_configId" &&
                    it.unique
            }
        )
    }

    private fun assertContains(haystack: String, needle: String) {
        assertTrue("expected schema to contain: $needle", haystack.contains(needle))
    }

    private fun RoomSchema.Entity.normalizedCreateSql(): String =
        createSql.replace("\${TABLE_NAME}", tableName)

    @Serializable
    private data class RoomSchema(
        val database: Database,
    ) {
        @Serializable
        data class Database(
            val version: Int,
            val entities: List<Entity>,
        )

        @Serializable
        data class Entity(
            @SerialName("tableName") val tableName: String,
            @SerialName("createSql") val createSql: String,
            @SerialName("indices") val indices: List<Index> = emptyList(),
        )

        @Serializable
        data class Index(
            val name: String,
            val unique: Boolean = false,
        )
    }

    private fun RoomSchema.entity(tableName: String): RoomSchema.Entity =
        database.entities.firstOrNull { it.tableName == tableName }
            ?: error("table not found in schema: $tableName")
}

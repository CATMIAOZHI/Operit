package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatCleanupItemEntity
import com.ai.assistance.operit.data.model.TokenStatCleanupOperationEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.model.TokenStatRangeCutoffEntity
import com.ai.assistance.operit.data.model.TokenStatResetCutoffEntity
import com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity
import com.ai.assistance.operit.data.stats.TokenStatIdentityResolver
import com.ai.assistance.operit.data.stats.TokenActivityEventRow
import com.ai.assistance.operit.data.stats.TokenStatsGroupMetadataSnapshot
import com.ai.assistance.operit.data.stats.TokenStatsLifetimeRead
import com.ai.assistance.operit.data.stats.TokenStatsQuerySnapshot
import androidx.room.Transaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * 统计账本 DAO（阶段 1）。
 *
 * - 事件按 [TokenStatEventEntity.eventId] 幂等插入（重复标识忽略，不重复入账）。
 * - baseline 以 identityId 为主键整体替换（受控补导时 REPLACE；普通导入只新增）。
 * - 身份绝不可走删除式 REPLACE（会级联删除该身份的事件）：新增用 INSERT IGNORE，
 *   分组变更走显式安全 UPDATE。
 * - 价格覆盖：**唯一写入入口是 [upsertPriceOverride]**（校验 scope 枚举 +
 *   规范化业务字段，见 [TokenStatPriceOverrideEntity.normalized]）；
 *   底层 [insertPriceOverride] 为 protected，不暴露任意 entity 插入的公开路径，
 *   防止绕过规范化写入导致唯一索引失效。
 */
@Dao
abstract class TokenStatsDao {

    // ==== 事件 ====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEvent(event: TokenStatEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEvents(events: List<TokenStatEventEntity>)

    @Query("SELECT * FROM token_stat_events WHERE eventId = :eventId")
    abstract suspend fun getEvent(eventId: String): TokenStatEventEntity?

    @Query("SELECT * FROM token_stat_events")
    abstract suspend fun getAllEvents(): List<TokenStatEventEntity>

    /** 活动热力图只读取所需列，避免把价格、诊断等完整事件字段整表实体化。 */
    @Query(
        "SELECT startedAtMs, uncachedInputTokens, cachedInputTokens, cacheWriteTokens, " +
            "totalInputTokens, outputTokens, reasoningTokens, reasoningIncludedInOutput, " +
            "cacheWriteSeparateBilling " +
            "FROM token_stat_events"
    )
    abstract suspend fun getTokenActivityRows(): List<TokenActivityEventRow>

    @Query("SELECT COUNT(*) FROM token_stat_events")
    abstract suspend fun countEvents(): Int

    /**
     * 阶段 3 统计查询：单次读取指定时间范围（半开区间 [startMs, endMs)，
     * 按 [TokenStatEventEntity.startedAtMs] 归属）内的全部事件，在内存中单遍聚合。
     * 走 `index_token_stat_events_startedAtMs` 索引；禁止逐桶/逐模型拆分查询。
     */
    @Query(
        "SELECT * FROM token_stat_events " +
            "WHERE startedAtMs >= :startMs AND startedAtMs < :endMs"
    )
    abstract suspend fun getEventsInRange(startMs: Long, endMs: Long): List<TokenStatEventEntity>

    /**
     * 阶段 3 统计查询：单次读取时间范围内属于给定展示模型分组（identity 的
     * displayModelId，单一事实来源）的事件。IN 列表由调用方提供，模型数再多也
     * 只有一条查询，不产生按模型 N+1。
     */
    @Query(
        "SELECT e.* FROM token_stat_events e " +
            "INNER JOIN token_stat_identities i ON e.statIdentityId = i.identityId " +
            "WHERE e.startedAtMs >= :startMs AND e.startedAtMs < :endMs " +
            "AND i.displayModelId IN (:displayModelIds)"
    )
    abstract suspend fun getEventsInRangeForDisplayModels(
        startMs: Long,
        endMs: Long,
        displayModelIds: List<String>,
    ): List<TokenStatEventEntity>

    /** 时间范围内是否存在事件（初始回退选择用，EXISTS 短路，走 startedAtMs 索引）。 */
    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM token_stat_events " +
            "WHERE startedAtMs >= :startMs AND startedAtMs < :endMs" +
            ")"
    )
    abstract suspend fun rangeHasEvents(startMs: Long, endMs: Long): Boolean

    /**
     * 生命周期分页读取（P2-1）：`(startedAtMs, eventId)` 键集分页，升序、无重复、
     * 无遗漏；配合 [loadLifetimeSnapshot] 在**同一事务**内逐页读取，避免整表
     * 实体化的内存峰值。调用方只在 `page.size == limit` 时推进游标继续取下一页。
     */
    @Query(
        "SELECT * FROM token_stat_events " +
            "WHERE (startedAtMs > :afterStartMs OR " +
            "(startedAtMs = :afterStartMs AND eventId > :afterEventId)) " +
            "ORDER BY startedAtMs ASC, eventId ASC LIMIT :limit"
    )
    abstract suspend fun getEventsPage(
        afterStartMs: Long,
        afterEventId: String,
        limit: Int,
    ): List<TokenStatEventEntity>

    /**
     * 单条 IN 查询允许的最大参数个数（P2-2）：SQLite 变量上限默认 999，
     * 留 99 余量取 900；超过时在同一事务内分块查询再合并。
     */
    companion object {
        const val MAX_IN_VALUES = 900
    }

    /**
     * 阶段 3 范围查询的**同事务只读快照**（P1-2）：identity/display model/价格覆盖
     * （重估口径才读）/事件在同一个 Room 事务内固定读取，事务外纯聚合；并发写入
     * 要么整体可见要么整体不可见，杜绝“summary 有事件但模型桶缺失”的拆分状态。
     *
     * [displayModelIds] 语义（P2-2）：null = 全部模型；空列表 = **无事件**（不是
     * 全部）；非空 = 走 JOIN 单条 IN 查询；超过 [MAX_IN_VALUES] 时在**同一事务**
     * 内按 ≤900 分块查询（去重后），合并结果按 (startedAtMs, eventId) 稳定排序。
     */
    @Transaction
    open suspend fun loadRangeSnapshot(
        startMs: Long,
        endMs: Long,
        displayModelIds: List<String>?,
        includeOverrides: Boolean,
    ): TokenStatsQuerySnapshot {
        val identitiesById = getAllIdentities().associateBy { it.identityId }
        val displayModelsById = getAllDisplayModels().associateBy { it.displayModelId }
        val overrides = if (includeOverrides) getAllPriceOverrides() else emptyList()
        val events =
            when {
                displayModelIds == null -> getEventsInRange(startMs, endMs)
                displayModelIds.isEmpty() -> emptyList()
                else -> getEventsInRangeForDisplayModelsChunked(startMs, endMs, displayModelIds)
            }
        return TokenStatsQuerySnapshot(
            events = events,
            identitiesById = identitiesById,
            displayModelsById = displayModelsById,
            overrides = overrides,
            baselines = emptyList(),
        )
    }

    /**
     * 生命周期快照（P1-2/P2-1）：identity/display model/价格覆盖/baseline 在
     * **同一事务**内一次读取；事件按 `(startedAtMs, eventId)` 键集分页（每页至多
     * [pageSize] 条）逐页回调 [onEventsPage]，由聚合器增量累加——避免整表实体化
     * 峰值，且分页与事务同界（页面间快照一致）。
     */
    @Transaction
    open suspend fun loadLifetimeSnapshot(
        includeOverrides: Boolean,
        pageSize: Int,
        onEventsPage: (
            List<TokenStatEventEntity>,
            Map<String, TokenStatIdentityEntity>,
            List<TokenStatPriceOverrideEntity>,
        ) -> Unit,
    ): TokenStatsLifetimeRead {
        val identitiesById = getAllIdentities().associateBy { it.identityId }
        val displayModelsById = getAllDisplayModels().associateBy { it.displayModelId }
        val overrides = if (includeOverrides) getAllPriceOverrides() else emptyList()
        val baselines = getAllBaselines()
        var afterStartMs = Long.MIN_VALUE
        var afterEventId = ""
        var totalEvents = 0L
        while (true) {
            val page = getEventsPage(afterStartMs, afterEventId, pageSize)
            if (page.isEmpty()) break
            totalEvents += page.size
            onEventsPage(page, identitiesById, overrides)
            if (page.size < pageSize) break
            val last = page.last()
            afterStartMs = last.startedAtMs
            afterEventId = last.eventId
        }
        return TokenStatsLifetimeRead(
            identitiesById = identitiesById,
            displayModelsById = displayModelsById,
            overrides = overrides,
            baselines = baselines,
            totalEvents = totalEvents,
        )
    }

    /**
     * 分组元数据快照（阶段 4 P1 修复）：全量身份 + 展示模型行在**同一个事务**内
     * 固定读取，与统计筛选（时间/模型/分类/状态）无关——分组管理与合并的
     * 成员/目标必须来自完整归属，而筛选范围明细只包含有事件的身份/分组
     * （事件存在与否不影响身份的分组成员身份）。
     */
    @Transaction
    open suspend fun loadGroupMetadataSnapshot(): TokenStatsGroupMetadataSnapshot {
        val identities = getAllIdentities()
        val displayModels = getAllDisplayModels()
        return TokenStatsGroupMetadataSnapshot(
            identities = identities,
            displayModels = displayModels,
        )
    }

    private suspend fun getEventsInRangeForDisplayModelsChunked(
        startMs: Long,
        endMs: Long,
        displayModelIds: List<String>,
    ): List<TokenStatEventEntity> {
        val distinct = displayModelIds.distinct()
        if (distinct.size <= MAX_IN_VALUES) {
            return getEventsInRangeForDisplayModels(startMs, endMs, distinct)
        }
        val merged = ArrayList<TokenStatEventEntity>()
        for (chunk in distinct.chunked(MAX_IN_VALUES)) {
            merged += getEventsInRangeForDisplayModels(startMs, endMs, chunk)
        }
        // 分块结果合并后按 (startedAtMs, eventId) 稳定排序（聚合对顺序不敏感，
        // 这里只是为了契约明确；分块都在同一事务快照内，不产生拆分状态）。
        return merged.sortedWith(compareBy({ it.startedAtMs }, { it.eventId }))
    }

    @Query("SELECT * FROM token_stat_events WHERE statIdentityId = :identityId")
    abstract fun observeEventsByIdentity(identityId: String): Flow<List<TokenStatEventEntity>>

    @Query("DELETE FROM token_stat_events WHERE statIdentityId = :identityId")
    abstract suspend fun deleteEventsByIdentity(identityId: String): Int

    /** 按成员身份批量删除事件（仅由事务方法分块调用，IN 数量受 [MAX_IN_VALUES] 限制）。 */
    @Query("DELETE FROM token_stat_events WHERE statIdentityId IN (:identityIds)")
    protected abstract suspend fun deleteEventsByIdentitiesQuery(identityIds: List<String>): Int

    /** 按成员身份批量删除 baseline（仅由事务方法分块调用）。 */
    @Query("DELETE FROM token_stat_baselines WHERE identityId IN (:identityIds)")
    protected abstract suspend fun deleteBaselinesByIdentitiesQuery(identityIds: List<String>): Int

    /** 分块删除指定身份集合的事件（同一调用方事务内执行，结果计数累加）。 */
    protected suspend fun deleteEventsByIdentities(identityIds: List<String>): Int {
        var deleted = 0
        for (chunk in identityIds.distinct().chunked(MAX_IN_VALUES)) {
            deleted += deleteEventsByIdentitiesQuery(chunk)
        }
        return deleted
    }

    /** 分块删除指定身份集合的 baseline。 */
    protected suspend fun deleteBaselinesByIdentities(identityIds: List<String>): Int {
        var deleted = 0
        for (chunk in identityIds.distinct().chunked(MAX_IN_VALUES)) {
            deleted += deleteBaselinesByIdentitiesQuery(chunk)
        }
        return deleted
    }

    /** 删除半开区间 [startMs, endMs) 内的事件（走 startedAtMs 索引；仅事务方法调用）。 */
    @Query("DELETE FROM token_stat_events WHERE startedAtMs >= :startMs AND startedAtMs < :endMs")
    protected abstract suspend fun deleteEventsInRange(startMs: Long, endMs: Long): Int

    @Query(
        "DELETE FROM token_stat_events WHERE statIdentityId IN " +
            "(SELECT identityId FROM token_stat_identities " +
            "WHERE provider = :provider AND model = :model)"
    )
    abstract suspend fun deleteEventsByProviderModel(provider: String, model: String): Int

    @Query("DELETE FROM token_stat_events")
    abstract suspend fun deleteAllEvents(): Int

    // ==== 统计身份 ====
    // 身份绝不可走删除式 REPLACE：REPLACE = DELETE + INSERT，会通过外键
    // 级联删除该身份下的全部事件（token_stat_events ON DELETE CASCADE）。
    // 新增身份用 INSERT IGNORE（已存在则跳过）；分组变更走显式安全 UPDATE。

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIdentityIfAbsent(identity: TokenStatIdentityEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIdentitiesIfAbsent(identities: List<TokenStatIdentityEntity>): List<Long>

    @Query(
        "UPDATE token_stat_identities SET displayModelId = :displayModelId " +
            "WHERE identityId = :identityId"
    )
    abstract suspend fun updateIdentityDisplayModel(identityId: String, displayModelId: String): Int

    @Query("SELECT * FROM token_stat_identities WHERE identityId = :identityId")
    abstract suspend fun getIdentity(identityId: String): TokenStatIdentityEntity?

    @Query(
        "SELECT * FROM token_stat_identities " +
            "WHERE configId = :configId AND provider = :provider AND model = :model " +
            "LIMIT 1"
    )
    abstract suspend fun getIdentityByTriple(
        configId: String,
        provider: String,
        model: String,
    ): TokenStatIdentityEntity?

    @Query("SELECT * FROM token_stat_identities")
    abstract suspend fun getAllIdentities(): List<TokenStatIdentityEntity>

    /**
     * 请求接受边界原子操作（P1-1）：身份不存在时创建（INSERT IGNORE，绝不 REPLACE）、
     * 默认展示分组补齐、读取当前 generation，全部在**同一事务**内完成。展示分组删除
     * 与请求开始按 SQLite 事务串行化（写事务原子性）：删除要么看见该身份并写 IDENTITY
     * tombstone（删除前接受的事件被跳过），要么请求捕获 ≥ tombstone 的新 generation
     * （删除后请求正常入账）——首次请求的身份不再可能绕过分组删除 tombstone 复活旧事件。
     */
    @Transaction
    open suspend fun ensureIdentityAndCaptureGenerationTx(
        identity: TokenStatIdentityEntity,
        displayModel: TokenStatDisplayModelEntity,
    ): Long {
        insertIdentityIfAbsent(identity)
        insertDisplayModelIfAbsent(displayModel)
        return currentResetGeneration()
    }

    // ==== 展示模型分组 ====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDisplayModel(displayModel: TokenStatDisplayModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDisplayModels(displayModels: List<TokenStatDisplayModelEntity>)

    @Query("SELECT * FROM token_stat_display_models WHERE displayModelId = :displayModelId")
    abstract suspend fun getDisplayModel(displayModelId: String): TokenStatDisplayModelEntity?

    @Query("SELECT * FROM token_stat_display_models")
    abstract suspend fun getAllDisplayModels(): List<TokenStatDisplayModelEntity>

    // ==== 展示分组受控写入（阶段 4 别名/合并） ====
    // 身份绝不可走删除式 REPLACE（级联删事件），只走安全 UPDATE；
    // 展示模型行是纯展示元数据，创建用 INSERT IGNORE，改名用 UPDATE。

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertDisplayModelIfAbsent(model: TokenStatDisplayModelEntity): Long

    @Query(
        "UPDATE token_stat_display_models SET displayName = :displayName " +
            "WHERE displayModelId = :displayModelId"
    )
    abstract suspend fun updateDisplayModelName(displayModelId: String, displayName: String): Int

    /** 展示分组行不存在时创建（displayModelId 同时作为规范化模型名），已存在则忽略。 */
    private suspend fun ensureDisplayModelRow(displayModelId: String, displayName: String? = null) {
        if (getDisplayModel(displayModelId) == null) {
            insertDisplayModelIfAbsent(
                TokenStatDisplayModelEntity(
                    displayModelId = displayModelId,
                    normalizedModel = displayModelId,
                    displayName = displayName ?: displayModelId,
                )
            )
        }
    }

    /**
     * 把一组身份安全移动到目标展示分组（P4 别名/合并）：
     * 目标分组行不存在时先创建；身份只走 [updateIdentityDisplayModel] 的
     * 安全 UPDATE，绝不 REPLACE（REPLACE = DELETE + INSERT，会经外键级联
     * 删除该身份下的全部事件）。同一事务内完成，避免半移状态。
     */
    @Transaction
    open suspend fun moveIdentitiesToDisplayModelTx(
        identityIds: List<String>,
        displayModelId: String,
    ) {
        require(displayModelId.isNotBlank()) { "displayModelId must not be blank" }
        ensureDisplayModelRow(displayModelId)
        for (identityId in identityIds.distinct()) {
            updateIdentityDisplayModel(identityId, displayModelId)
        }
    }

    /**
     * 创建自定义展示分组（新 displayModelId + 展示名）并把指定身份移入，
     * 同一事务内完成；[groupId] 必须不与既有分组冲突。
     */
    @Transaction
    open suspend fun createDisplayGroupTx(
        groupId: String,
        groupName: String,
        identityIds: List<String>,
    ) {
        require(groupId.isNotBlank()) { "groupId must not be blank" }
        require(groupName.isNotBlank()) { "groupName must not be blank" }
        require(getDisplayModel(groupId) == null) { "display model already exists: $groupId" }
        insertDisplayModelIfAbsent(
            TokenStatDisplayModelEntity(
                displayModelId = groupId,
                normalizedModel = groupId,
                displayName = groupName.trim(),
            )
        )
        for (identityId in identityIds.distinct()) {
            updateIdentityDisplayModel(identityId, groupId)
        }
    }

    /**
     * 恢复默认规范分组：把指定展示组下每个身份按其自身模型名归回默认组
     * （displayModelId = 规范化模型名，[TokenStatIdentityResolver.displayModelIdFor]），
     * 默认组行不存在时自动创建。同一事务内完成；事件/baseline 随身份跟随，
     * 无任何删除或 REPLACE。
     */
    @Transaction
    open suspend fun restoreDefaultGroupsTx(displayModelId: String) {
        val identities = getAllIdentities().filter { it.displayModelId == displayModelId }
        for (identity in identities) {
            val defaultId = TokenStatIdentityResolver.displayModelIdFor(identity.model)
            ensureDisplayModelRow(defaultId, displayName = identity.model)
            updateIdentityDisplayModel(identity.identityId, defaultId)
        }
    }

    // ==== 价格覆盖 ====
    // 唯一性由 (scope, provider, model, configId) 规范化业务字段的唯一索引强制；
    // rowId 是内部自增主键，不承载业务语义。公开写入唯一入口会校验 scope 枚举
    // 并规范化字段（TokenStatPriceOverrideEntity.normalized），非法输入抛
    // IllegalArgumentException；底层插入不公开，防止绕过规范化。

    /**
     * 价格覆盖唯一写入入口：校验 scope 固定枚举、规范化 provider/model/configId
     * 后落库；规范化后相同业务组合在数据库唯一索引上冲突，REPLACE 后写覆盖。
     * @throws IllegalArgumentException scope 非固定枚举名或 provider/model 空白。
     */
    suspend fun upsertPriceOverride(
        scope: String,
        provider: String,
        model: String,
        configId: String?,
        billingMode: String,
        pricingCurrency: String,
        inputPricePerMillion: Double? = null,
        cachedInputPricePerMillion: Double? = null,
        cacheWritePricePerMillion: Double? = null,
        outputPricePerMillion: Double? = null,
        pricePerRequest: Double? = null,
    ) {
        insertPriceOverride(
            TokenStatPriceOverrideEntity.normalized(
                scope = scope,
                provider = provider,
                model = model,
                configId = configId,
                billingMode = billingMode,
                pricingCurrency = pricingCurrency,
                inputPricePerMillion = inputPricePerMillion,
                cachedInputPricePerMillion = cachedInputPricePerMillion,
                cacheWritePricePerMillion = cacheWritePricePerMillion,
                outputPricePerMillion = outputPricePerMillion,
                pricePerRequest = pricePerRequest,
            )
        )
    }

    /** 规范化后的实际落库（受保护：唯一入口是 [upsertPriceOverride]）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPriceOverride(override: TokenStatPriceOverrideEntity)

    @Query(
        "SELECT * FROM token_stat_price_overrides " +
            "WHERE scope = :scope AND provider = :provider " +
            "AND model = :model AND configId = :configId LIMIT 1"
    )
    abstract suspend fun getPriceOverride(
        scope: String,
        provider: String,
        model: String,
        configId: String,
    ): TokenStatPriceOverrideEntity?

    @Query("SELECT * FROM token_stat_price_overrides")
    abstract suspend fun getAllPriceOverrides(): List<TokenStatPriceOverrideEntity>

    /** 按规范化业务组合删除价格覆盖（阶段 4 管理入口；参数须为规范化后的值）。 */
    @Query(
        "DELETE FROM token_stat_price_overrides " +
            "WHERE scope = :scope AND provider = :provider " +
            "AND model = :model AND configId = :configId"
    )
    abstract suspend fun deletePriceOverride(
        scope: String,
        provider: String,
        model: String,
        configId: String,
    ): Int

    // ==== baseline ====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBaseline(baseline: TokenStatBaselineEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBaselines(baselines: List<TokenStatBaselineEntity>)

    @Query("SELECT * FROM token_stat_baselines WHERE identityId = :identityId")
    abstract suspend fun getBaseline(identityId: String): TokenStatBaselineEntity?

    @Query("SELECT * FROM token_stat_baselines")
    abstract suspend fun getAllBaselines(): List<TokenStatBaselineEntity>

    @Query("SELECT COUNT(*) FROM token_stat_baselines")
    abstract suspend fun countBaselines(): Int

    @Query("DELETE FROM token_stat_baselines WHERE identityId = :identityId")
    abstract suspend fun deleteBaseline(identityId: String): Int

    @Query(
        "DELETE FROM token_stat_baselines WHERE identityId IN " +
            "(SELECT identityId FROM token_stat_identities " +
            "WHERE provider = :provider AND model = :model)"
    )
    abstract suspend fun deleteBaselinesByProviderModel(provider: String, model: String): Int

    @Query("DELETE FROM token_stat_baselines")
    abstract suspend fun deleteAllBaselines(): Int

    // ==== 恢复 generation（受控补导幂等锚点） ====

    @Query(
        "SELECT COUNT(*) FROM token_stat_restore_generations " +
            "WHERE generation = :generation"
    )
    abstract suspend fun restoreGenerationExists(generation: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertRestoreGeneration(generation: TokenStatRestoreGenerationEntity): Long

    // ==== legacy cleanup outbox（阶段 5 P1 闭环） ====
    // 跨存储删除的线性化点：operation/items 与 tombstone/删除在**同一 Room 事务**
    // 提交（见 deleteDisplayModelEventsTx / resetModelTx / deleteAllStatisticsTx）。
    // drain 顺序固定：Room 读 PENDING → DataStore apply（marker 幂等）→ Room ACK；
    // 失败保持 PENDING 下次重试。历史行不删除（导入 fence 与备份 lineage）。

    @Query(
        "SELECT * FROM token_stat_cleanup_operations " +
            "WHERE status = 'PENDING' ORDER BY createdAtMs ASC, operationId ASC"
    )
    abstract suspend fun getPendingCleanupOperations(): List<TokenStatCleanupOperationEntity>

    @Query(
        "SELECT * FROM token_stat_cleanup_operations " +
            "ORDER BY createdAtMs ASC, operationId ASC"
    )
    abstract suspend fun getAllCleanupOperations(): List<TokenStatCleanupOperationEntity>

    @Query("SELECT * FROM token_stat_cleanup_items WHERE operationId = :operationId")
    abstract suspend fun getCleanupItems(operationId: String): List<TokenStatCleanupItemEntity>

    @Query("SELECT COUNT(*) FROM token_stat_cleanup_operations WHERE status = 'PENDING'")
    abstract suspend fun countPendingCleanupOperations(): Int

    /**
     * drain ACK：只把仍为 PENDING 的 operation 标记 APPLIED（@return 0 = 已由
     * 其他排空完成，幂等安全）。
     */
    @Query(
        "UPDATE token_stat_cleanup_operations SET status = 'APPLIED' " +
            "WHERE operationId = :operationId AND status = 'PENDING'"
    )
    abstract suspend fun ackCleanupOperation(operationId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCleanupOperation(
        operation: TokenStatCleanupOperationEntity
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCleanupItems(
        items: List<TokenStatCleanupItemEntity>
    ): List<Long>

    /**
     * 在删除事务内创建 PENDING cleanup operation（+ 不可变 items 快照）。
     * 只供本类事务方法调用；items 为空表示 ALL kind 或无需逐项清理。
     */
    protected suspend fun createCleanupOperation(
        scope: String,
        targetRef: String,
        deleteBaselines: Boolean,
        items: List<Triple<String, String, String>>,
    ): TokenStatCleanupOperationEntity {
        val operation =
            TokenStatCleanupOperationEntity(
                operationId = UUID.randomUUID().toString(),
                scope = scope,
                targetRef = targetRef,
                deleteBaselines = deleteBaselines,
                status = TokenStatCleanupOperationEntity.STATUS_PENDING,
                createdAtMs = System.currentTimeMillis(),
            )
        insertCleanupOperation(operation)
        if (items.isNotEmpty()) {
            insertCleanupItems(
                items.map { (identityId, provider, model) ->
                    TokenStatCleanupItemEntity(
                        operationId = operation.operationId,
                        identityId = identityId,
                        provider = provider,
                        model = model,
                    )
                }
            )
        }
        return operation
    }

    /**
     * 导入 fence（P1 闭环）：当前快照（含同时读取的 applied marker ID 集合）能否
     * 安全用于 baseline 导入。返回 true 当且仅当：
     * 1. 不存在 PENDING cleanup operation（未排空的清理不得被导入覆盖）；
     * 2. Room 中**全部** cleanup operation ID 都包含在该快照的 marker 集合里——
     *    否则快照早于某次 legacy cleanup 完成，直接应用会复活已删除的 baseline。
     * 必须在 Room 事务内调用（与快照读取后的写入线性化）。
     */
    @Transaction
    open suspend fun cleanupFenceSatisfied(markerOperationIds: Set<String>): Boolean {
        if (countPendingCleanupOperations() > 0) return false
        return getAllCleanupOperations().all { it.operationId in markerOperationIds }
    }

    // ==== 重置 tombstone（reset cutoff） ====
    // reset 与 spool 排空的一致同步边界：tombstone 与删除在同一事务提交，
    // 排空插入在同一事务内检查，SQLite 事务串行化杜绝并发复活（P1-3）。
    // 阶段 5 扩展：IDENTITY kind（按展示分组删除，精确到身份）与
    // token_stat_range_cutoffs 表（时间范围删除）共用同一 generation 计数器，
    // 删除后新接受的事件（acceptedGeneration ≥ cutoff）永不误伤。

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertResetCutoff(cutoff: TokenStatResetCutoffEntity)

    @Query(
        "SELECT * FROM token_stat_reset_cutoffs " +
            "WHERE kind = 'FULL' LIMIT 1"
    )
    abstract suspend fun fullResetCutoff(): TokenStatResetCutoffEntity?

    @Query("SELECT * FROM token_stat_reset_cutoffs WHERE kind = 'MODEL'")
    abstract suspend fun modelResetCutoffs(): List<TokenStatResetCutoffEntity>

    /** IDENTITY tombstone（阶段 5 展示分组删除）：provider 空串、model 列 = identityId。 */
    @Query(
        "SELECT * FROM token_stat_reset_cutoffs " +
            "WHERE kind = 'IDENTITY' AND provider = '' AND model = :identityId LIMIT 1"
    )
    protected abstract suspend fun identityResetCutoff(identityId: String): TokenStatResetCutoffEntity?

    @Query("DELETE FROM token_stat_reset_cutoffs WHERE kind = 'MODEL'")
    protected abstract suspend fun deleteModelResetCutoffs()

    @Query("DELETE FROM token_stat_reset_cutoffs WHERE kind = 'IDENTITY'")
    protected abstract suspend fun deleteIdentityResetCutoffs()

    /**
     * 统一 generation 计数器：跨 reset_cutoffs 与 range_cutoffs 两表取最大值。
     * 阶段 5 必须统一：范围删除 tombstone 与 reset tombstone 共用单调序列，
     * 否则“删除当前范围”后新请求捕获的 acceptedGeneration 可能低于范围 tombstone，
     * 导致新事件被误判为删除前事件而跳过入账。
     */
    @Query(
        "SELECT COALESCE(MAX(generation), 0) FROM (" +
            "SELECT generation FROM token_stat_reset_cutoffs " +
            "UNION ALL " +
            "SELECT generation FROM token_stat_range_cutoffs" +
            ")"
    )
    abstract suspend fun currentResetGeneration(): Long

    // ==== 阶段 5：时间范围删除 tombstone ====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertRangeCutoff(cutoff: TokenStatRangeCutoffEntity)

    @Query("SELECT * FROM token_stat_range_cutoffs")
    abstract suspend fun rangeCutoffs(): List<TokenStatRangeCutoffEntity>

    /**
     * RANGE tombstone 覆盖检查（排空插入用）：是否存在 generation **大于**事件
     * 接受 generation 且半开区间 [startMs, endMs) 包含事件 startedAtMs 的
     * tombstone。单条 EXISTS 短路查询，避免把 range cutoffs 全表 materialize
     * 后在 JVM 侧遍历（行数 = 用户范围删除次数）。
     */
    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM token_stat_range_cutoffs " +
            "WHERE generation > :acceptedGeneration " +
            "AND startMs <= :startedAtMs AND endMs > :startedAtMs" +
            ")"
    )
    protected abstract suspend fun rangeCutoffCoversEvent(
        acceptedGeneration: Long,
        startedAtMs: Long,
    ): Boolean

    @Query("DELETE FROM token_stat_range_cutoffs")
    protected abstract suspend fun deleteAllRangeCutoffs()

    /**
     * 删除时间范围 [startMs, endMs) 内的事件：写入 RANGE tombstone 并与删除
     * 同一事务提交。**绝不触碰 baseline**（baseline 无时间分布，只有按模型/全部
     * 删除且用户确认后才删除）。身份/展示分组/价格覆盖一律保留。
     * @return 删除的事件数。
     */
    @Transaction
    open suspend fun deleteRangeEventsTx(startMs: Long, endMs: Long): Int {
        require(endMs > startMs) { "range end must be after start" }
        val generation = Math.addExact(currentResetGeneration(), 1L)
        upsertRangeCutoff(
            TokenStatRangeCutoffEntity(
                generation = generation,
                startMs = startMs,
                endMs = endMs,
            )
        )
        return deleteEventsInRange(startMs, endMs)
    }

    /**
     * 按展示分组删除（阶段 5 + P1 闭环）：事务内从 **identity 全表**解析组成员（不依赖
     * 任何统计筛选），为该组全部成员写 IDENTITY tombstone（精确到身份，同一
     * provider:model 的其他分组不受影响），再按成员删除事件；[deleteBaselines]
     * 为 true 时同步删除这些成员的 baseline，并**在同一事务内**为其中 configId
     * 为空串的 legacy 成员持久化 cleanup operation + items（不可变快照，供
     * DataStore 累计键排空；为 false 时 baseline 与旧键一律保留、不建 operation）。
     * 身份行/展示分组/价格覆盖不删除（保持“只清计数、保留配置”语义）。
     * 任何读取失败都会让整个事务回滚（不产生 tombstone/operation 半状态）。
     */
    @Transaction
    open suspend fun deleteDisplayModelEventsTx(
        displayModelId: String,
        deleteBaselines: Boolean,
    ): TokenStatDisplayGroupDeletionResult {
        require(displayModelId.isNotBlank()) { "displayModelId must not be blank" }
        val members = getAllIdentities().filter { it.displayModelId == displayModelId }
        if (members.isEmpty()) return TokenStatDisplayGroupDeletionResult(0, null)
        val memberIds = members.map { it.identityId }
        val generation = Math.addExact(currentResetGeneration(), 1L)
        for (identityId in memberIds) {
            upsertResetCutoff(
                TokenStatResetCutoffEntity(
                    kind = TokenStatResetCutoffEntity.KIND_IDENTITY,
                    provider = "",
                    model = identityId,
                    generation = generation,
                )
            )
        }
        val deleted = deleteEventsByIdentities(memberIds)
        var operation: TokenStatCleanupOperationEntity? = null
        if (deleteBaselines) {
            deleteBaselinesByIdentities(memberIds)
            val legacyMembers = members.filter { it.configId == "" }
            if (legacyMembers.isNotEmpty()) {
                operation =
                    createCleanupOperation(
                        scope = TokenStatCleanupOperationEntity.SCOPE_DISPLAY_GROUP,
                        targetRef = displayModelId,
                        deleteBaselines = true,
                        items =
                            legacyMembers.map {
                                Triple(it.identityId, it.provider, it.model)
                            },
                    )
            }
        }
        return TokenStatDisplayGroupDeletionResult(deleted, operation)
    }

    /**
     * 全部删除（阶段 5 + P1 闭环）：写入 FULL tombstone 并与删除同一事务提交；
     * [deleteBaselines] 为 true 时同时清空全部 baseline，并创建 ALL kind 的
     * cleanup operation（无 items，排空时清除全部旧累计键——不触碰价格等配置）；
     * 为 false 时 baseline 与旧累计键一律保留、不建 operation。
     * 顺带清理 MODEL/IDENTITY/RANGE tombstone（与既有
     * [resetAllStatisticsTx] 的卫生语义一致：FULL 覆盖所有更早的删除边界，
     * 事件只按 FULL generation 判断，旧边界不再需要）。
     */
    @Transaction
    open suspend fun deleteAllStatisticsTx(
        deleteBaselines: Boolean,
    ): TokenStatAllDeletionResult {
        val generation = Math.addExact(currentResetGeneration(), 1L)
        upsertResetCutoff(
            TokenStatResetCutoffEntity(
                kind = TokenStatResetCutoffEntity.KIND_FULL,
                provider = "",
                model = "",
                generation = generation,
            )
        )
        deleteModelResetCutoffs()
        deleteIdentityResetCutoffs()
        deleteAllRangeCutoffs()
        val deleted = deleteAllEvents()
        var operation: TokenStatCleanupOperationEntity? = null
        if (deleteBaselines) {
            deleteAllBaselines()
            operation =
                createCleanupOperation(
                    scope = TokenStatCleanupOperationEntity.SCOPE_ALL,
                    targetRef = "",
                    deleteBaselines = true,
                    items = emptyList(),
                )
        }
        return TokenStatAllDeletionResult(deleted, operation)
    }

    /**
     * 全量重置（既有语义，阶段 1 确认）：事件 + 全部 baseline 一并删除，并创建
     * ALL kind 的 legacy cleanup operation（P1 闭环：跨存储窗口统一走 outbox）。
     * 阶段 5 保留为旧重置流程的别名；新删除流程直接使用
     * [deleteAllStatisticsTx]（可单独选择是否删除 baseline）。
     * @return 创建的 cleanup operation（null 表示无需清理——本路径恒删除 baseline）。
     */
    @Transaction
    open suspend fun resetAllStatisticsTx(): TokenStatCleanupOperationEntity? =
        deleteAllStatisticsTx(deleteBaselines = true).cleanupOperation

    /**
     * 按模型重置（P1 闭环）：写入 MODEL tombstone（每 provider/model REPLACE 覆盖，
     * 取最近时刻）并与删除同一事务提交；覆盖该模型下所有配置实例身份，并为其中
     * configId 为空串的 legacy 成员持久化 cleanup operation + items（精确到成员，
     * 不误清其他模型/配置身份对应的旧键）。
     * @return 创建的 cleanup operation（null = 无 legacy 成员，无需清旧键）。
     */
    @Transaction
    open suspend fun resetModelTx(provider: String, model: String): TokenStatCleanupOperationEntity? {
        val generation = Math.addExact(currentResetGeneration(), 1L)
        upsertResetCutoff(
            TokenStatResetCutoffEntity(
                kind = TokenStatResetCutoffEntity.KIND_MODEL,
                provider = provider,
                model = model,
                generation = generation,
            )
        )
        deleteEventsByProviderModel(provider, model)
        deleteBaselinesByProviderModel(provider, model)
        // 与 delete*ByProviderModel 相同（非规范化）匹配口径：只登记实际被删的 legacy 成员
        val legacyMembers =
            getAllIdentities().filter {
                it.configId == "" && it.provider == provider && it.model == model
            }
        return if (legacyMembers.isEmpty()) {
            null
        } else {
            createCleanupOperation(
                scope = TokenStatCleanupOperationEntity.SCOPE_MODEL,
                targetRef = "$provider:$model",
                deleteBaselines = true,
                items = legacyMembers.map { Triple(it.identityId, it.provider, it.model) },
            )
        }
    }

    /**
     * 排空路径的事件插入入口：tombstone 检查与插入在同一事务内。
     * @return false = 事件被 reset/删除 tombstone 覆盖（跳过；调用方视为已处理，
     *   段可删除，不重放）；true = 已插入。
     *
     * 阶段 5 检查链（与删除矩阵一一对应）：
     * - FULL：全量删除/重置后不接受任何更早接受的事件；
     * - IDENTITY：按展示分组删除后不接受该身份更早接受的事件（精确到身份）；
     * - MODEL：按 provider:model 重置后不接受该模型更早接受的事件；
     * - RANGE：范围删除后不接受 startedAtMs 落在已删范围且更早接受的事件
     *   （单条 EXISTS 短路，不整表 materialize range cutoffs）。
     * 统一 generation 计数（两表 UNION）保证“接受于删除前”判断不依赖墙钟。
     */
    @Transaction
    open suspend fun insertEventIfNotResetCovered(event: TokenStatEventEntity): Boolean {
        val full = fullResetCutoff()
        if (full != null && event.acceptedGeneration < full.generation) return false
        val identity =
            getIdentity(event.statIdentityId)
                ?: error("identity missing for event ${event.eventId} (ensureIdentity must run first)")
        val identityCutoff = identityResetCutoff(event.statIdentityId)
        if (identityCutoff != null && event.acceptedGeneration < identityCutoff.generation) return false
        val models = modelResetCutoffs()
        for (cutoff in models) {
            if (event.acceptedGeneration < cutoff.generation &&
                TokenStatIdentityResolver.normalizeProvider(cutoff.provider) ==
                TokenStatIdentityResolver.normalizeProvider(identity.provider) &&
                TokenStatIdentityResolver.normalizeModelName(cutoff.model) ==
                TokenStatIdentityResolver.normalizeModelName(identity.model)
            ) {
                return false
            }
        }
        // RANGE tombstone 用单条 EXISTS 判断（半开区间 [startMs, endMs)，
        // generation > acceptedGeneration 等价于 acceptedGeneration < generation）。
        if (rangeCutoffCoversEvent(event.acceptedGeneration, event.startedAtMs)) return false
        insertEvent(event)
        return true
    }
}

/**
 * 恢复 generation 的兼容别名（供不依赖 DAO 的调用方引用表名/语义）。
 * 表 token_stat_restore_generations：generation TEXT PK、appliedAtMs INTEGER NOT NULL。
 */
object TokenStatRestoreGenerationContract {
    const val TABLE_NAME = "token_stat_restore_generations"
}

/**
 * 按展示分组删除的结果：删除的事件数与（baseline=yes 且组内存在 legacy 成员时）
 * 在同一事务内创建的 cleanup operation（否则为 null）。
 */
data class TokenStatDisplayGroupDeletionResult(
    val deletedEvents: Int,
    val cleanupOperation: TokenStatCleanupOperationEntity?,
)

/** 全部删除的结果：删除的事件数与（baseline=yes 时）ALL kind cleanup operation。 */
data class TokenStatAllDeletionResult(
    val deletedEvents: Int,
    val cleanupOperation: TokenStatCleanupOperationEntity?,
)

package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.withTransaction
import com.ai.assistance.operit.data.backup.RestoreCompletionCoordinator
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.TokenStatCleanupOperationEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 旧 DataStore 累计统计 → baseline 的导入执行器（启动时一次性、冻结价格语义）。
 *
 * 普通导入（[ensureMigrated]）：计数指纹变化时（旧系统累计 setter 增长，或用户
 * reset 后降低）用 baseline 行内**冻结价格**重估计数/成本，整体替换为快照绝对值；
 * 计数不变时普通价格 setter 不重估。普通导入**只更新快照中明确存在的模型**，
 * 快照缺失的模型保持原样（不删除——偏好文件可能暂时缺失/部分恢复；显式删除
 * 走 [TokenStatsResetCoordinator] 的用户重置路径）。受控补导（恢复驱动）：真实
 * 备份恢复流程（RawSnapshotBackupManager，经 [RestoreCompletionCoordinator]）在
 * 偏好文件恢复完成后登记 pending 标记；下次冷启动 [consumePendingRestore] 读取
 * 恢复后的偏好快照，在**同一 Room 事务**内以 forceReplace 语义整体重导（替换
 * 冻结价格），并把恢复 generation 写入幂等锚点表；相同 generation 已应用则跳过
 * （崩溃后重放安全）。只有该完整受控补导才按恢复快照处理缺失模型（删除其旧系统
 * baseline，见 [TokenBaselineMigrator]）；恢复快照**为空同样合法**——旧系统
 * 从未有统计即无 legacy baseline 可留，受控补导仍执行并删除全部 legacy baseline
 * （保留非空 configId 的 baseline）。普通启动的空快照则安全 no-op（见
 * [runImport] 的空快照守卫）。
 * 整库（databases/）与偏好（datastore/）恢复顺序由 RawSnapshot 流程保证
 * （datastore 先于 databases 覆盖，登记在全部替换成功后、recovery state 完成前）。
 *
 * P1 闭环（legacy cleanup outbox fence）：两个导入入口在读取快照**之前**先排空
 * pending cleanup operation（[TokenStatsResetCoordinator.drainPendingCleanupWith]，
 * Room 事务之外），并让快照携带**同一次读取**的 applied marker ID 集合；导入事务
 * 内经 [TokenStatsDao.cleanupFenceSatisfied] 校验（无 PENDING 且全部 operation ID
 * 都在快照 markers 中）才应用快照，否则 no-op 等待下次启动重试——杜绝“先读旧
 * 快照 → cleanup 完成 → 旧快照写回”复活已删除的 baseline。受控补导被 fence 拒绝
 * 时**不记录 generation、不消费 marker**，保证信号不丢失。
 */
object TokenBaselineImportRunner {

    private const val TAG = "TokenBaselineImport"

    /**
     * 测试注入缝：生产代码始终为 null，走 [AppDatabase.getDatabase] 的真实事务；
     * 测试注入时由测试自行提供真实 Room 数据库（JVM 驱动），验证导入语义。
     */
    internal var databaseProvider: ((Context) -> AppDatabase)? = null

    suspend fun ensureMigrated(context: Context) {
        ensureMigratedStrict(context)
    }

    /** Startup readiness entry point: false means this attempt must not be treated as ready. */
    internal suspend fun ensureMigratedStrict(context: Context): Boolean {
        try {
            return runImport(context.applicationContext, forceReplace = false)
        } catch (e: CancellationException) {
            // 取消必须向上传播，不能当作迁移失败吞掉
            throw e
        } catch (e: Exception) {
            // 迁移失败不影响主流程；下次启动会重试（指纹与事务保证幂等）。
            AppLogger.e(TAG, "旧累计统计导入失败（将在下次启动重试）", e)
            return false
        }
    }

    // ==== 恢复生命周期：pending 标记 ====

    /**
     * 冷启动消费 pending 标记：有标记才补导。无标记（含 Room-only 恢复）不动作。
     * 补导与 generation 记录在同一事务中；相同 generation 已应用则跳过。
     * 空/损坏标记由 [RestoreCompletionCoordinator.readPendingGeneration] 重新登记
     * 新 generation（不静默删除信号）。
     */
    suspend fun consumePendingRestore(context: Context) {
        consumePendingRestoreStrict(context)
    }

    /**
     * Startup readiness entry point. No marker and a fully consumed marker are ready; a retained
     * marker (fence refusal or failure) is not ready and must be retried.
     */
    internal suspend fun consumePendingRestoreStrict(context: Context): Boolean {
        val appContext = context.applicationContext
        val generation = try {
            RestoreCompletionCoordinator.readPendingGeneration(appContext) ?: return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.e(TAG, "读取受控补导标记失败（下次启动重试）", e)
            return false
        }
        try {
            val injected = databaseProvider
            val database = injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
            val dao = database.tokenStatsDao()
            // 排空必须在 Room 事务之外（DataStore I/O 不得占用数据库写锁/在事务内等 DataStore）。
            // 排空后快照才是清理后的最新状态，fence 校验才有意义。
            TokenStatsResetCoordinator.drainPendingCleanupWith(appContext, dao)
            val applied =
                if (injected != null) {
                    // 测试路径：注入真实 Room 数据库（JVM 驱动），跳过 withTransaction
                    // （该 API 需要 Android 框架 SupportSQLiteOpenHelper）。
                    consumePendingLocked(dao, appContext, generation)
                } else {
                    database.withTransaction {
                        consumePendingLocked(dao, appContext, generation)
                    }
                }
            // 只有真正应用（或幂等重放命中锚点）才消费 pending 标记；fence 拒绝
            // （未排空/旧快照）时必须保留标记，下次冷启动重试，信号不丢失。
            if (applied) {
                RestoreCompletionCoordinator.consumeMarker(appContext)
            }
            return applied
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 补导失败保留标记，下次启动重试（幂等锚点保证不重复计账）。
            AppLogger.e(TAG, "受控补导失败（保留标记，下次启动重试）", e)
            return try {
                RestoreCompletionCoordinator.readPendingGeneration(appContext) == null
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * @return true = 已应用（本次记录 generation，或幂等重放命中锚点），可消费
     *   pending 标记；false = fence 拒绝（cleanup 未排空或快照 marker 过期），
     *   保留标记下次重试。
     */
    private suspend fun consumePendingLocked(
        dao: TokenStatsDao,
        appContext: Context,
        generation: String,
    ): Boolean {
        if (dao.restoreGenerationExists(generation) > 0) {
            AppLogger.i(TAG, "受控补导已应用（generation=$generation），跳过")
            return true
        }
        // 恢复后的偏好快照 + 同一次读取的 cleanup marker 集合（P1 闭环 fence）：
        // 快照早于某次 legacy cleanup 完成（marker 缺失）时直接应用会复活已删除
        // 的 baseline，必须跳过并保留 marker 下次冷启动重试。
        val read = ApiPreferences.getInstance(appContext).legacyStatsSnapshotWithMarkers()
        if (!dao.cleanupFenceSatisfied(read.cleanupMarkerIds)) {
            AppLogger.w(
                TAG,
                "legacy cleanup outbox 未排空或快照 marker 过期，受控补导跳过（下次启动重试）"
            )
            return false
        }
        // 恢复后的偏好快照是旧累计统计的权威全量：**空快照同样合法**（旧系统
        // 从未产生统计、或备份时已清空）。因此不设非空守卫，无条件以
        // forceReplace 语义进入同一 Room 事务：planImport 对空快照仍会产出
        // removedBaselineIdentityIds（全部 configId 为空的 legacy baseline），
        // 删除它们、保留非空 configId 的 baseline，并记录 generation。
        if (!runImport(appContext, dao, read.snapshot, read.cleanupMarkerIds, forceReplace = true)) {
            return false
        }
        dao.insertRestoreGeneration(
            com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity(
                generation = generation,
                appliedAtMs = System.currentTimeMillis(),
            )
        )
        AppLogger.i(TAG, "受控补导完成（generation=$generation）")
        return true
    }

    // ==== 导入 ====

    internal suspend fun runImport(appContext: Context, forceReplace: Boolean): Boolean {
        val injected = databaseProvider
        val database = injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
        val dao = database.tokenStatsDao()
        // 普通启动守卫前先排空 pending legacy cleanup（删除后未完成的 DataStore
        // 清理），再读快照：保证读到的是清理后的最新状态；排空失败抛异常由
        // ensureMigrated 捕获，下次启动重试（不吞）。
        TokenStatsResetCoordinator.drainPendingCleanupWith(appContext, dao)
        val read = ApiPreferences.getInstance(appContext).legacyStatsSnapshotWithMarkers()
        // 普通启动守卫：空快照直接返回，不触碰数据库（取消/空源都安全，绝不删除）。
        // 注意：受控补导（consumePendingLocked）不走此入口，空快照也以
        // forceReplace 语义执行删除计划。
        if (read.snapshot.providerModels.isEmpty()) return true
        return if (injected != null) {
            runImport(appContext, dao, read.snapshot, read.cleanupMarkerIds, forceReplace)
        } else {
            database.withTransaction {
                runImport(appContext, dao, read.snapshot, read.cleanupMarkerIds, forceReplace)
            }
        }
    }

    private suspend fun runImport(
        appContext: Context,
        dao: TokenStatsDao,
        snapshot: LegacyTokenStatsSnapshot,
        cleanupMarkerIds: Set<String>,
        forceReplace: Boolean,
    ): Boolean {
        // 导入 fence（P1 闭环）：Room 侧无 PENDING cleanup 且**全部** cleanup
        // operation ID 都包含在本快照的 marker 集合中，才允许应用该快照——
        // 否则快照早于某次 legacy cleanup（或清理尚未排空），应用会复活已删除
        // 的 baseline。拒绝时 no-op，下次启动重试。
        if (!dao.cleanupFenceSatisfied(cleanupMarkerIds)) {
            AppLogger.w(
                TAG,
                "legacy cleanup 未排空或快照 marker 过期，跳过本次 baseline 导入（下次启动重试）"
            )
            return false
        }
        val existingBaselines = dao.getAllBaselines().associateBy { it.identityId }
        val existingIdentities = dao.getAllIdentities().associateBy { it.identityId }
        val existingDisplayModels = dao.getAllDisplayModels().associateBy { it.displayModelId }
        val plan =
            TokenBaselineMigrator.planImport(
                snapshot = snapshot,
                existingBaselines = existingBaselines,
                nowMs = System.currentTimeMillis(),
                forceReplace = forceReplace,
                resolveIdentity = { providerModel -> ensureIdentity(providerModel) },
                resolveDisplayModel = { providerModel ->
                    TokenBaselineMigrator.defaultDisplayModel(providerModel)
                },
                resolvePricing = { providerModel ->
                    resolvePricingFor(providerModel, snapshot)
                },
                existingIdentities = existingIdentities,
            )
        val preserved =
            TokenBaselineMigrator.preserveExistingGroups(
                plan = plan,
                existingIdentities = existingIdentities,
                existingDisplayModels = existingDisplayModels,
            )

        // 身份绝不可 REPLACE（会级联删除该身份的事件）：
        // - 新身份 → INSERT IGNORE；
        // - 已存在身份 → 只做安全 UPDATE（分组展示列），不改 identityId。
        val newIdentities = preserved.identities.filter { it.identityId !in existingIdentities }
        if (newIdentities.isNotEmpty()) {
            dao.insertIdentitiesIfAbsent(newIdentities)
        }
        preserved.identities
            .filter { it.identityId in existingIdentities }
            .filter { it.displayModelId != existingIdentities.getValue(it.identityId).displayModelId }
            .forEach { dao.updateIdentityDisplayModel(it.identityId, it.displayModelId) }

        if (preserved.displayModels.isNotEmpty()) {
            dao.upsertDisplayModels(preserved.displayModels)
        }
        if (preserved.baselines.isNotEmpty()) {
            dao.upsertBaselines(preserved.baselines)
        }
        // 快照中消失的模型：仅受控补导（forceReplace）时删除其旧系统 baseline
        //（恢复快照是权威全量；普通导入绝不删除——缺失可能只是偏好文件暂缺）。
        if (preserved.removedBaselineIdentityIds.isNotEmpty()) {
            preserved.removedBaselineIdentityIds.forEach { dao.deleteBaseline(it) }
        }
        if (preserved.skippedProviderModels.isNotEmpty()) {
            AppLogger.w(
                TAG,
                "跳过无法映射到模型身份的旧统计键: ${preserved.skippedProviderModels}"
            )
        }
        AppLogger.i(
            TAG,
            "旧累计统计导入完成: 导入 ${preserved.baselines.size} 个 baseline, " +
                "跳过 ${preserved.skippedProviderModels.size} 个无模型键"
        )
        return true
    }

    private fun ensureIdentity(providerModel: String): TokenStatIdentityEntity {
        val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
        return TokenStatIdentityEntity(
            identityId = TokenStatIdentityResolver.identityId("", provider, model),
            configId = "",
            provider = provider,
            model = model,
            displayModelId = TokenStatIdentityResolver.displayModelIdFor(model),
        )
    }

    /**
     * baseline 估算只使用旧配置链（旧 DataStore 价格 → 内置默认价），
     * 不读取新系统当前价格覆盖：保证已迁移快照不受用户后续改价影响（见
     * [TokenBaselineMigrator] 冻结语义）。
     */
    private fun resolvePricingFor(
        providerModel: String,
        snapshot: LegacyTokenStatsSnapshot,
    ): ResolvedPricing {
        val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
        return TokenPriceResolver.resolve(
            provider = provider,
            model = model,
            configId = null,
            overrides = emptyList(),
            legacyOverride = snapshot.providerModels[providerModel]?.priceSettings,
            defaults = DefaultModelPricingCollect.getDefaultPricing(providerModel),
            // Lifetime totals cannot identify the context length of individual requests.
            contextInputTokens = null,
            selectContextTier = true,
        )
    }
}

/**
 * 统计重置接线：把新账本（事件 + baseline）接入仓库现有“全量重置/按模型重置”机制。
 *
 * - 全量重置：递增 durable FULL generation 并在同一 Room 事务内无条件删除事件
 *   与全部 baseline（不删除身份、展示分组与价格覆盖，
 *   与旧系统“重置只清计数、保留配置”语义一致）。
 * - 按模型重置：写 MODEL generation tombstone（每 provider/model REPLACE）
 *   并在同一事务内删除该 provider/model 下**所有配置实例**身份的事件与 baseline；
 *   旧 DataStore 无配置实例区分，其 baseline 身份的 configId 为空串，同样被覆盖。
 * - spool 一致性（P1-3）：排空插入在同一 Room 事务内检查 tombstone
 *   （[TokenStatsDao.insertEventIfNotResetCovered]），并发中已接受但未入 Room 的
 *   事件不会复活；重置后触发 [TokenStatSpool.replay] 让排空丢弃被覆盖的行。
 *
 * 阶段 5 删除入口（与旧重置共用同一 spool 一致性机制）：
 * - [deleteEventsInRange]：只删时间范围内的事件（RANGE tombstone），绝不碰 baseline；
 * - [deleteDisplayModel]：按**完整展示分组**删除事件，可单独选择是否删除该组
 *   baseline（IDENTITY tombstone 精确到身份，不误伤同 provider:model 的其他分组）；
 * - [deleteAllEvents]：删除全部事件，可单独选择是否删除全部 baseline
 *   （FULL tombstone）。
 *
 * 跨存储 legacy cleanup（P1 闭环）：删除事务是唯一线性化点——删除 baseline 的
 * 入口（[deleteDisplayModel]/[deleteAllEvents]/[resetAllStatistics]/
 * [resetStatisticsForProviderModel]）在同一事务内为 configId 为空的 legacy 成员
 * 持久化 PENDING cleanup operation/items；事务提交后在 **Room 事务之外**调用
 * [drainPendingCleanup]：Room 读 PENDING → DataStore 单次 edit 精准清键并写
 * marker → Room ACK APPLIED。DataStore 失败抛异常、operation 保持 PENDING，
 * 由冷启动（baseline 导入之前 / pending restore 之前）与下次删除入口重试。
 *
 * [daoProvider] 为测试注入缝：生产代码始终为 null，走 [AppDatabase] 的真实事务；
 * 测试注入时由测试自行验证调用语义（生产原子性由 DAO @Transaction 保证）。
 */
object TokenStatsResetCoordinator {

    internal var daoProvider: ((Context) -> TokenStatsDao)? = null

    private const val TAG = "TokenStatsReset"
    private val cleanupSnapshotMutex = Mutex()

    /**
     * Cross-store snapshot barrier for the cleanup outbox. A reset/baseline deletion performs
     * Room mutation -> DataStore marker apply -> Room APPLIED acknowledgement; raw snapshot export
     * must not archive DataStore and Room from different points inside that sequence.
     */
    internal suspend fun <T> withCleanupSnapshotAccess(block: suspend () -> T): T =
        cleanupSnapshotMutex.withLock { block() }

    suspend fun resetAllStatistics(context: Context) {
        withCleanupSnapshotAccess {
            withDao(context) { dao -> dao.resetAllStatisticsTx() }
            drainPendingCleanupUnlocked(context.applicationContext)
        }
        TokenStatSpool.replay(context.applicationContext)
    }

    suspend fun resetStatisticsForProviderModel(context: Context, providerModel: String) {
        val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
        if (model.isBlank()) return
        withCleanupSnapshotAccess {
            withDao(context) { dao -> dao.resetModelTx(provider, model) }
            drainPendingCleanupUnlocked(context.applicationContext)
        }
        TokenStatSpool.replay(context.applicationContext)
    }

    /** 删除时间范围 [startMs, endMs) 内的事件；baseline 一律保留（阶段 5）。 */
    suspend fun deleteEventsInRange(context: Context, startMs: Long, endMs: Long) {
        withDao(context) { dao -> dao.deleteRangeEventsTx(startMs, endMs) }
        TokenStatSpool.replay(context.applicationContext)
    }

    /**
     * 按展示分组删除（阶段 5）：组成员在 DAO 事务内从 identity 全表解析；
     * [deleteBaselines] 为 true 时同时删除该组成员的 baseline，并在同一事务内
     * 为其中 legacy 成员持久化 cleanup operation（事务外立即排空 DataStore）。
     */
    suspend fun deleteDisplayModel(
        context: Context,
        displayModelId: String,
        deleteBaselines: Boolean,
    ) {
        withCleanupSnapshotAccess {
            withDao(context) { dao -> dao.deleteDisplayModelEventsTx(displayModelId, deleteBaselines) }
            drainPendingCleanupUnlocked(context.applicationContext)
        }
        TokenStatSpool.replay(context.applicationContext)
    }

    /** 删除全部事件；[deleteBaselines] 为 true 时同时删除全部 baseline（阶段 5）。 */
    suspend fun deleteAllEvents(context: Context, deleteBaselines: Boolean) {
        withCleanupSnapshotAccess {
            withDao(context) { dao -> dao.deleteAllStatisticsTx(deleteBaselines) }
            drainPendingCleanupUnlocked(context.applicationContext)
        }
        TokenStatSpool.replay(context.applicationContext)
    }

    /**
     * 排空 pending legacy cleanup（P1 闭环）：固定顺序
     * **Room 读 PENDING operations/items → DataStore apply（marker 幂等）→
     * Room ACK APPLIED**，单 operation 依次完成。
     * - DataStore apply 失败抛异常且 operation 保持 PENDING（下次启动/下次删除
     *   重试；marker 已在时重放为幂等 no-op）；
     * - 协程取消（[CancellationException]）向上传播，不吞；
     * - 不占用任何 Room 事务等待 DataStore（本函数在删除事务之外调用）。
     */
    suspend fun drainPendingCleanup(context: Context) {
        withCleanupSnapshotAccess {
            drainPendingCleanupUnlocked(context)
        }
    }

    private suspend fun drainPendingCleanupUnlocked(context: Context) {
        val appContext = context.applicationContext
        val injected = daoProvider
        val dao =
            injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext).tokenStatsDao()
        drainPendingCleanupWithLocked(appContext, dao)
    }

    /**
     * 指定 DAO 的排空（runner/coordinator 共用）：见 [drainPendingCleanup]。
     * runner 用它复用自己解析的数据库实例（测试注入路径）。
     */
    internal suspend fun drainPendingCleanupWith(appContext: Context, dao: TokenStatsDao) {
        withCleanupSnapshotAccess {
            drainPendingCleanupWithLocked(appContext, dao)
        }
    }

    private suspend fun drainPendingCleanupWithLocked(appContext: Context, dao: TokenStatsDao) {
        val pending = dao.getPendingCleanupOperations()
        if (pending.isEmpty()) return
        val prefs = ApiPreferences.getInstance(appContext)
        for (operation in pending) {
            val providerModels =
                if (operation.scope == TokenStatCleanupOperationEntity.SCOPE_ALL) {
                    null
                } else {
                    dao.getCleanupItems(operation.operationId)
                        .map { "${it.provider}:${it.model}" }
                }
            prefs.applyLegacyCleanup(operation.operationId, providerModels)
            if (dao.ackCleanupOperation(operation.operationId) == 0) {
                AppLogger.w(TAG, "cleanup operation ${operation.operationId} 已由其他排空完成")
            }
        }
    }

    private suspend fun withDao(
        context: Context,
        block: suspend (TokenStatsDao) -> Unit,
    ) {
        val injected = daoProvider
        if (injected != null) {
            block(injected(context))
            return
        }
        val database = AppDatabase.getDatabase(context.applicationContext)
        database.withTransaction { block(database.tokenStatsDao()) }
    }
}

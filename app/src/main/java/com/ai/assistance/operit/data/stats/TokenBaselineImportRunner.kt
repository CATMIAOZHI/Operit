package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.withTransaction
import com.ai.assistance.operit.data.backup.RestoreCompletionCoordinator
import com.ai.assistance.operit.data.collects.DefaultModelPricingCollect
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.preferences.ApiPreferences
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.CancellationException

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
 */
object TokenBaselineImportRunner {

    private const val TAG = "TokenBaselineImport"

    /**
     * 测试注入缝：生产代码始终为 null，走 [AppDatabase.getDatabase] 的真实事务；
     * 测试注入时由测试自行提供真实 Room 数据库（JVM 驱动），验证导入语义。
     */
    internal var databaseProvider: ((Context) -> AppDatabase)? = null

    suspend fun ensureMigrated(context: Context) {
        try {
            runImport(context.applicationContext, forceReplace = false)
        } catch (e: CancellationException) {
            // 取消必须向上传播，不能当作迁移失败吞掉
            throw e
        } catch (e: Exception) {
            // 迁移失败不影响主流程；下次启动会重试（指纹与事务保证幂等）。
            AppLogger.e(TAG, "旧累计统计导入失败（将在下次启动重试）", e)
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
        val appContext = context.applicationContext
        val generation =
            RestoreCompletionCoordinator.readPendingGeneration(appContext) ?: return
        try {
            val injected = databaseProvider
            val database = injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
            val dao = database.tokenStatsDao()
            if (injected != null) {
                // 测试路径：注入真实 Room 数据库（JVM 驱动），跳过 withTransaction
                // （该 API 需要 Android 框架 SupportSQLiteOpenHelper）。
                consumePendingLocked(dao, appContext, generation)
            } else {
                database.withTransaction {
                    consumePendingLocked(dao, appContext, generation)
                }
            }
            RestoreCompletionCoordinator.consumeMarker(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 补导失败保留标记，下次启动重试（幂等锚点保证不重复计账）。
            AppLogger.e(TAG, "受控补导失败（保留标记，下次启动重试）", e)
        }
    }

    private suspend fun consumePendingLocked(
        dao: TokenStatsDao,
        appContext: Context,
        generation: String,
    ) {
        if (dao.restoreGenerationExists(generation) > 0) {
            AppLogger.i(TAG, "受控补导已应用（generation=$generation），跳过")
            return
        }
        val snapshot = ApiPreferences.getInstance(appContext).legacyStatsSnapshot()
        // 恢复后的偏好快照是旧累计统计的权威全量：**空快照同样合法**（旧系统
        // 从未产生统计、或备份时已清空）。因此不设非空守卫，无条件以
        // forceReplace 语义进入同一 Room 事务：planImport 对空快照仍会产出
        // removedBaselineIdentityIds（全部 configId 为空的 legacy baseline），
        // 删除它们、保留非空 configId 的 baseline，并记录 generation。
        runImport(appContext, dao, snapshot, forceReplace = true)
        dao.insertRestoreGeneration(
            com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity(
                generation = generation,
                appliedAtMs = System.currentTimeMillis(),
            )
        )
        AppLogger.i(TAG, "受控补导完成（generation=$generation）")
    }

    // ==== 导入 ====

    internal suspend fun runImport(appContext: Context, forceReplace: Boolean) {
        // 普通启动守卫：空快照直接返回，不触碰数据库（取消/空源都安全，绝不删除）。
        // 注意：受控补导（consumePendingLocked）不走此入口，空快照也以
        // forceReplace 语义执行删除计划。
        val snapshot = ApiPreferences.getInstance(appContext).legacyStatsSnapshot()
        if (snapshot.providerModels.isEmpty()) return

        val injected = databaseProvider
        val database = injected?.invoke(appContext) ?: AppDatabase.getDatabase(appContext)
        val dao = database.tokenStatsDao()
        if (injected != null) {
            runImport(appContext, dao, snapshot, forceReplace)
        } else {
            database.withTransaction {
                runImport(appContext, dao, snapshot, forceReplace)
            }
        }
    }

    private suspend fun runImport(
        appContext: Context,
        dao: TokenStatsDao,
        snapshot: LegacyTokenStatsSnapshot,
        forceReplace: Boolean,
    ) {
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
        )
    }
}

/**
 * 统计重置接线：把新账本（事件 + baseline）接入仓库现有“全量重置/按模型重置”机制。
 *
 * - 全量重置：清空全部事件与 baseline（不删除身份、展示分组与价格覆盖，
 *   与旧系统“重置只清计数、保留配置”语义一致）。
 * - 按模型重置：在单个数据库事务中删除该 provider/model 下**所有配置实例**
 *   身份的事件与 baseline；旧 DataStore 无配置实例区分，其 baseline 身份的
 *   configId 为空串，同样被覆盖。
 *
 * [daoProvider] 为测试注入缝：生产代码始终为 null，走 [AppDatabase] 的真实事务；
 * 测试注入时由测试自行验证调用语义（生产原子性由 withTransaction 保证）。
 */
object TokenStatsResetCoordinator {

    internal var daoProvider: ((Context) -> TokenStatsDao)? = null

    suspend fun resetAllStatistics(context: Context) {
        withTransaction(context) { dao ->
            dao.deleteAllEvents()
            dao.deleteAllBaselines()
        }
    }

    suspend fun resetStatisticsForProviderModel(context: Context, providerModel: String) {
        val (provider, model) = TokenStatIdentityResolver.splitProviderModel(providerModel)
        if (model.isBlank()) return
        withTransaction(context) { dao ->
            dao.deleteEventsByProviderModel(provider, model)
            dao.deleteBaselinesByProviderModel(provider, model)
        }
    }

    private suspend fun withTransaction(
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

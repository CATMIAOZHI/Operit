package com.ai.assistance.operit.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatBaselineEntity
import com.ai.assistance.operit.data.model.TokenStatDisplayModelEntity
import com.ai.assistance.operit.data.model.TokenStatEventEntity
import com.ai.assistance.operit.data.model.TokenStatIdentityEntity
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import com.ai.assistance.operit.data.model.TokenStatRestoreGenerationEntity
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

    @Query("SELECT COUNT(*) FROM token_stat_events")
    abstract suspend fun countEvents(): Int

    @Query("SELECT * FROM token_stat_events WHERE statIdentityId = :identityId")
    abstract fun observeEventsByIdentity(identityId: String): Flow<List<TokenStatEventEntity>>

    @Query("DELETE FROM token_stat_events WHERE statIdentityId = :identityId")
    abstract suspend fun deleteEventsByIdentity(identityId: String): Int

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

    // ==== 展示模型分组 ====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDisplayModel(displayModel: TokenStatDisplayModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDisplayModels(displayModels: List<TokenStatDisplayModelEntity>)

    @Query("SELECT * FROM token_stat_display_models WHERE displayModelId = :displayModelId")
    abstract suspend fun getDisplayModel(displayModelId: String): TokenStatDisplayModelEntity?

    @Query("SELECT * FROM token_stat_display_models")
    abstract suspend fun getAllDisplayModels(): List<TokenStatDisplayModelEntity>

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
}

/**
 * 恢复 generation 的兼容别名（供不依赖 DAO 的调用方引用表名/语义）。
 * 表 token_stat_restore_generations：generation TEXT PK、appliedAtMs INTEGER NOT NULL。
 */
object TokenStatRestoreGenerationContract {
    const val TABLE_NAME = "token_stat_restore_generations"
}

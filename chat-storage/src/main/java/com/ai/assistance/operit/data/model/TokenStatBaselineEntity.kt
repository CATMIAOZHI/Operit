package com.ai.assistance.operit.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/**
 * 旧 DataStore 累计统计迁移出的 baseline（生命周期总览数据）。
 *
 * - baseline 无可靠时间分布，不进入事件表，也不进入时间趋势图。
 * - 每个统计身份至多一行；**首次迁移即冻结**：普通启动/价格 setter 永不重估
 *   baseline。只有真实备份恢复流程完成（偏好文件恢复后显式触发的受控补导，
 *   见 TokenBaselineImportRunner.markRestorePending/consumePendingRestore）
 *   才以 `forceReplace` 语义整体重导一次。
 * - [fingerprint] 只摘要**旧迁移源的累计计数**（不含价格设置），用于诊断与
 *   恢复补导的幂等核对，不再作为普通重导的判据。
 * - [frozen*] 记录本次估算所用价格快照（冻结）：恢复补导前保持原值，
 *   改价不会改写历史估算。
 * - 费用始终按迁移时可用配置估算并标记 [isEstimated]，不能伪装成精确历史账单；
 *   无可用定价时 [costInPricingCurrency] 为 null（未知，而非 0）。
 * - 展示分组不在此表重复保存：唯一事实来源是
 *   [TokenStatIdentityEntity.displayModelId]（经 identityId 外键关联）。
 */
@Entity(
    tableName = "token_stat_baselines",
    foreignKeys = [
        ForeignKey(
            entity = TokenStatIdentityEntity::class,
            parentColumns = ["identityId"],
            childColumns = ["identityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TokenStatBaselineEntity(
    @PrimaryKey @ColumnInfo(name = "identityId") val identityId: String,
    @ColumnInfo(name = "inputTokens") val inputTokens: Long,
    @ColumnInfo(name = "cachedInputTokens") val cachedInputTokens: Long,
    @ColumnInfo(name = "outputTokens") val outputTokens: Long,
    @ColumnInfo(name = "requestCount") val requestCount: Long,
    @ColumnInfo(name = "pricingCurrency") val pricingCurrency: String,
    @ColumnInfo(name = "costInPricingCurrency") val costInPricingCurrency: Double? = null,
    @ColumnInfo(name = "isEstimated") val isEstimated: Boolean = true,
    @ColumnInfo(name = "fingerprint") val fingerprint: String,
    @ColumnInfo(name = "importedAtMs") val importedAtMs: Long,
    @ColumnInfo(name = "frozenBillingMode") val frozenBillingMode: String,
    @ColumnInfo(name = "frozenInputPricePerMillion") val frozenInputPricePerMillion: Double? = null,
    @ColumnInfo(name = "frozenCachedInputPricePerMillion") val frozenCachedInputPricePerMillion: Double? = null,
    @ColumnInfo(name = "frozenOutputPricePerMillion") val frozenOutputPricePerMillion: Double? = null,
    @ColumnInfo(name = "frozenPricePerRequest") val frozenPricePerRequest: Double? = null,
)

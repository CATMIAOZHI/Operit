package com.ai.assistance.operit.data.stats

import android.content.Context
import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.preferences.ApiPreferences

/**
 * 统计页持久化偏好（阶段 4）：汇率、总计币种、费用口径与时间选择。
 *
 * - 全部键落在 ApiPreferences 的 `api_settings` DataStore 文件内，由现有
 *   整库/ApiPreferences 备份恢复逻辑整体覆盖，不新增独立存储与凭据。
 * - 汇率**只由用户手动设置**：未设置时 [loadRateWithEstimate] 返回默认估算
 *   7.0（[TokenCostCurrency.DEFAULT_USD_TO_CNY_RATE]）并标记 estimated，
 *   界面必须明显标注“默认估算”；不联网获取汇率。
 * - 时间选择持久化的是“用户是否手动选过”：null = 从未选择（允许每次进入
 *   时按 5h→…→30d 自动回退）；非 null = 用户选择，不再自动跳转。
 */
interface TokenStatsSettingsStore {

    /** 当前手动汇率 + 是否默认估算（true = 未设置，界面必须标记估算）。 */
    suspend fun loadRateWithEstimate(): Pair<Double, Boolean>

    /** 保存用户手动汇率；保存后 [loadRateWithEstimate] 的 estimated 为 false。 */
    suspend fun saveRate(rate: Double)

    suspend fun loadTargetCurrency(): PricingCurrency

    suspend fun saveTargetCurrency(currency: PricingCurrency)

    suspend fun loadCostMode(): TokenStatsCostMode

    suspend fun saveCostMode(mode: TokenStatsCostMode)

    /** 是否在生命周期累计中加入迁移的旧版 baseline；默认 true。 */
    suspend fun loadIncludeLegacy(): Boolean

    suspend fun saveIncludeLegacy(include: Boolean)

    /**
     * 当前时间选择（首次自动回退结果或用户手选）；null = 从未有任何选择，
     * 进入页面时允许执行首次自动回退并持久化。
     */
    suspend fun loadTimeSelection(): TokenStatsTimeSelection?

    /** 当前时间选择是否由用户手动做出；false = 首次自动回退（或旧数据迁移）。 */
    suspend fun loadSelectionWasManual(): Boolean

    /**
     * 保存/清除时间选择；[manual] = 用户手动选择（true）或首次自动回退
     * （false）。null 表示清除（回到首次自动回退语义）。
     */
    suspend fun saveTimeSelection(selection: TokenStatsTimeSelection?, manual: Boolean)
}

/** 用户选择的时间范围（持久化形态）：预设 + 可选自定义边界（毫秒，设备时区自然日）。 */
data class TokenStatsTimeSelection(
    val preset: TokenStatsPreset,
    val customStartMs: Long? = null,
    val customEndMs: Long? = null,
)

/** 生产实现：直接包装 [ApiPreferences]（同一 DataStore 文件，备份自动覆盖）。 */
class ApiPreferencesTokenStatsSettingsStore(context: Context) : TokenStatsSettingsStore {

    private val api = ApiPreferences.getInstance(context)

    override suspend fun loadRateWithEstimate(): Pair<Double, Boolean> =
        api.usdToCnyRateWithEstimate()

    override suspend fun saveRate(rate: Double) {
        api.setUsdToCnyExchangeRate(rate)
    }

    override suspend fun loadTargetCurrency(): PricingCurrency =
        api.getStatsTargetCurrency()

    override suspend fun saveTargetCurrency(currency: PricingCurrency) {
        api.setStatsTargetCurrency(currency)
    }

    override suspend fun loadCostMode(): TokenStatsCostMode =
        api.getStatsCostMode()

    override suspend fun saveCostMode(mode: TokenStatsCostMode) {
        api.setStatsCostMode(mode)
    }

    override suspend fun loadIncludeLegacy(): Boolean = api.getStatsIncludeLegacy()

    override suspend fun saveIncludeLegacy(include: Boolean) {
        api.setStatsIncludeLegacy(include)
    }

    override suspend fun loadTimeSelection(): TokenStatsTimeSelection? =
        api.getStatsTimeSelection()

    override suspend fun loadSelectionWasManual(): Boolean =
        api.getStatsSelectionWasManual()

    override suspend fun saveTimeSelection(selection: TokenStatsTimeSelection?, manual: Boolean) {
        api.setStatsTimeSelection(selection, manual)
    }
}

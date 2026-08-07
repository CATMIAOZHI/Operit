package com.ai.assistance.operit.data.stats

import com.ai.assistance.operit.data.collects.PricingCurrency
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.data.model.PriceOverrideScope
import com.ai.assistance.operit.data.model.TokenStatPriceOverrideEntity
import java.util.UUID

/** 价格覆盖编辑草稿（界面 ↔ 管理入口的统一输入形态）。 */
data class TokenStatsPriceOverrideDraft(
    val scope: PriceOverrideScope,
    val provider: String,
    val model: String,
    val configId: String?,
    val billingMode: BillingMode,
    val currency: PricingCurrency,
    val inputPricePerMillion: Double? = null,
    val cachedInputPricePerMillion: Double? = null,
    val cacheWritePricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    val pricePerRequest: Double? = null,
)

/**
 * 统计页设置管理（阶段 4）：价格覆盖与模型别名/分组的**受控**写入入口。
 *
 * - 价格覆盖：所有价格值必须为非负有限数（NaN/Infinity/负数直接抛
 *   [IllegalArgumentException]，不落库）；写入走 [TokenStatsDao.upsertPriceOverride]
 *   的规范化唯一入口（scope 枚举 + 规范化业务字段），删除走规范化业务组合。
 * - 分组/别名：身份只通过 [TokenStatsDao] 的安全 UPDATE 移动，绝不 REPLACE
 *   （REPLACE 会经外键级联删除事件）；展示模型行用 INSERT IGNORE / UPDATE。
 * - 本类只做校验与编排，事务边界在 DAO（@Transaction）保证。
 */
class TokenStatsSettingsManager(private val dao: TokenStatsDao) {

    companion object {
        /** 自定义分组 displayModelId 前缀（与任何规范化模型名天然不冲突）。 */
        const val CUSTOM_GROUP_ID_PREFIX = "custom-group-"
    }

    // ==== 价格覆盖 ====

    /** 校验单个价格输入：null 允许（该计费方式不使用），非空必须非负有限。 */
    fun validatePriceValue(name: String, value: Double?): Double? {
        if (value == null) return null
        require(value.isFinite() && value >= 0.0) {
            "$name must be non-negative and finite, got $value"
        }
        return value
    }

    /**
     * 新增/编辑价格覆盖（provider/model 或 API 配置作用域）。
     * 价格值非法时抛 [IllegalArgumentException]，不产生任何写入。
     */
    suspend fun upsertPriceOverride(
        scope: PriceOverrideScope,
        provider: String,
        model: String,
        configId: String?,
        billingMode: BillingMode,
        pricingCurrency: PricingCurrency,
        inputPricePerMillion: Double?,
        cachedInputPricePerMillion: Double?,
        cacheWritePricePerMillion: Double?,
        outputPricePerMillion: Double?,
        pricePerRequest: Double?,
    ) {
        dao.upsertPriceOverride(
            scope = scope.name,
            provider = provider,
            model = model,
            configId = configId,
            billingMode = billingMode.name,
            pricingCurrency = pricingCurrency.name,
            inputPricePerMillion = validatePriceValue("inputPrice", inputPricePerMillion),
            cachedInputPricePerMillion =
                validatePriceValue("cachedInputPrice", cachedInputPricePerMillion),
            cacheWritePricePerMillion =
                validatePriceValue("cacheWritePrice", cacheWritePricePerMillion),
            outputPricePerMillion = validatePriceValue("outputPrice", outputPricePerMillion),
            pricePerRequest = validatePriceValue("pricePerRequest", pricePerRequest),
        )
    }

    /** 草稿形态的统一入口（阶段 4 UI 使用）。 */
    suspend fun upsertPriceOverride(draft: TokenStatsPriceOverrideDraft) {
        upsertPriceOverride(
            scope = draft.scope,
            provider = draft.provider,
            model = draft.model,
            configId = draft.configId,
            billingMode = draft.billingMode,
            pricingCurrency = draft.currency,
            inputPricePerMillion = draft.inputPricePerMillion,
            cachedInputPricePerMillion = draft.cachedInputPricePerMillion,
            cacheWritePricePerMillion = draft.cacheWritePricePerMillion,
            outputPricePerMillion = draft.outputPricePerMillion,
            pricePerRequest = draft.pricePerRequest,
        )
    }

    /**
     * 编辑已有价格覆盖（P1-7）：业务键（scope/provider/model/configId）必须与
     * 现有行**规范化后一致**，只允许修改价格/币种/计费方式。防止 UI 之外
     * （或 UI 缺陷）改动业务键产生第二行或误覆盖其他覆盖。
     * 校验通过后按规范化值写回（REPLACE 覆盖同一业务组合，始终只有一行）。
     * @throws IllegalArgumentException 业务键不一致或价格非法。
     */
    suspend fun updatePriceOverride(
        existing: TokenStatPriceOverrideEntity,
        draft: TokenStatsPriceOverrideDraft,
    ) {
        val normalized = TokenStatPriceOverrideEntity.normalized(
            scope = draft.scope.name,
            provider = draft.provider,
            model = draft.model,
            configId = draft.configId,
            billingMode = draft.billingMode.name,
            pricingCurrency = draft.currency.name,
        )
        require(normalized.scope == existing.scope) { "price override scope must not change on edit" }
        require(normalized.provider == existing.provider) { "price override provider must not change on edit" }
        require(normalized.model == existing.model) { "price override model must not change on edit" }
        require(normalized.configId == existing.configId) { "price override configId must not change on edit" }
        upsertPriceOverride(draft)
    }

    /** 全部价格覆盖（管理区展示用；小表，一次读取）。 */
    suspend fun allPriceOverrides(): List<TokenStatPriceOverrideEntity> =
        dao.getAllPriceOverrides()

    /** 删除价格覆盖（按规范化业务组合；不存在的组合静默成功）。 */
    suspend fun deletePriceOverride(
        scope: PriceOverrideScope,
        provider: String,
        model: String,
        configId: String?,
    ) {
        dao.deletePriceOverride(
            scope = scope.name,
            // 与写入同一规范化：provider trim+小写、model trim+小写+压缩空白
            provider = provider.trim().lowercase(),
            model = model.trim().lowercase().replace(Regex("\\s+"), " "),
            configId =
                if (scope == PriceOverrideScope.PROVIDER_MODEL) {
                    ""
                } else {
                    configId?.trim().orEmpty()
                },
        )
    }

    // ==== 展示分组 / 别名 ====

    /**
     * 完整展示分组元数据（阶段 4 P1 修复）：与统计筛选（时间/模型/分类/状态）
     * 完全无关——分组管理与合并的成员/目标必须来自全量身份/展示模型表，而不是
     * 当前筛选范围所见（范围明细只反映有事件的身份/分组，会把无事件的组成员
     * 漏掉）。返回所有分组（含无身份的空组，空组仍是合法合并目标）；组名取
     * display_models 行，缺失时回退 displayModelId；输出顺序确定。
     */
    suspend fun groupModels(): List<TokenStatsGroupModelInfo> {
        val snapshot = dao.loadGroupMetadataSnapshot()
        val displayNames = snapshot.displayModels.associateBy { it.displayModelId }
        val membersByGroup = LinkedHashMap<String, MutableList<String>>()
        for (identity in snapshot.identities) {
            membersByGroup.getOrPut(identity.displayModelId) { mutableListOf() }
                .add(identity.identityId)
        }
        // 有展示行但无身份的空分组：仍可作为合并目标，必须保留
        for (display in snapshot.displayModels) {
            membersByGroup.putIfAbsent(display.displayModelId, mutableListOf())
        }
        return membersByGroup.map { (displayModelId, memberIds) ->
            TokenStatsGroupModelInfo(
                displayModelId = displayModelId,
                displayName = displayNames[displayModelId]?.displayName ?: displayModelId,
                memberIdentityIds = memberIds.sorted(),
            )
        }.sortedWith(compareBy({ it.displayName.lowercase() }, { it.displayModelId }))
    }

    /** 重命名展示分组（只改 displayName，不动身份归属）。 */
    suspend fun renameDisplayGroup(displayModelId: String, displayName: String) {
        val trimmed = displayName.trim()
        require(trimmed.isNotBlank()) { "display name must not be blank" }
        dao.updateDisplayModelName(displayModelId, trimmed)
    }

    /** 把指定身份移动到已有展示分组（分组行不存在时自动创建）。 */
    suspend fun moveIdentitiesToGroup(identityIds: List<String>, displayModelId: String) {
        require(identityIds.isNotEmpty()) { "identityIds must not be empty" }
        dao.moveIdentitiesToDisplayModelTx(identityIds, displayModelId)
    }

    /**
     * 新建自定义展示分组并把指定身份移入；返回新分组 displayModelId。
     * 分组 ID 使用随机后缀，避免与规范化模型名冲突。
     */
    suspend fun createGroupAndMove(groupName: String, identityIds: List<String>): String {
        val trimmed = groupName.trim()
        require(trimmed.isNotBlank()) { "group name must not be blank" }
        require(identityIds.isNotEmpty()) { "identityIds must not be empty" }
        val groupId = "$CUSTOM_GROUP_ID_PREFIX${UUID.randomUUID()}"
        dao.createDisplayGroupTx(groupId, trimmed, identityIds)
        return groupId
    }

    /** 恢复默认规范分组：组内每个身份按其自身模型名归回默认组。 */
    suspend fun restoreDefaultGroups(displayModelId: String) {
        dao.restoreDefaultGroupsTx(displayModelId)
    }
}

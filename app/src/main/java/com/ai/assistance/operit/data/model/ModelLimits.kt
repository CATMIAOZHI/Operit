package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * How much one model can read and write, in tokens; null means no limit is known.
 *
 * An entry in [ModelConfigData.modelLimits] also records that the model has been matched in the
 * model catalog (or set by hand), so automatic matching leaves the model's settings alone after.
 */
@Serializable
data class ModelLimits(
    val contextTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    /** The same limits with anything not positive dropped. */
    fun cleaned(): ModelLimits =
        ModelLimits(contextTokens?.takeIf { it > 0 }, outputTokens?.takeIf { it > 0 })
}

/** [model]'s limits; without a name, those of the configuration's only model. */
fun ModelConfigData.limitsFor(model: String? = null): ModelLimits? {
    val name = model?.trim()?.takeIf { it.isNotEmpty() }
        ?: getModelList(modelName).singleOrNull()
        ?: return null
    return modelLimits[name]?.cleaned()
}

/** [model]'s context window in K (1024 tokens), or null when it is not known. */
fun ModelConfigData.contextLimitK(model: String? = null): Float? =
    limitsFor(model)?.contextTokens?.let { it / 1024f }

/** The reply length to ask for: the configured one, never above what [model] can write. */
fun ModelConfigData.effectiveMaxTokens(model: String? = null): Int {
    val limit = limitsFor(model)?.outputTokens ?: return maxTokens
    return min(maxTokens, limit)
}

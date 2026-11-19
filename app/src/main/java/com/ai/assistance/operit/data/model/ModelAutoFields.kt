package com.ai.assistance.operit.data.model

/**
 * The per-model fields the model settings page edits and automatic matching fills in. The page
 * saves only what it changed itself, so matching done meanwhile (in the background, by a tool or
 * when saving) is kept.
 */
data class ModelAutoFields(
    val protocols: Map<String, ModelProtocolSettings>,
    val capabilities: Map<String, ModelMultimodalCapabilities>,
    val limits: Map<String, ModelLimits>,
    val settled: Set<String>,
) {
    /**
     * These fields with the changes made from [base] to [mine] laid over them. Inputs and limits
     * merge switch by switch and number by number, so changing one keeps a new value of another.
     */
    fun withEdits(base: ModelAutoFields, mine: ModelAutoFields) = ModelAutoFields(
        protocols = overlay(protocols, base.protocols, mine.protocols) { _, _, edited -> edited },
        capabilities = overlay(capabilities, base.capabilities, mine.capabilities) { target, was, edited ->
            val from = target ?: ModelMultimodalCapabilities()
            val before = was ?: ModelMultimodalCapabilities()
            ModelMultimodalCapabilities(
                image = if (edited.image != before.image) edited.image else from.image,
                audio = if (edited.audio != before.audio) edited.audio else from.audio,
                video = if (edited.video != before.video) edited.video else from.video,
            )
        },
        limits = overlay(limits, base.limits, mine.limits) { target, was, edited ->
            ModelLimits(
                contextTokens = if (edited.contextTokens != was?.contextTokens) edited.contextTokens else target?.contextTokens,
                outputTokens = if (edited.outputTokens != was?.outputTokens) edited.outputTokens else target?.outputTokens,
            )
        },
        settled = settled + (mine.settled - base.settled) - (base.settled - mine.settled),
    )

    /** Whether [model]'s fields are the same here and in [other]. */
    fun sameFor(model: String, other: ModelAutoFields): Boolean =
        protocols[model] == other.protocols[model] &&
            capabilities[model] == other.capabilities[model] &&
            limits[model] == other.limits[model] &&
            (model in settled) == (model in other.settled)

    /** Only the fields of [names]; a removed model's old values must not come back with it. */
    fun only(names: Collection<String>): ModelAutoFields {
        val keep = if (names is Set<String>) names else names.toSet()
        return ModelAutoFields(
            protocols = protocols.filterKeys { it in keep },
            capabilities = capabilities.filterKeys { it in keep },
            limits = limits.filterKeys { it in keep },
            settled = settled.filterTo(mutableSetOf()) { it in keep },
        )
    }

    companion object {
        /** The fields of the models [config] lists. */
        fun of(config: ModelConfigData): ModelAutoFields {
            val listed = getModelList(config.modelName)
            return ModelAutoFields(
                protocols = config.modelProtocolSettings,
                capabilities = listed.associateWith(config::multimodalCapabilitiesForModel),
                limits = config.modelLimits,
                settled = config.settledModels(),
            ).only(listed)
        }

        /** [merge] gets the target's value, the base one and the edited one of a changed key. */
        private fun <V : Any> overlay(
            target: Map<String, V>,
            base: Map<String, V>,
            mine: Map<String, V>,
            merge: (target: V?, base: V?, edited: V) -> V,
        ): Map<String, V> {
            val result = target.toMutableMap()
            (base.keys + mine.keys).forEach { key ->
                val edited = mine[key]
                val was = base[key]
                if (edited != was) {
                    if (edited == null) result.remove(key) else result[key] = merge(target[key], was, edited)
                }
            }
            return result
        }
    }
}

/** This configuration with [fields] for the models it lists. */
fun ModelConfigData.withAutoFields(fields: ModelAutoFields): ModelConfigData {
    val listed = getModelList(modelName)
    val listedSet = listed.toSet()
    return copy(
        modelProtocolSettings = fields.protocols.filterKeys { it in listedSet },
        modelMultimodalCapabilities = listed.associateWith { fields.capabilities[it] ?: ModelMultimodalCapabilities() },
        modelLimits = fields.limits.filterKeys { it in listedSet },
        settledCapabilityModels = fields.settled.filterTo(mutableSetOf()) { it in listedSet },
    )
}

package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.api.chat.llmprovider.ThinkingRequestSemantics
import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import kotlinx.serialization.json.*

internal object CollaborationModelParameters {
    /**
     * The subagent's effort is mapped onto the target model's catalog declaration: a model that
     * lists no effort values rejects the parameter, so the request carries none instead of the
     * raw value.
     */
    fun apply(
        parameters: List<ModelParameter<*>>,
        effort: String?,
        declaredEfforts: List<String>? = null,
    ): List<ModelParameter<*>> {
        if (effort == null) return parameters
        val effectiveEffort =
            ThinkingRequestSemantics.declaredCatalogReasoningEffort(effort, declaredEfforts)
                ?: return parameters
        val reasoning = parameters.firstOrNull { it.apiName == "reasoning" && it.isEnabled }
        val updated = if (reasoning != null) {
            val objectValue = Json.parseToJsonElement(reasoning.currentValue.toString()).jsonObject
            ModelParameter(
                id = reasoning.id, name = reasoning.name, apiName = "reasoning",
                defaultValue = reasoning.defaultValue.toString(),
                currentValue =
                    JsonObject(objectValue + ("effort" to JsonPrimitive(effectiveEffort))).toString(),
                isEnabled = true, valueType = ParameterValueType.OBJECT, isCustom = true,
            )
        } else null
        return parameters.filterNot { it.apiName in setOf("reasoning_effort", "reasoning") } +
            listOfNotNull(updated) + ModelParameter(
            id = "collaboration_reasoning_effort",
            name = "reasoning_effort", apiName = "reasoning_effort",
            defaultValue = effectiveEffort, currentValue = effectiveEffort, isEnabled = true,
            valueType = ParameterValueType.STRING, isCustom = true,
        )
    }
}

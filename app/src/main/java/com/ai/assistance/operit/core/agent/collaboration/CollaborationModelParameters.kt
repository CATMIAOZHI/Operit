package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import kotlinx.serialization.json.*

internal object CollaborationModelParameters {
    fun apply(parameters: List<ModelParameter<*>>, effort: String?): List<ModelParameter<*>> {
        if (effort == null) return parameters
        val reasoning = parameters.firstOrNull { it.apiName == "reasoning" && it.isEnabled }
        val updated = if (reasoning != null) {
            val objectValue = Json.parseToJsonElement(reasoning.currentValue.toString()).jsonObject
            ModelParameter(
                id = reasoning.id, name = reasoning.name, apiName = "reasoning",
                defaultValue = reasoning.defaultValue.toString(),
                currentValue = JsonObject(objectValue + ("effort" to JsonPrimitive(effort))).toString(),
                isEnabled = true, valueType = ParameterValueType.OBJECT, isCustom = true,
            )
        } else null
        return parameters.filterNot { it.apiName in setOf("reasoning_effort", "reasoning") } +
            listOfNotNull(updated) + ModelParameter(
            id = "collaboration_reasoning_effort",
            name = "reasoning_effort", apiName = "reasoning_effort",
            defaultValue = effort, currentValue = effort, isEnabled = true,
            valueType = ParameterValueType.STRING, isCustom = true,
        )
    }
}

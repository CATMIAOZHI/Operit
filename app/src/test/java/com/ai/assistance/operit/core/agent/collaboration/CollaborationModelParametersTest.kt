package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.data.model.ModelParameter
import com.ai.assistance.operit.data.model.ParameterValueType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CollaborationModelParametersTest {
    @Test fun overridesEffortInBothChatAndResponsesFormsWithoutDroppingSummary() {
        val reasoning = ModelParameter(
            "r", "reasoning", "reasoning", defaultValue = "{}",
            currentValue = """{"effort":"low","summary":"auto"}""",
            isEnabled = true, valueType = ParameterValueType.OBJECT,
        )
        val result = CollaborationModelParameters.apply(listOf(reasoning), "high")
        val objectValue = Json.parseToJsonElement(result.single { it.apiName == "reasoning" }.currentValue.toString()).jsonObject
        assertEquals("high", objectValue["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", objectValue["summary"]!!.jsonPrimitive.content)
        assertEquals("high", result.single { it.apiName == "reasoning_effort" }.currentValue)
        assertSame(reasoning, CollaborationModelParameters.apply(listOf(reasoning), null).single())
    }
}

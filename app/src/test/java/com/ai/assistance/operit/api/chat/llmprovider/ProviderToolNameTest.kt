package com.ai.assistance.operit.api.chat.llmprovider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolNameTest {
    @Test
    fun `blank and missing names fail closed`() {
        assertNull(decodeProviderToolName(null))
        assertNull(decodeProviderToolName(JSONObject.NULL))
        assertNull(decodeProviderToolName(""))
        assertNull(decodeProviderToolName("   "))
        assertNull(JSONObject().optProviderToolName())
    }

    @Test
    fun `json null and non-string names are not coerced into the literal null tool`() {
        val jsonNull = JSONObject().put("name", JSONObject.NULL)
        val numeric = JSONObject().put("name", 123)
        val booleanName = JSONObject().put("name", true)
        val objectName = JSONObject().put("name", JSONObject().put("nested", "use_package"))

        assertNull(jsonNull.optProviderToolName())
        assertNull(numeric.optProviderToolName())
        assertNull(booleanName.optProviderToolName())
        assertNull(objectName.optProviderToolName())
        assertNull(decodeProviderToolName(JSONObject.NULL))
        assertNull(decodeProviderToolName(42))
        assertNull(decodeProviderToolName("null"))
        assertNull(JSONObject().put("name", "null").optProviderToolName())
    }

    @Test
    fun `valid tool names stay intact including package tools`() {
        val usePackage = JSONObject().put("name", "use_package")
        val packageTool = JSONObject().put("name", "super_admin:terminal")
        val padded = JSONObject().put("name", "  list_files  ")

        assertEquals("use_package", usePackage.optProviderToolName())
        assertEquals("super_admin:terminal", packageTool.optProviderToolName())
        assertEquals("list_files", padded.optProviderToolName())
        assertEquals("use_package", decodeProviderToolName("use_package"))
    }

    @Test
    fun `structured payload with a json-null name does not become an executable null tool`() {
        val payload = JSONObject().put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put(
                    "function",
                    JSONObject()
                        .put("name", JSONObject.NULL)
                        .put("arguments", JSONObject().put("package_name", "code_runner").toString())
                )
            )
        ).toString()

        val xml = StructuredToolCallBridge.convertToolCallPayloadToXml(payload)
        assertEquals(payload, xml)
        assertFalse(xml.contains("name=\"null\""))
    }

    @Test
    fun `structured payload with a real name still converts to xml`() {
        val payload = JSONObject().put(
            "tool_calls",
            JSONArray().put(
                JSONObject().put(
                    "function",
                    JSONObject()
                        .put("name", "use_package")
                        .put("arguments", JSONObject().put("package_name", "code_runner").toString())
                )
            )
        ).toString()

        val xml = StructuredToolCallBridge.convertToolCallPayloadToXml(payload)
        assertTrue(xml.contains("name=\"use_package\""))
        assertTrue(xml.contains("<param name=\"package_name\">code_runner</param>"))
        assertFalse(xml.contains("name=\"null\""))
    }
}

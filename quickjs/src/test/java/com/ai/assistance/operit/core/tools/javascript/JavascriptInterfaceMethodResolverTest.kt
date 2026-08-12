package com.ai.assistance.operit.core.tools.javascript

import android.webkit.JavascriptInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JavascriptInterfaceMethodResolverTest {
    private class NativeApiFixture {
        @JavascriptInterface
        fun exposed(value: String): String = value

        fun detachLifecycle() = Unit
    }

    @Test
    fun resolvesOnlyExplicitJavascriptInterfaceMethods() {
        val exposed =
            resolveJavascriptInterfaceMethod(
                NativeApiFixture::class.java,
                methodName = "exposed",
                argCount = 1
            )

        assertEquals("exposed", exposed?.name)
        assertNull(
            resolveJavascriptInterfaceMethod(
                NativeApiFixture::class.java,
                methodName = "detachLifecycle",
                argCount = 0
            )
        )
        assertNull(
            resolveJavascriptInterfaceMethod(
                NativeApiFixture::class.java,
                methodName = "toString",
                argCount = 0
            )
        )
    }

    @Test
    fun nativeDispatcherRejectsOversizedOrDeepArgumentsBeforeJsonParsing() {
        val oversized = "x".repeat(2_113_537)
        val deep = "[".repeat(65) + "0" + "]".repeat(65)
        val tooManyElements = List(65_537) { "0" }.joinToString(prefix = "[", postfix = "]")
        val tooManyOmittedSlots = "[" + ",".repeat(65_537) + "]"
        val tooManyObjectEntries =
            buildString {
                append("[{")
                repeat(65_537) { index ->
                    if (index > 0) append(',')
                    append('"').append(index).append("\":0")
                }
                append("}]")
            }

        val textError =
            assertThrows(IllegalArgumentException::class.java) {
                decodeNativeInterfaceArgs(oversized)
            }
        assertTrue(textError.message.orEmpty().contains("JSON text limit"))

        val depthError =
            assertThrows(IllegalArgumentException::class.java) {
                decodeNativeInterfaceArgs(deep)
            }
        assertTrue(depthError.message.orEmpty().contains("depth limit"))

        val elementError =
            assertThrows(IllegalArgumentException::class.java) {
                decodeNativeInterfaceArgs(tooManyElements)
            }
        assertTrue(elementError.message.orEmpty().contains("element limit"))

        val objectElementError =
            assertThrows(IllegalArgumentException::class.java) {
                decodeNativeInterfaceArgs(tooManyObjectEntries)
            }
        assertTrue(objectElementError.message.orEmpty().contains("element limit"))

        val omittedSlotError =
            assertThrows(IllegalArgumentException::class.java) {
                decodeNativeInterfaceArgs(tooManyOmittedSlots)
            }
        assertTrue(omittedSlotError.message.orEmpty().contains("element limit"))
        assertEquals(listOf("value,[,]"), decodeNativeInterfaceArgs("[\"value,[,]\"]"))
        listOf(
            "['value']",
            "[1;2]",
            "[{\"key\"=1}]",
            "[//comment\n1]",
            "[#comment\n1]",
            "[/*comment*/1]"
        ).forEach { lenientJson ->
            val lenientError =
                assertThrows(IllegalArgumentException::class.java) {
                    decodeNativeInterfaceArgs(lenientJson)
                }
            assertTrue(lenientError.message.orEmpty().contains("non-canonical JSON extension"))
        }

        assertTrue(isNativeMethodStrictlyBudgeted("javaCallStatic"))
        assertTrue(isNativeMethodStrictlyBudgeted("javaResolvePendingJsCallback"))
        assertTrue(isNativeMethodStrictlyBudgeted("__javaReleaseInstanceInternal"))
        assertTrue(isNativeMethodStrictlyBudgeted("listSandboxPackageDevPackageAssets"))
        assertTrue(isNativeMethodStrictlyBudgeted("readSandboxPackageDevPackageAssetBase64"))
        assertFalse(isNativeMethodStrictlyBudgeted("registerImageFromBase64"))
        assertFalse(isNativeMethodStrictlyBudgeted("sendCallIntermediateResult"))
    }
}

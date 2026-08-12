package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class ToolInvocationExtractionTest {
    @Test
    fun markdownExamples_doNotBecomeToolInvocations() = runBlocking {
        val content =
            """
            ```xml
            <tool name="fenced_example"><param name="path">/fake</param></tool>
            ```
            <think><tool name="nested_example"><param name="path">/nested</param></tool></think>
            <tool><param name="path">/malformed</param></tool>
            <tool name="read_file"><param name="path">/real</param></tool>
            <tool name="file_info"><param name="path">/second</param></tool>
            """.trimIndent()

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("read_file", "file_info"), invocations.map { it.tool.name })
        assertEquals("/real", invocations[0].tool.parameters.single().value)
        assertEquals("/second", invocations[1].tool.parameters.single().value)
    }

    @Test
    fun executableExtraction_ignoresClosedAndUnclosedThinkingBlocks() = runBlocking {
        val content =
            """
            <think>provider reasoning <tool name="write_file"><param name="path">/unsafe</param></tool></think>
            <tool name="visit_web"><param name="url">https://example.com</param></tool>
            <thinking>unfinished <tool name="delete_file"><param name="path">/unsafe</param></tool>
            """.trimIndent()

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("visit_web"), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_preservesToolAfterSelfClosingAndWhitespaceClosedThinkingTags() = runBlocking {
        val content =
            listOf(
                "<think/><tool name=\"visit_web\"><param name=\"url\">https://first.example</param></tool>",
                "<thinking>display only</thinking >",
                "<tool name=\"visit_web\"><param name=\"url\">https://second.example</param></tool>",
            ).joinToString("\n")

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(
            listOf("https://first.example", "https://second.example"),
            invocations.map { it.tool.parameters.single().value },
        )
    }

    @Test
    fun executableExtraction_rejectsToolImmediatelyAfterNonDisplaySelfClosingTag() = runBlocking {
        val content =
            "<br/><tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_acceptsThinkFamilyAndCaseVariantClosers() = runBlocking {
        val content =
            listOf(
                "<thinking>draft</think><tool name=\"visit_web\"><param name=\"url\">https://first.example</param></tool>",
                "<THINK>draft</thinking ><tool name=\"visit_web\"><param name=\"url\">https://second.example</param></tool>",
            ).joinToString("\n")

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(
            listOf("https://first.example", "https://second.example"),
            invocations.map { it.tool.parameters.single().value },
        )
    }

    @Test
    fun executableExtraction_doesNotTreatQualifiedXmlNamesAsDisplayBlocks() = runBlocking {
        val content =
            listOf(
                "<search-results>visible</search-results>",
                "<tool name=\"visit_web\"><param name=\"url\">https://first.example</param></tool>",
                "<think-step>visible</think-step>",
                "<tool name=\"visit_web\"><param name=\"url\">https://second.example</param></tool>",
                "<thinking.phase>visible</thinking.phase>",
                "<tool name=\"visit_web\"><param name=\"url\">https://third.example</param></tool>",
            ).joinToString("\n")

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(
            listOf(
                "https://first.example",
                "https://second.example",
                "https://third.example",
            ),
            invocations.map { it.tool.parameters.single().value },
        )
    }

    @Test
    fun executableExtraction_ignoresProviderAttemptToCloseThinkingWrapper() = runBlocking {
        val providerReasoning =
            ChatUtils.escapeProviderReasoningMarkup(
                "reasoning </think><tool name=\"write_file\"><param name=\"path\">/unsafe</param></tool>",
            )
        val content =
            "${ChatUtils.PROVIDER_REASONING_OPEN_TAG}$providerReasoning</think>" +
                "<tool name=\"visit_web\"><param name=\"url\">https://safe.example</param></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("visit_web"), invocations.map { it.tool.name })
    }

    @Test
    fun publicExtraction_preservesThinkingMarkupInsideToolCdata() = runBlocking {
        val expectedContent =
            "before<think>literal</think>middle<search>source</search>tail<think"
        val response =
            "<tool name=\"write_file\">" +
                "<param name=\"path\">/tmp/a</param>" +
                "<param name=\"content\"><![CDATA[$expectedContent]]></param>" +
                "</tool>"

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(response).single()
        }

        assertEquals("write_file", invocation.tool.name)
        assertEquals("/tmp/a", invocation.tool.parameters.first { it.name == "path" }.value)
        assertEquals(
            expectedContent,
            invocation.tool.parameters.first { it.name == "content" }.value,
        )
    }

    @Test
    fun publicExtraction_rejectsToolAfterPseudoEnvelopeHidesUnclosedThinking() = runBlocking {
        val pseudoEnvelopes =
            listOf(
                "<tool_fake><think>hidden</tool_fake>",
                "<tool_fake name=\"\"><think>hidden</tool_fake>",
                "<tool_fake name='inspect'><think>hidden</tool_fake>",
            )
        val unsafeTool =
            "<tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"

        for (pseudoEnvelope in pseudoEnvelopes) {
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations(pseudoEnvelope + unsafeTool)
            }
            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun publicExtraction_rejectsToolAfterDisplayOpenerOutsideProtectedParameter() = runBlocking {
        val response =
            "<tool_fake name=\"inspect\"><think>hidden</tool_fake>" +
                "<tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(response)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun publicExtraction_rejectsToolAfterNonExecutableParameterHidesUnclosedThinking() = runBlocking {
        val invalidParameterOpeners =
            listOf(
                "<param\u00A0name=\"value\">",
                "<param name=\"\">",
                "<param NAME=\"value\">",
            )
        val unsafeTool =
            "<tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"

        for (parameterOpener in invalidParameterOpeners) {
            val response =
                "<tool_fake name=\"inspect\">$parameterOpener<think>hidden</param></tool_fake>" +
                    unsafeTool
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations(response)
            }
            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun publicExtraction_rejectsToolAfterNonExecutableContextsHideUnclosedThinking() = runBlocking {
        val unsafeTool =
            "<tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"
        val prefixes =
            listOf(
                """```xml
                <tool name="example"><param name="payload"><think>hidden</param></tool>
                ```
                """.trimIndent(),
                "prefix <tool_fake name=\"inspect\"><param name=\"x\"><think>hidden</param></tool_fake>\n",
            )

        for (prefix in prefixes) {
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations(prefix + unsafeTool)
            }
            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun publicExtraction_failsClosedOnMalformedThinkingClosers() = runBlocking {
        val malformedClosers = listOf("</think.foo>", "</think/>", "</think bogus>")
        val unsafeTool =
            "<tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"

        for (closingTag in malformedClosers) {
            val response = "prefix <think>hidden$closingTag\n$unsafeTool"
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations(response)
            }
            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun publicExtraction_resumesAtDisplayOpenerAfterUnfinishedStrayCloser() = runBlocking {
        val prefixes = listOf("</think bogus ", "</think bogus \"")
        for (prefix in prefixes) {
            val response =
                prefix + "<think>hidden\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">https://unsafe.example</param></tool>"

            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations(response)
            }

            assertEquals(prefix, emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun executableExtraction_acceptsAttributedDisplayOpeners() = runBlocking {
        val content =
            "<think type=\"analysis\">draft</thinking >" +
                "<tool name=\"visit_web\"><param name=\"url\">https://safe.example</param></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("https://safe.example"), invocations.map { it.tool.parameters.single().value })
    }

    @Test
    fun executableExtraction_rejectsToolInsideQuotedAttributedThinkingBlock() = runBlocking {
        val content =
            "<think title=\"x/>y\">hidden\n" +
                "<tool name=\"visit_web\">" +
                "<param name=\"url\">https://unsafe.example</param></tool>" +
                "</think>answer"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun publicExtraction_preservesToolAfterStrayMalformedThinkingCloser() = runBlocking {
        val safeTool =
            "<tool name=\"visit_web\"><param name=\"url\">https://safe.example</param></tool>"

        listOf("</think.foo>", "</think/>", "</think bogus>", "</think").forEach { closingTag ->
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations("prefix$closingTag\n$safeTool")
            }
            assertEquals(listOf("visit_web"), invocations.map { it.tool.name })
        }
    }

    @Test
    fun publicExtraction_doesNotRestoreParametersInsideDisplayOnlyContent() = runBlocking {
        val response =
            "<tool name=\"visit_web\"><think>" +
                "<param name=\"url\">https://unsafe.example</param>" +
                "</think></tool>"

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(response).single()
        }

        assertEquals("visit_web", invocation.tool.name)
        assertEquals(emptyList<String>(), invocation.tool.parameters.map { it.name })
    }

    @Test
    fun publicExtraction_keepsVisibleParametersAfterClosedDisplayOnlyContent() = runBlocking {
        val response =
            "<tool name=\"visit_web\"><think>draft</think>" +
                "<param name=\"url\">https://safe.example</param></tool>"

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(response).single()
        }

        assertEquals("https://safe.example", invocation.tool.parameters.single().value)
    }

    @Test
    fun publicExtraction_failsClosedOnCrossNestedDisplayMarkupInsideUnprotectedParameter() =
        runBlocking {
            val response =
                "<tool name=\"visit_web\"><think>" +
                    "<param name=\"junk\"><search></think></param></think>" +
                    "<param name=\"url\">https://unsafe.example</param></tool>"

            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractToolInvocations(response)
            }

            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }

    @Test(timeout = 5_000L)
    fun publicExtraction_handlesManyRealProtectedParametersInOnePass() = runBlocking {
        val response =
            buildString {
                append("<tool name=\"batch\">")
                repeat(3_000) { index ->
                    append("<param name=\"p$index\">value$index</param>")
                }
                append("</tool>")
            }

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(response).single()
        }

        assertEquals(3_000, invocation.tool.parameters.size)
        assertEquals("value2999", invocation.tool.parameters.last().value)
    }

    @Test(timeout = 5_000L)
    fun publicExtraction_failsClosedAcrossManyRealParametersInOnePass() = runBlocking {
        val response =
            buildString {
                append("<tool name=\"batch\"><think>")
                repeat(3_000) { index ->
                    append("<param name=\"p$index\">value$index</param>")
                }
                append("</tool>\n")
                append(
                    "<tool name=\"visit_web\">" +
                        "<param name=\"url\">https://unsafe.example</param></tool>"
                )
            }

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractToolInvocations(response)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    private suspend fun <T> withoutAndroidLogging(block: suspend () -> T): T {
        return Mockito.mockStatic(AppLogger::class.java).use {
            try {
                StreamLogger.setEnabled(false)
                block()
            } finally {
                StreamLogger.setEnabled(true)
            }
        }
    }
}

package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.api.chat.protocol.ExecutableToolProtocolParser
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.stream.StreamLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito

class ToolInvocationExtractionTest {
    @Test
    fun parameterMarkdownCannotHideProtocolClosersOrTheNextTool() = runBlocking {
        for (value in listOf("```kotlin\nunfinished example", "`unfinished inline", "```\ntext\n```")) {
            val content = "<tool_write name=\"create_file\"><param name=\"content\">$value</param></tool_write>" +
                "\n<tool_read name=\"read_file\"><param name=\"path\">test.md</param></tool_read>"
            val inspection = ExecutableToolProtocolParser.inspectTruncation(content)
            assertEquals(null, inspection.truncatedTool)
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractExecutableToolInvocations(content)
            }
            assertEquals(listOf("create_file", "read_file"), invocations.map { it.tool.name })
            assertEquals(value, invocations.first().tool.parameters.single().value)
        }
    }

    @Test
    fun kdocBackticksInLongFileDoNotInvalidateToolOrShiftFollowingIndices() = runBlocking {
        val source = "/**\n * ```kotlin\n * example()\n * ```\n */\n" +
            "// file content\n".repeat(10_000)
        val create = "<tool_create name=\"create_file\"><param name=\"content\">$source</param></tool_create>"
        val following = "\n<tool_edit name=\"edit_file\"><param name=\"path\">test.kt</param></tool_edit>"
        val inspection = ExecutableToolProtocolParser.inspectTruncation(create)
        assertEquals(null, inspection.truncatedTool)
        assertEquals(listOf("create_file"), inspection.completeToolNames)
        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(create + following)
        }
        assertEquals(listOf("create_file", "edit_file"), invocations.map { it.tool.name })
        assertEquals(source, invocations.first().tool.parameters.single().value)
    }

    @Test
    fun executableExtraction_ignoresUnknownUnclosedMarkupBeforeTool() = runBlocking {
        val content =
            "usage:<bytes>, limit:<bytes>\n" +
                "<tool_avwh name=\"inspect\">" +
                "<param name=\"path\">/tmp/actual</param>" +
                "</tool_avwh>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("inspect"), invocations.map { it.tool.name })
        assertEquals("/tmp/actual", invocations.single().tool.parameters.single().value)
    }

    @Test
    fun executableExtraction_doesNotTreatArbitraryUnknownTagAsTypePlaceholder() = runBlocking {
        val content =
            "label:<x>\n" +
                "<tool name=\"unsafe\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_failsClosedForUsagePlaceholderWithNarrativeTail() = runBlocking {
        val content =
            "usage:<bytes> narrative\n" +
                "<tool name=\"unsafe\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_failsClosedAfterMalformedUnknownOpening() = runBlocking {
        val malformedPrefixes =
            listOf(
                "<narrative title=\"unfinished\n",
                "<narrative\n",
            )
        val unsafeTool = "<tool name=\"unsafe\"></tool>"

        for (prefix in malformedPrefixes) {
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractExecutableToolInvocations(prefix + unsafeTool)
            }

            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun executableExtraction_failsClosedAfterMalformedCompleteOpening() = runBlocking {
        val malformedPrefixes =
            listOf(
                "<narrative!>\n",
                "<think!foo>\n",
            )
        val unsafeTool = "<tool name=\"unsafe\"></tool>"

        for (prefix in malformedPrefixes) {
            val invocations = withoutAndroidLogging {
                ToolExecutionManager.extractExecutableToolInvocations(prefix + unsafeTool)
            }

            assertEquals(emptyList<String>(), invocations.map { it.tool.name })
        }
    }

    @Test
    fun executableExtraction_ignoresToolInsideXmlComment() = runBlocking {
        val content =
            "<!--\n" +
                "<tool name=\"unsafe\"></tool>\n" +
                "-->\n" +
                "<tool name=\"safe\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("safe"), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_failsClosedInsideUnclosedXmlComment() = runBlocking {
        val content =
            "<!--\n" +
                "<tool name=\"unsafe\"></tool>\n" +
                "<tool name=\"also_unsafe\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_ignoresParametersInsideMalformedCompleteContainer() = runBlocking {
        val content =
            "<tool name=\"write_file\">" +
                "<narrative!><param name=\"path\">/unsafe</param></narrative!>" +
                "</tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun truncationInspection_recognizesTopLevelPartialToolAfterFailClosedScan() {
        val inspection =
            ExecutableToolProtocolParser.inspectTruncation(
                "<tool name=\"visit_web\"",
            )

        assertEquals("tool", inspection.truncatedTool?.tagName)
        assertEquals("<tool name=\"visit_web\"", inspection.truncatedTool?.fragment)
    }

    @Test
    fun executableExtraction_ignoresToolShapedTextInsideInlineCode() = runBlocking {
        val content =
            "`<tool name=\"example\"><param name=\"path\">/fake</param></tool>`\n" +
                "<tool name=\"read_file\"><param name=\"path\">/real</param></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("read_file"), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_ignoresToolShapedTextInsideFencedCodeAfterProse() = runBlocking {
        val content =
            "Example: ```xml\n" +
                "<tool name=\"example\"><param name=\"path\">/fake</param></tool>\n" +
                "```\n" +
                "<tool name=\"read_file\"><param name=\"path\">/real</param></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("read_file"), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_preservesAdjacentToolCalls() = runBlocking {
        val content =
            "<tool name=\"first\"></tool>" +
                "<tool name=\"second\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("first", "second"), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_acceptsLegacyParameterSpacing() = runBlocking {
        val content =
            "<tool name=\"inspect\"><param name = \"path\">/tmp/actual</param></tool>"

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content).single()
        }

        assertEquals("path", invocation.tool.parameters.single().name)
        assertEquals("/tmp/actual", invocation.tool.parameters.single().value)
    }

    @Test
    fun executableExtraction_acceptsSingleQuotedParameterName() = runBlocking {
        val content =
            "<tool name=\"inspect\"><param name='path'>/tmp/actual</param></tool>"

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content).single()
        }

        assertEquals("path", invocation.tool.parameters.single().name)
        assertEquals("/tmp/actual", invocation.tool.parameters.single().value)
    }

    @Test
    fun executableExtraction_doesNotCloseToolInsideCdataParameter() = runBlocking {
        val payload = "script </tool> with <think>literal</think>"
        val content =
            "<tool name=\"write_file\">" +
                "<param name=\"content\"><![CDATA[$payload]]></param>" +
                "</tool>"

        val invocation = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content).single()
        }

        assertEquals(payload, invocation.tool.parameters.single().value)
    }

    @Test
    fun executableExtraction_doesNotBorrowClosingTagFromNestedTool() = runBlocking {
        val content =
            "<tool name=\"truncated\">" +
                "<tool name=\"real\"></tool>" +
                "</tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_ignoresParametersInsideUnknownContainers() = runBlocking {
        val content =
            "<tool name=\"write_file\">" +
                "<narrative><param name=\"path\">/unsafe</param></narrative>" +
                "</tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("write_file"), invocations.map { it.tool.name })
        assertEquals(emptyList<String>(), invocations.single().tool.parameters)
    }

    @Test
    fun executableExtraction_ignoresToolInsideClosedUnknownContainer() = runBlocking {
        val content =
            "<bytes>Note: <tool name=\"unsafe\"></tool></bytes>" +
                "<tool name=\"safe\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(listOf("safe"), invocations.map { it.tool.name })
    }

    @Test
    fun executableExtraction_failsClosedInsideUnclosedNarrativeContainer() = runBlocking {
        val content =
            "<bytes>Note: <tool name=\"unsafe\"></tool>\n" +
                "<tool name=\"also_unsafe\"></tool>"

        val invocations = withoutAndroidLogging {
            ToolExecutionManager.extractExecutableToolInvocations(content)
        }

        assertEquals(emptyList<String>(), invocations.map { it.tool.name })
    }

    @Test
    fun truncationInspection_ignoresToolShapedCodeExample() {
        val content =
            """
            ```xml
            <tool name="fake">
            """.trimIndent()

        val inspection = ExecutableToolProtocolParser.inspectTruncation(content)

        assertEquals(null, inspection.truncatedTool)
        assertEquals(emptyList<String>(), inspection.completeToolNames)
    }

    @Test(timeout = 5_000L)
    fun truncationInspection_handlesManyUnclosedCandidatesInLinearTime() {
        val content =
            buildString {
                repeat(3_000) { index ->
                    append("<tool name=\"truncated$index\">")
                }
            }

        val inspection = ExecutableToolProtocolParser.inspectTruncation(content)

        assertEquals("tool", inspection.truncatedTool?.tagName)
    }

    @Test
    fun truncatedRepair_usesQuotedTagEndAndClosesOpenParameter() {
        val fragment =
            "<tool name=\"write_file\" title=\"contains > safely\">" +
                "<param name=\"content\">partial"

        val suffix =
            ExecutableToolProtocolParser.buildTruncatedToolRepairSuffix(
                fragment = fragment,
                fallbackTagName = "tool",
            )

        assertEquals("</param></tool>", suffix)
    }

    @Test
    fun truncatedRepair_ignoresToolCloserInsideCdata() {
        val fragment =
            "<tool name=\"write_file\">" +
                "<param name=\"content\"><![CDATA[text </tool>]]>partial"

        val suffix =
            ExecutableToolProtocolParser.buildTruncatedToolRepairSuffix(
                fragment = fragment,
                fallbackTagName = "tool",
            )

        assertEquals("</param></tool>", suffix)
    }

    @Test
    fun truncatedRepair_closesUnfinishedCdataBeforeParameterAndTool() {
        val fragment =
            "<tool name=\"write_file\">" +
                "<param name=\"content\"><![CDATA[text </tool>"

        val suffix =
            ExecutableToolProtocolParser.buildTruncatedToolRepairSuffix(
                fragment = fragment,
                fallbackTagName = "tool",
            )

        assertEquals("]]></param></tool>", suffix)
    }

    @Test
    fun truncatedRepair_ordersOpenParameterBeforePartialToolCloser() {
        val fragment =
            "<tool name=\"write_file\"><param name=\"content\">partial</tool"

        val suffix =
            ExecutableToolProtocolParser.buildTruncatedToolRepairSuffix(
                fragment = fragment,
                fallbackTagName = "tool",
            )

        assertEquals("", suffix)
    }

    @Test
    fun truncatedRepair_readsSingleQuotedToolName() {
        assertEquals(
            "visit_web",
            ExecutableToolProtocolParser.extractAttributeValue(
                source = "<tool name='visit_web'>",
                attributeName = "name",
            ),
        )
    }

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

package com.ai.assistance.operit.util.stream.plugins

import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.markdown.NestedMarkdownProcessor
import com.ai.assistance.operit.util.stream.StreamLogger
import com.ai.assistance.operit.util.stream.splitBy
import com.ai.assistance.operit.util.stream.stream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamXmlPluginDisplayClosingTest {
    @Test
    fun displayClosingTagsAcceptWhitespaceFamilyAndCaseVariants() {
        try {
            StreamLogger.setEnabled(false)
            listOf(
                "<thinking>draft</thinking >",
                "<thinking>draft</think>",
                "<THINK>draft</thinking\n>",
                "<SEARCH>draft</search >",
            ).forEach { input ->
                val plugin = StreamXmlPlugin()
                plugin.initPlugin()
                var atStartOfLine = true
                input.forEach { character ->
                    plugin.processChar(character, atStartOfLine)
                    atStartOfLine = character == '\n'
                }
                assertEquals(input, PluginState.IDLE, plugin.state)
            }
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByExposesToolAfterDisplayClosingVariants() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            listOf(
                "<thinking>draft</thinking ><tool name=\"visit_web\"><param name=\"url\">one</param></tool>" to 1,
                "<thinking>draft</think><tool name=\"visit_web\"><param name=\"url\">two</param></tool>" to 1,
                "<THINK>draft</thinking\n><tool name=\"visit_web\"><param name=\"url\">three</param></tool>" to 1,
                (
                    "<thinking>draft</think><tool name=\"visit_web\"><param name=\"url\">four</param></tool>\n" +
                        "<THINK>draft</thinking ><tool name=\"visit_web\"><param name=\"url\">five</param></tool>"
                ) to 2,
            ).forEach { (input, expectedToolGroups) ->
                val groups = mutableListOf<Pair<Boolean, String>>()
                input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                    val text = StringBuilder()
                    group.stream.collect { text.append(it) }
                    groups.add((group.tag is StreamXmlPlugin) to text.toString())
                }
                assertEquals(
                    "$input -> $groups",
                    expectedToolGroups,
                    groups.count { (isXml, text) ->
                        isXml && ChatMarkupRegex.matchToolCall(text) != null
                    },
                )
            }
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByDoesNotExposeToolAfterArbitrarySelfClosingTag() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            val input =
                "<br/><tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool>"
            val groups = mutableListOf<Pair<Boolean, String>>()
            input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                val text = StringBuilder()
                group.stream.collect { text.append(it) }
                groups.add((group.tag is StreamXmlPlugin) to text.toString())
            }

            assertEquals(
                0,
                groups.count { (isXml, text) ->
                    isXml && ChatMarkupRegex.matchToolCall(text) != null
                },
            )
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByConsumesDisplayAdjacencyAllowanceOnMalformedMarkup() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            val input =
                "<think/><foo!bar><tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool>"
            val groups = mutableListOf<Pair<Boolean, String>>()
            input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                val text = StringBuilder()
                group.stream.collect { text.append(it) }
                groups.add((group.tag is StreamXmlPlugin) to text.toString())
            }

            assertEquals(
                "$input -> $groups",
                0,
                groups.count { (isXml, text) ->
                    isXml && ChatMarkupRegex.matchToolCall(text) != null
                },
            )
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByKeepsToolInsideNestedThinkFamilyBlock() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            listOf(
                "<think>outer<thinking>inner</thinking>\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool></think>answer",
                "<think>outer<thinking /\"x\">inner</thinking>\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool></think>answer",
                "<think>outer<thinking /'x'>inner</thinking>\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool></think>answer",
                "<think /\"x\">hidden\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool></think>answer",
                "<think /'x'>hidden\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool></think>answer",
            ).forEach { input ->
                val groups = mutableListOf<Pair<Boolean, String>>()
                input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                    val text = StringBuilder()
                    group.stream.collect { text.append(it) }
                    groups.add((group.tag is StreamXmlPlugin) to text.toString())
                }

                val toolGroup = groups.single { (_, text) -> "<tool" in text }
                assertEquals("$input -> $groups", true, toolGroup.first)
                assertEquals("$input -> $groups", true, "<think" in toolGroup.second)
            }
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByKeepsToolInsideCrossFamilyDisplayNesting() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            listOf(
                "<think><search>hidden</search><tool name=\"visit_web\"></tool></think>answer",
                "<search><thinking>hidden</thinking><tool name=\"visit_web\"></tool></search>answer",
                "<think><search>hidden</think><tool name=\"visit_web\"></tool></search>",
                "<search><think>hidden</search><tool name=\"visit_web\"></tool></think>",
            ).forEach { input ->
                val groups = mutableListOf<Pair<Boolean, String>>()
                input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                    val text = StringBuilder()
                    group.stream.collect { text.append(it) }
                    groups.add((group.tag is StreamXmlPlugin) to text.toString())
                }

                val toolGroup = groups.single { (_, text) -> "<tool" in text }
                assertEquals("$input -> $groups", true, toolGroup.first)
                assertEquals("$input -> $groups", true, toolGroup.second.startsWith("<think") || toolGroup.second.startsWith("<search"))
            }
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByKeepsQualifiedXmlNamesSeparateFromFollowingTools() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            val input =
                listOf(
                    "<search-results>visible</search-results>",
                    "<tool name=\"visit_web\"><param name=\"url\">one</param></tool>",
                    "<think-step>visible</think-step>",
                    "<tool name=\"visit_web\"><param name=\"url\">two</param></tool>",
                    "<thinking.phase>visible</thinking.phase>",
                    "<tool name=\"visit_web\"><param name=\"url\">three</param></tool>",
                ).joinToString("\n")
            val groups = mutableListOf<Pair<Boolean, String>>()
            input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                val text = StringBuilder()
                group.stream.collect { text.append(it) }
                groups.add((group.tag is StreamXmlPlugin) to text.toString())
            }

            assertEquals(
                "$input -> $groups",
                3,
                groups.count { (isXml, text) ->
                    isXml && ChatMarkupRegex.matchToolCall(text) != null
                },
            )
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByDoesNotTruncateInvalidNameBoundariesIntoDisplayTags() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            for (invalid in listOf("<think!foo>visible</think!foo>", "<think@foo>visible</think@foo>")) {
                val input =
                    invalid + "\n" +
                        "<tool name=\"visit_web\"><param name=\"url\">safe</param></tool>"
                val groups = mutableListOf<Pair<Boolean, String>>()
                input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                    val text = StringBuilder()
                    group.stream.collect { text.append(it) }
                    groups.add((group.tag is StreamXmlPlugin) to text.toString())
                }

                assertEquals(
                    "$input -> $groups",
                    1,
                    groups.count { (isXml, text) ->
                        isXml && ChatMarkupRegex.matchToolCall(text) != null
                    },
                )
                assertEquals(
                    "$input -> $groups",
                    0,
                    groups.count { (isXml, text) -> isXml && invalid in text },
                )
            }
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByDoesNotExposeToolInsideQuotedAttributedThinkingBlock() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            val input =
                "<think title=\"x/>y\">hidden\n" +
                    "<tool name=\"visit_web\"><param name=\"url\">unsafe</param></tool>" +
                    "</think>answer"
            val groups = mutableListOf<Pair<Boolean, String>>()
            input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                val text = StringBuilder()
                group.stream.collect { text.append(it) }
                groups.add((group.tag is StreamXmlPlugin) to text.toString())
            }

            assertEquals(
                "$input -> $groups",
                0,
                groups.count { (isXml, text) ->
                    isXml && ChatMarkupRegex.matchToolCall(text) != null
                },
            )
        } finally {
            StreamLogger.setEnabled(true)
        }
    }

    @Test
    fun splitByKeepsMultilineQuotedMarkdownInsideXmlBlock() = runBlocking {
        try {
            StreamLogger.setEnabled(false)
            val input = "<think title=\"x>\n# heading\n\">secret</think>answer"
            val groups = mutableListOf<Pair<StreamPlugin?, String>>()
            input.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
                val text = StringBuilder()
                group.stream.collect { text.append(it) }
                groups.add(group.tag to text.toString())
            }

            val headingGroup = groups.single { (_, text) -> "# heading" in text }
            assertEquals("$input -> $groups", true, headingGroup.first is StreamXmlPlugin)
            assertEquals(
                "<think title=\"x>\n# heading\n\">secret</think>",
                headingGroup.second,
            )
        } finally {
            StreamLogger.setEnabled(true)
        }
    }
}

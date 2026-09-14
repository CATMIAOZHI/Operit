package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.ui.common.markdown.MarkdownGroupedItem
import com.ai.assistance.operit.data.preferences.ToolCollapseMode
import com.ai.assistance.operit.ui.features.chat.components.part.ThinkToolsXmlNodeGrouper
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import org.junit.Assert.*
import org.junit.Test

class ThinkToolsPagingTest {
    private fun xml(text: String) = MarkdownNodeStable(MarkdownProcessorType.XML_BLOCK, text, emptyList())
    private fun call(id: Int) = xml("""<tool name="read_file" call_id="$id"></tool>""")
    private fun result(id: Int) = xml("""<tool_result name="read_file" call_id="$id">ok</tool_result>""")

    @Test
    fun longSequentialAgentTranscriptSplitsWithoutLosingNodes() {
        val nodes = (0 until 100).flatMap { listOf(call(it), result(it)) }
        val groups = ThinkToolsXmlNodeGrouper(true).group(nodes, "test")
        assertTrue(groups.size > 1)
        val indices = groups.flatMap {
            when (it) {
                is MarkdownGroupedItem.Single -> listOf(it.index)
                is MarkdownGroupedItem.Group -> {
                    assertTrue(it.endIndexInclusive - it.startIndex + 1 <= 16)
                    (it.startIndex..it.endIndexInclusive).toList()
                }
            }
        }
        assertEquals(nodes.indices.toList(), indices)
    }

    @Test
    fun concurrentBatchKeepsAllCallsWithTheirResults() {
        val nodes = (0 until 12).map(::call) + (0 until 12).reversed().map(::result) +
            listOf(call(12), result(12))
        val groups = ThinkToolsXmlNodeGrouper(true).group(nodes, "test")
        val first = groups.first() as MarkdownGroupedItem.Group
        assertEquals(0, first.startIndex)
        assertEquals(23, first.endIndexInclusive)
    }

    @Test
    fun fullModeKeepsToolResultOnlySequenceUnfolded() {
        // 媒体标记会把工具序列切开，只剩工具结果、没有 <tool> 的片段在 FULL 模式下曾折叠成
        // “工具调用（0）”的空标题，这里锁定不再折叠的行为。
        val nodes = listOf(xml("<think>thinking</think>"), result(1), result(2))
        val groups =
            ThinkToolsXmlNodeGrouper(true, toolCollapseMode = ToolCollapseMode.FULL)
                .group(nodes, "test")
        assertEquals(
            nodes.indices.toList(),
            groups.map {
                when (it) {
                    is MarkdownGroupedItem.Single -> it.index
                    is MarkdownGroupedItem.Group -> it.startIndex
                }
            }
        )
        assertTrue(groups.all { it is MarkdownGroupedItem.Single })
    }

    @Test
    fun fullModeStillCollapsesThinkWithToolCalls() {
        val nodes = listOf(xml("<think>thinking</think>"), call(1), result(1))
        val groups =
            ThinkToolsXmlNodeGrouper(true, toolCollapseMode = ToolCollapseMode.FULL)
                .group(nodes, "test")
        assertEquals(1, groups.size)
        val group = groups.first() as MarkdownGroupedItem.Group
        assertEquals(0, group.startIndex)
        assertEquals(2, group.endIndexInclusive)
        assertEquals("think-tools-0", group.stableKey)
    }

    @Test
    fun fullModeKeepsStandaloneToolResultsUnfolded() {
        // 被媒体标记切开后只剩工具结果的片段会从 tool_result 起头，走的是 tools-only 分支，
        // 同样不能再折叠成“工具调用（0）”。
        val nodes = listOf(result(1), result(2))
        val groups =
            ThinkToolsXmlNodeGrouper(true, toolCollapseMode = ToolCollapseMode.FULL)
                .group(nodes, "test")
        assertTrue(groups.all { it is MarkdownGroupedItem.Single })
        assertEquals(nodes.indices.toList(), groups.map { (it as MarkdownGroupedItem.Single).index })
    }
}

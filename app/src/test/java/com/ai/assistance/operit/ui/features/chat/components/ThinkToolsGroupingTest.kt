package com.ai.assistance.operit.ui.features.chat.components

import com.ai.assistance.operit.ui.common.markdown.MarkdownGroupedItem
import com.ai.assistance.operit.data.preferences.ToolCollapseMode
import com.ai.assistance.operit.ui.features.chat.components.part.ThinkToolsXmlNodeGrouper
import com.ai.assistance.operit.util.markdown.MarkdownNodeStable
import com.ai.assistance.operit.util.markdown.MarkdownProcessorType
import org.junit.Assert.*
import org.junit.Test

class ThinkToolsGroupingTest {
    private fun xml(text: String) = MarkdownNodeStable(MarkdownProcessorType.XML_BLOCK, text, emptyList())
    private fun text(value: String) = MarkdownNodeStable(MarkdownProcessorType.PLAIN_TEXT, value, emptyList())
    private fun call(id: Int) = xml("""<tool name="read_file" call_id="$id"></tool>""")
    private fun result(id: Int) = xml("""<tool_result name="read_file" call_id="$id">ok</tool_result>""")
    private fun think(body: String) = xml("<think>" + body + "</think>")

    @Test
    fun longSequentialAgentTranscriptFoldsIntoOneGroup() {
        // 一次连续的工具序列只折叠一个分组；按条数切块会让长任务重复出现
        // 多个「思考与工具调用（8）」标题，并把真实的调用总数藏进分块里。
        val nodes = (0 until 100).flatMap { listOf(call(it), result(it)) }
        val groups = ThinkToolsXmlNodeGrouper(true).group(nodes, "test")
        assertEquals(1, groups.size)
        val group = groups.first() as MarkdownGroupedItem.Group
        assertEquals(0, group.startIndex)
        assertEquals(nodes.lastIndex, group.endIndexInclusive)
    }

    @Test
    fun thinkingRunWithManyToolsFoldsIntoOneGroup() {
        // think 起头的那条路径，就是用户看到「思考与工具调用（8）」重复出现的地方。
        val nodes = listOf(think("thinking")) + (0 until 40).flatMap { listOf(call(it), result(it)) }
        val groups = ThinkToolsXmlNodeGrouper(true).group(nodes, "test")
        assertEquals(1, groups.size)
        val group = groups.first() as MarkdownGroupedItem.Group
        assertEquals(0, group.startIndex)
        assertEquals(nodes.lastIndex, group.endIndexInclusive)
        assertEquals("think-tools-0", group.stableKey)
        // 索引恰好被划分一次：不丢、不重。
        assertEquals(
            nodes.indices.toList(),
            groups.flatMap { item ->
                when (item) {
                    is MarkdownGroupedItem.Single -> listOf(item.index)
                    is MarkdownGroupedItem.Group ->
                        (item.startIndex..item.endIndexInclusive).toList()
                }
            }
        )
    }

    @Test
    fun visibleAnswerEndsTheFoldedRun() {
        val nodes = (0 until 40).flatMap { listOf(call(it), result(it)) } + listOf(text("Final answer"))
        val groups = ThinkToolsXmlNodeGrouper(true).group(nodes, "test")
        assertEquals(2, groups.size)
        val group = groups.first() as MarkdownGroupedItem.Group
        assertEquals(0, group.startIndex)
        assertEquals(nodes.size - 2, group.endIndexInclusive)
        assertEquals(nodes.lastIndex, (groups.last() as MarkdownGroupedItem.Single).index)
    }

    @Test
    fun concurrentBatchKeepsAllCallsWithTheirResults() {
        val nodes = (0 until 12).map(::call) + (0 until 12).reversed().map(::result) +
            listOf(call(12), result(12))
        val groups = ThinkToolsXmlNodeGrouper(true).group(nodes, "test")
        val first = groups.first() as MarkdownGroupedItem.Group
        assertEquals(0, first.startIndex)
        assertEquals(nodes.lastIndex, first.endIndexInclusive)
    }

    @Test
    fun fullModeKeepsToolResultOnlySequenceUnfolded() {
        // 媒体标记会把工具序列切开，只剩工具结果、没有 <tool> 的片段在 FULL 模式下曾折叠成
        // “工具调用（0）”的空标题，这里锁定不再折叠的行为。
        val nodes = listOf(think("thinking"), result(1), result(2))
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
        val nodes = listOf(think("thinking"), call(1), result(1))
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

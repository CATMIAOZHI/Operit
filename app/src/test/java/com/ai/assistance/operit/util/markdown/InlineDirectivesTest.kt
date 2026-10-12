package com.ai.assistance.operit.util.markdown

import com.ai.assistance.operit.util.markdown.InlineDirectives.Badge
import com.ai.assistance.operit.util.markdown.InlineDirectives.Icon
import com.ai.assistance.operit.util.markdown.InlineDirectives.Text
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineDirectivesTest {
    private val known = setOf("check", "time")::contains

    @Test
    fun badgeAndIconSitInsideOneSentence() {
        val parts = InlineDirectives.parse("状态：:badge[已完成]{tone=success icon=check}，耗时 :icon[time] **2 分钟**", known)
        assertEquals(
            listOf(
                Text("状态："),
                Badge("已完成", "success", "check"),
                Text("，耗时 "),
                Icon("time", null),
                Text(" **2 分钟**"),
            ),
            parts,
        )
    }

    @Test
    fun unknownValuesDegradeQuietly() {
        assertEquals(
            listOf(Badge("风险", null, null)),
            InlineDirectives.parse(":badge[风险]{tone=purple, icon=rocket}", known),
        )
        assertEquals(listOf(Text("看"), Text(""), Text("这里")), InlineDirectives.parse("看:icon[rocket]这里", known))
    }

    @Test
    fun malformedSyntaxStaysText() {
        listOf(":badge[]", ":badge[多行\n文本]", "badge[x]", ":badge[${"长".repeat(17)}]").forEach { raw ->
            assertEquals(listOf(Text(raw)), InlineDirectives.parse(raw, known))
            assertEquals(raw, InlineDirectives.toPlainText(raw))
        }
        assertEquals(":badge[ ]", InlineDirectives.toPlainText(":badge[ ]"))
    }

    @Test
    fun plainTextKeepsBadgeLabelsAndDropsIcons() {
        assertEquals(
            "构建 通过，耗时  2 分钟",
            InlineDirectives.toPlainText("构建 :badge[通过]{tone=success}，耗时 :icon[time] 2 分钟"),
        )
    }
}

package com.ai.assistance.operit.features.reading

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终审 BLOCKING-1：角色卡完整 CHAT 人设进入 subagent 上下文（任务 prompt / get_constraints），
 * 而非仅角色名。
 *
 * 行为级断言驱动真实的 prompt 构造路径（[ReadingCompanionSubagentCoordinator.buildSubagentTaskPrompt]，
 * 即 runGeneration 实际使用的同一函数）；get_constraints 的完整人设断言在
 * ReadingCompanionSubagentToolsTest 中走真实工具执行路径。
 */
class ReadingCompanionPersonaContextTest {

    private val fullPersona =
        "【设定】雨夜侦探，冷静毒舌，喜欢用比喻点评人物。\n" +
            "【口吻】短句、吐槽，关注细节描写与伏笔。"

    @Test
    fun `changing book and chapter preserves the rules and persona prefix in both modes`() {
        for (summaryOnly in listOf(false, true)) {
            fun prompt(book: String, chapter: Int) =
                ReadingCompanionSubagentCoordinator.buildSubagentTaskPrompt(
                    bookName = book,
                    chapterIndex = chapter,
                    roleCardName = "Rainy",
                    rolePrompt = fullPersona,
                    summaryOnly = summaryOnly,
                )
            val first = prompt("Book A", 2)
            val second = prompt("Book B", 8)
            val separator = "\n\n本次任务：\n"
            assertTrue(first.contains(separator))
            org.junit.Assert.assertEquals(
                first.substringBefore(separator),
                second.substringBefore(separator),
            )
            assertFalse(first.substringBefore(separator).contains("Book A"))
            assertFalse(first.substringBefore(separator).contains("阅读范围："))
            assertTrue(first.substringAfter(separator).contains("阅读范围："))
            if (!summaryOnly) {
                assertTrue(first.startsWith("以下是本次伴读角色卡"))
                assertTrue(
                    first.indexOf("</reader_persona>") <
                        first.indexOf("reading_commentary_list_chapters"),
                )
            }
            assertTrue(first.substringAfter(separator).contains("第 3 章"))
            assertTrue(second.substringAfter(separator).contains("第 9 章"))
        }
    }

    @Test
    fun `task prompt carries the full persona inside a controlled reader_persona block`() {
        val prompt =
            ReadingCompanionSubagentCoordinator.buildSubagentTaskPrompt(
                bookName = "Book A",
                chapterIndex = 2,
                roleCardName = "Rainy",
                rolePrompt = fullPersona,
            )
        assertTrue(prompt.contains("角色卡「Rainy」的口吻"))
        assertTrue(prompt.contains("<reader_persona>"))
        assertTrue(prompt.contains("</reader_persona>"))
        assertTrue(
            "完整人设文本必须进任务 prompt，而不是只给角色名",
            prompt.contains("雨夜侦探，冷静毒舌"),
        )
        assertTrue(prompt.contains("使用它的性格、口吻和阅读偏好来写段评"))
    }

    @Test
    fun `blank persona keeps the plain task prompt and omits the reader_persona block`() {
        val prompt =
            ReadingCompanionSubagentCoordinator.buildSubagentTaskPrompt(
                bookName = "Book A",
                chapterIndex = 2,
                roleCardName = "Rainy",
                rolePrompt = "",
            )
        assertTrue(prompt.contains("角色卡「Rainy」的口吻"))
        assertFalse(prompt.contains("<reader_persona>"))
        assertFalse(prompt.contains("以下是本次伴读角色卡"))
    }

    @Test
    fun `task prompt requires the anchor to follow every supporting paragraph`() {
        val prompt =
            ReadingCompanionSubagentCoordinator.buildSubagentTaskPrompt(
                bookName = "Book A",
                chapterIndex = 2,
                roleCardName = "Rainy",
                rolePrompt = fullPersona,
            )

        assertTrue(prompt.contains("最后一个段落"))
        assertTrue(prompt.contains("evidenceIds 必须包含 anchorId"))
        assertTrue(prompt.contains("所有证据段落都不得晚于 anchorId"))
        assertTrue(prompt.contains("重新提交完整修正版"))

        val submitPrompt =
            ReadingCompanionSubagentTools
                .prompts()
                .single { it.name == ReadingCompanionSubagentTools.TOOL_SUBMIT_COMMENTS }
        assertTrue(submitPrompt.description.contains("latest (highest) paragraph"))
        assertTrue(submitPrompt.description.contains("every evidenceId must be less than or equal"))
        assertTrue(submitPrompt.description.contains("whole array is rejected"))
        assertTrue(
            submitPrompt.parametersStructured
                .orEmpty()
                .single { it.name == "comments" }
                .description
                .contains("must never point after it"),
        )
    }

}

package com.ai.assistance.operit.features.reading

import java.io.File
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
    fun `subagent path resolves the persona from the model gateway and passes it to the coordinator`() {
        val autoCommentarySource =
            File(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionAutoCommentary.kt",
            ).readText()
        assertTrue(
            "generateViaSubagent 必须解析完整人设",
            autoCommentarySource.contains("modelGateway.resolveAutoCommentRolePrompt("),
        )
        assertTrue(
            "解析结果必须传给 runGeneration",
            autoCommentarySource.contains("rolePrompt = rolePrompt"),
        )
        val coordinatorSource =
            File(
                "src/main/java/com/ai/assistance/operit/features/reading/" +
                    "ReadingCompanionSubagentCoordinator.kt",
            ).readText()
        assertTrue(
            "session 必须携带 rolePrompt（get_constraints 依赖它）",
            coordinatorSource.contains("rolePrompt = rolePrompt"),
        )
    }
}

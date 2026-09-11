package com.ai.assistance.operit.ui.features.chat.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤回确认框依赖 [messageHasExecutableToolCall] 判断被删除的轮次是否调用过工具。
 *
 * 回归重点：正文里出现工具标签 != 执行过工具。记忆文件夹附件会把示例 `<tool …>` 写进用户消息，
 * 模型在代码块里演示写法也很常见，这两种都不该触发"调用过工具"的额外提示。
 */
class MessageRevertToolCallDetectionTest {
    @Test
    fun aiMessageWithExecutableToolCallIsDetected() {
        val content =
            "我来读取文件。\n" +
                "<tool_DV0n name=\"read_file\"><param name=\"path\">README.md</param></tool_DV0n>\n" +
                "<tool_result_DV0n name=\"read_file\" status=\"success\"><content>ok</content></tool_result_DV0n>"

        assertTrue(messageHasExecutableToolCall("ai", content))
    }

    @Test
    fun userMessageWithToolSamplesIsIgnored() {
        // 记忆文件夹附件内联的示例文本会原样写进持久化用户消息。
        val content =
            "<attachment id=\"1\" filename=\"memory\" type=\"text\">\n" +
                "使用方式示例：<tool name=\"query_memory\"><param name=\"query\">关键词</param></tool>\n" +
                "</attachment>"

        assertFalse(messageHasExecutableToolCall("user", content))
    }

    @Test
    fun aiMessageWithToolSampleInCodeFenceIsIgnored() {
        val content =
            "可以这样调用：\n```\n<tool_xcoi name=\"read_file\"><param name=\"path\">README.md</param></tool_xcoi>\n```"

        assertFalse(messageHasExecutableToolCall("ai", content))
    }

    @Test
    fun aiMessageWithoutToolMarkupIsIgnored() {
        assertFalse(messageHasExecutableToolCall("ai", "普通回答，没有任何工具调用。"))
        assertFalse(messageHasExecutableToolCall("ai", ""))
    }
}

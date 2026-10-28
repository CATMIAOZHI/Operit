package com.ai.assistance.operit.features.orbit

import com.ai.assistance.operit.data.model.ConversationSummaryConfig
import com.ai.assistance.operit.data.model.SummarySectionOverride

/**
 * How an Orbit chat is summarized when it grows too long (Muse's compaction): conclusions and
 * state, not the path to them, one entry per matter with its key identifiers copied exactly, the
 * user's decisions, what is still open and the rules to keep, each with a pointer back to its
 * source. Orbit's goals, tasks, notes and files are outside the chat and are not summarized.
 */
object OrbitSummary {
    /** [base] keeps the user's other summary settings; Orbit's sections and rules replace theirs. */
    fun config(base: ConversationSummaryConfig, english: Boolean): ConversationSummaryConfig =
        base.copy(sectionOverrides = sections(english), globalRules = rules(english))

    private fun sections(english: Boolean) = if (english) listOf(
        SummarySectionOverride("core_task", title = "Matters",
            instruction = "One entry per matter or task the user and you dealt with, newest first. For each, five short labelled lines:\n" +
                "- Conclusion: where it stands now (done / in progress / waiting), and the next step.\n" +
                "- Identifiers: codes, amounts, dates, times, names, links, file paths and task ids, copied exactly.\n" +
                "- Decisions: what the user settled, including \"don't bring this up again\".\n" +
                "- Open: what is still waiting, on whom, for what.\n" +
                "- Constraints: limits that change what you do next.\n" +
                "End each line that states a fact with its source in brackets: (#message number and date, file path, or task id)."),
        SummarySectionOverride("interaction", title = "The user and how to work with them",
            instruction = "What this stretch showed about the user: preferences, tone, corrections they made and how they want things done. " +
                "Facts that belong in memory.md are already there; only note what matters for the next replies. No chit-chat, no quotes of small talk."),
        SummarySectionOverride("progress", title = "Delivered",
            instruction = "What you made or did that lasts outside the chat: files, artifacts, cards, scheduled tasks, goals, sent messages, with where they are " +
                "(path, name, task id). Leave out attempts that went nowhere and raw tool output."),
        SummarySectionOverride("key_info", title = "Rules to keep",
            instruction = "- Every standing rule or way of working the user set that affects what you do next, one per item, with its source."),
    ) else listOf(
        SummarySectionOverride("core_task", title = "事项",
            instruction = "和用户处理过的每件事一条，新的在前。每条写五行，每行前面带标签：\n" +
                "- 结论：现在到哪一步（已完成/进行中/在等），下一步是什么。\n" +
                "- 关键标识：编号、金额、日期时间、人名、链接、文件路径、任务 id，原样照抄。\n" +
                "- 决定：用户拍板的事，包括「这个不要再议」。\n" +
                "- 未决：还在等什么、等谁。\n" +
                "- 约束：会影响下一步的限制。\n" +
                "写事实的行末尾用括号标出处：（#消息序号和日期、文件路径或任务 id）。"),
        SummarySectionOverride("interaction", title = "用户与相处",
            instruction = "这段对话里看出的用户特点：偏好、语气、他纠正过你什么、他希望怎么做。已经写进 memory.md 的事实不用重复，只记对接下来回复有用的。不写闲聊原文。"),
        SummarySectionOverride("progress", title = "已交付",
            instruction = "做出来、留在聊天之外的东西：文件、成果物、卡片、定时任务、目标、发出去的消息，写清在哪（路径、名字、任务 id）。没成功的尝试和工具原始输出不写。"),
        SummarySectionOverride("key_info", title = "要守的规矩",
            instruction = "- 用户定下的、会影响以后怎么做的规矩和做法，一条一项，带出处。"),
    )

    private fun rules(english: Boolean) = if (english) """
        Orbit rules, which come before the format notes above: every section is written as short labelled items, not paragraphs.
        Keep conclusions and state; drop trial and error, raw tool output, guesses that a later conclusion replaced, and small talk.
        Never round or paraphrase an identifier: a summary that loses a code or an amount is worse than a long one.
        Orbit's goals, tasks, notes, files and memory live outside the chat and survive this summary; do not copy them in, only point to them.
        When the newer messages correct something older, keep only the corrected version.
    """.trimIndent() else """
        Orbit 的规则，优先于上面的格式说明：每一节都写成带标签的短条目，不写成段落。
        留结论和状态；试错过程、工具原始输出、已被后来结论推翻的推测、闲聊，都不要。
        关键标识不许约数、不许改写：丢掉一个编号或金额，比写长一点更糟。
        目标、任务、笔记、文件和记忆在聊天之外，压缩后还在；不用抄进来，指一下在哪就行。
        新消息纠正了旧内容时，只留纠正后的版本。
    """.trimIndent()

    /**
     * Put after the summary in the chat: the first reply after it reconciles state before
     * answering, and goes back to the source before acting on a fact.
     */
    fun reconcileNote(english: Boolean): String = if (english) {
        "[After this summary] The summary is a map, not the ground. In your next reply, first check state: what is still open " +
            "(orbit_task pending), your goals, and memory.md, so you do not work from a stale summary. Before acting on a price, time " +
            "or code, check it at its source: search_chat_history for the user's own words, the file, or the task. When the summary " +
            "and the latest evidence disagree, go with the evidence and fix the note that was wrong."
    } else {
        "【摘要之后】摘要是地图，不是实地。接下来第一次回复前，先对一遍账：还在等的事（orbit_task 的 pending）、目标和 memory.md，" +
            "别拿着过期的摘要继续做。要按价格、时间、编号行动前，回源头核一遍：用 search_chat_history 找用户原话，或看文件、任务。" +
            "摘要和最新证据对不上时，以证据为准，并把记错的笔记改正。"
    }
}

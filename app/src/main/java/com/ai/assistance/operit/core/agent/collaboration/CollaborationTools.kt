package com.ai.assistance.operit.core.agent.collaboration

import com.ai.assistance.operit.core.agent.AgentProfile
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt

/** The separate Codex v2 tool surface. The legacy task schema remains owned by v1. */
object CollaborationTools {
    val names = setOf(
        "spawn_agent", "send_message", "followup_task", "interrupt_agent", "list_agents", "wait_agent",
    )

    fun category(chinese: Boolean, profiles: List<AgentProfile>): SystemToolPromptCategory {
        fun text(cn: String, en: String) = if (chinese) cn else en
        fun parameter(name: String, cn: String, en: String, required: Boolean = false) =
            ToolParameterSchema(name = name, type = "string", description = text(cn, en), required = required)
        val target = parameter(
            "target", "目标代理 ID、当前代理下的相对任务名或 /root 开始的完整路径。",
            "Agent ID, task name relative to the caller, or canonical path beginning with /root.", true,
        )
        val message = parameter("message", "发送给代理的非空消息。", "Non-empty message for the agent.", true)
        return SystemToolPromptCategory(
            categoryName = "Subagent v2",
            tools = listOf(
                ToolPrompt(
                    name = "spawn_agent",
                    description = text(
                        "异步创建具名子代理；立即返回路径。子代理可协作、发送消息并继续执行新任务。完成消息自动投递到父代理邮箱。仅为可独立执行的具体子任务创建代理。",
                        "Spawn a named child asynchronously and return its path immediately. Agents can collaborate, exchange messages and accept follow-up tasks. Completion is delivered to the parent's mailbox. Spawn only for a concrete independent subtask.",
                    ),
                    parametersStructured = listOf(
                        parameter("task_name", "任务名，只含小写字母、数字和下划线，不可为 root。", "Task name: lowercase letters, digits and underscores; root is reserved.", true),
                        message,
                        parameter("fork_turns", "继承父上下文：all（默认）、none 或正整数轮数。", "Parent context: all (default), none, or a positive number of turns."),
                        parameter("agent_type", "代理配置 ID：" + profiles.joinToString { it.id }, "Agent profile ID: " + profiles.joinToString { it.id }),
                        parameter("model", "可选的已配置模型 ID。", "Optional configured model ID."),
                        parameter("reasoning_effort", "可选思考强度：none、minimal、low、medium、high、xhigh、max、ultra；模型需支持。", "Optional reasoning effort: none, minimal, low, medium, high, xhigh, max, ultra; requires model support."),
                    ),
                ),
                ToolPrompt(
                    name = "send_message",
                    description = text(
                        "投递消息。运行中的代理在下一消息边界接收；空闲代理只入队，不会启动新轮。",
                        "Deliver a message at the next input boundary of an active agent. Queue messages for idle agents without starting a turn.",
                    ),
                    parametersStructured = listOf(target, message),
                ),
                ToolPrompt(
                    name = "followup_task",
                    description = text(
                        "给现有代理继续分配任务。空闲时启动一轮，运行中在消息边界投递；不能指向根代理。",
                        "Give an existing agent another task. Start a turn if idle; deliver at an input boundary if running. Cannot target root.",
                    ),
                    parametersStructured = listOf(target, message),
                ),
                ToolPrompt(
                    name = "interrupt_agent",
                    description = text(
                        "中断目标代理当前轮并返回先前状态。代理身份和对话保留，可继续分配任务。不能中断根代理或自己。",
                        "Interrupt the target's current turn and return its previous status. Retain identity and conversation for follow-up tasks. Cannot target root or self.",
                    ),
                    parametersStructured = listOf(target),
                ),
                ToolPrompt(
                    name = "list_agents",
                    description = text("列出当前根会话中的代理及状态。", "List agents and their status in the current root conversation."),
                    parametersStructured = listOf(parameter("path_prefix", "可选完整或当前代理下的相对路径前缀，按路径段筛选。", "Optional canonical or caller-relative path prefix, matched by path segment.")),
                ),
                ToolPrompt(
                    name = "wait_agent",
                    description = text(
                        "等待邮箱消息、完成通知或新的用户输入。消息通过上下文投递；此工具只报告等待结果。",
                        "Wait for mailbox activity, completion notifications or new user input. Messages arrive through context; this tool only reports the wait outcome.",
                    ),
                    parametersStructured = listOf(parameter("timeout_ms", "等待毫秒数，默认 30000，最少 10000，最多 3600000。", "Timeout in milliseconds: default 30000, minimum 10000, maximum 3600000.")),
                ),
            ),
        )
    }
}

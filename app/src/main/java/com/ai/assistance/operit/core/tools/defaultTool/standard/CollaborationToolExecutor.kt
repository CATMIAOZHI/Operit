package com.ai.assistance.operit.core.tools.defaultTool.standard

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.agent.collaboration.AgentFork
import com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
import com.ai.assistance.operit.core.agent.collaboration.CollaborationToolPolicy
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class CollaborationToolExecutor(context: Context) : ToolExecutor {
    private val appContext = context.applicationContext
    private val coordinator get() = CollaborationCoordinator.getInstance(appContext)

    override fun invoke(tool: AITool): ToolResult {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        return runBlocking(Dispatchers.IO) { execute(tool, runtime) }
    }

    override fun invokeAndStream(tool: AITool): Flow<ToolResult> = flow {
        val runtime = ToolExecutionManager.currentToolRuntimeContext()
        emit(execute(tool, runtime))
    }

    private suspend fun execute(tool: AITool, runtime: ToolExecutionManager.ToolRuntimeContext?): ToolResult {
        fun optional(name: String) = tool.parameters.firstOrNull { it.name == name }?.value?.trim()?.takeIf { it.isNotEmpty() }
        fun required(name: String) = requireNotNull(optional(name)) { "$name is required" }
        return try {
            val chatId = requireNotNull(runtime?.callerChatId?.takeIf { it.isNotBlank() }) { "Collaboration requires a caller chat" }
            require(CollaborationToolPolicy.visibility(appContext, chatId, runtime.isSubagent)[tool.name] == true) {
                "This tool is not available in the caller's subagent version"
            }
            val result = when (tool.name) {
                "spawn_agent" -> {
                    val model = optional("model")
                    val effort = optional("reasoning_effort")
                    require(effort == null || effort in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")) {
                        "Invalid reasoning_effort"
                    }
                    if (model != null) {
                        val manager = ModelConfigManager(appContext)
                        manager.initializeIfNeeded()
                        require(manager.getAllConfigSummaries().any { it.id == model }) { "Unknown configured model ID" }
                    }
                    val agent = coordinator.spawn(
                        chatId, required("task_name"), required("message"), optional("agent_type") ?: "general",
                        AgentFork.parse(optional("fork_turns")), model ?: runtime.parentModelConfigId,
                        if (model == null) runtime.parentModelIndex else 0, runtime.callId,
                        inheritModel = model != null ||
                            (AgentFork.parse(optional("fork_turns")) == AgentFork.All && optional("agent_type") == null),
                        reasoningEffort = effort,
                    )
                    buildJsonObject { put("task_name", agent.path); put("agent_id", agent.chatId) }
                }
                "send_message", "followup_task" -> {
                    coordinator.send(chatId, required("target"), required("message"), tool.name == "followup_task")
                    buildJsonObject { put("accepted", true) }
                }
                "interrupt_agent" -> buildJsonObject {
                    put("previous_status", coordinator.interrupt(chatId, required("target")).name.lowercase())
                }
                "list_agents" -> buildJsonObject {
                    put("agents", buildJsonArray {
                        coordinator.list(chatId, optional("path_prefix")).forEach {
                            add(buildJsonObject {
                                put("task_name", it.path)
                                put("agent_id", it.chatId)
                                put("status", it.status.name.lowercase())
                                it.lastError?.let { error -> put("error", error) }
                            })
                        }
                    })
                }
                "wait_agent" -> {
                    val timeout = optional("timeout_ms")?.let {
                        requireNotNull(it.toLongOrNull()) { "timeout_ms must be an integer" }
                    } ?: 30_000L
                    val timedOut = coordinator.wait(chatId, timeout)
                    buildJsonObject {
                        put("timed_out", timedOut)
                        put("message", if (timedOut) "Wait timed out." else "Wait completed.")
                    }
                }
                else -> error("Unknown collaboration tool")
            }
            ToolResult(toolName = tool.name, success = true, result = StringResultData(result.toString()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult(toolName = tool.name, success = false, result = StringResultData(""), error = error.message ?: "Collaboration failed")
        }
    }
}

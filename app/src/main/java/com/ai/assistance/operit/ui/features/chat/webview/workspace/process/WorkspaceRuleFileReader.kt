package com.ai.assistance.operit.ui.features.chat.webview.workspace.process

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.FileContentData
import com.ai.assistance.operit.core.tools.DirectoryListingData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WorkspaceRuleFileReader {
    private val WORKSPACE_RULE_FILE_NAMES = listOf("AGENT.md", "AGENTS.md")

    data class WorkspaceRuleFile(val name: String, val content: String)
    data class ReadResult(val file: WorkspaceRuleFile?, val reliable: Boolean)

    suspend fun readWorkspaceRootRuleFile(
        context: Context,
        workspacePath: String?,
        workspaceEnv: String? = null
    ): WorkspaceRuleFile? = readWorkspaceRootRuleFileWithStatus(context, workspacePath, workspaceEnv).file

    suspend fun readWorkspaceRootRuleFileWithStatus(
        context: Context,
        workspacePath: String?,
        workspaceEnv: String? = null
    ): ReadResult = withContext(Dispatchers.IO) {
        if (workspacePath.isNullOrBlank()) {
            return@withContext ReadResult(null, true)
        }

        val toolHandler = AIToolHandler.getInstance(context)
        val listing = toolHandler.executeTool(AITool(name = "list_files", parameters = buildList {
            add(ToolParameter("path", workspacePath))
            if (!workspaceEnv.isNullOrBlank()) add(ToolParameter("environment", workspaceEnv))
        }))
        val entries = (listing.result as? DirectoryListingData)?.entries
            ?.takeIf { listing.success }
        var reliable = entries != null
        for (fileName in WORKSPACE_RULE_FILE_NAMES) {
            if (entries != null && entries.none { it.name == fileName && !it.isDirectory }) continue
            val result =
                toolHandler.executeTool(
                    AITool(
                        name = "read_file_full",
                        parameters = buildList {
                            add(ToolParameter("path", buildWorkspaceChildPath(workspacePath, fileName)))
                            add(ToolParameter("text_only", "true"))
                            if (!workspaceEnv.isNullOrBlank()) {
                                add(ToolParameter("environment", workspaceEnv))
                            }
                        }
                    )
                )
            val content = (result.result as? FileContentData)?.content?.trim().orEmpty()
            if (!result.success || result.result !is FileContentData) reliable = false
            if (result.success && content.isNotBlank()) {
                return@withContext ReadResult(WorkspaceRuleFile(name = fileName, content = content), reliable)
            }
        }

        ReadResult(null, reliable)
    }

    private fun buildWorkspaceChildPath(workspacePath: String, childName: String): String {
        val normalizedRoot = workspacePath.trim().ifBlank { "/" }
        return if (normalizedRoot == "/") {
            "/$childName"
        } else {
            normalizedRoot.trimEnd('/') + "/$childName"
        }
    }
}

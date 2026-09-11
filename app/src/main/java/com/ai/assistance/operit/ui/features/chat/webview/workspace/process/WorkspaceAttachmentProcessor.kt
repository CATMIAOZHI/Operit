package com.ai.assistance.operit.ui.features.chat.webview.workspace.process

import android.content.Context
import com.ai.assistance.operit.R

object WorkspaceAttachmentProcessor {
    fun generateWorkspaceAttachment(
        context: Context,
        workspaceEnv: String?,
    ): String {
        val workspaceTag = workspaceEnv?.trim().orEmpty()
        return buildString {
            appendLine(context.getString(R.string.workspace_attachment_attached))
            if (workspaceTag.isNotEmpty()) {
                appendLine(context.getString(R.string.workspace_attachment_environment, escapeText(workspaceTag)))
            }
        }.trim()
    }

    private fun escapeText(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

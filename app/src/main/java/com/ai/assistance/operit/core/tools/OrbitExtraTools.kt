package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult

/**
 * The one list of Orbit's newer tools. Registration, the Orbit tool category, visibility, the
 * role-card gate and background task runs all read it, so a new tool is added in one place.
 */
internal object OrbitExtraTools {
    /**
     * [inTasks]: background task runs (the root task chat) get it too, not only the Orbit chat.
     * [stream]: a suspend version run in the tool call's coroutine, for a tool that may wait long.
     * [inSubagents]: the Orbit's subagents get it too. They carry the Orbit's prompt, so they know
     * every Orbit tool; the rest are left to the main agent (see OrbitTools.subagentDenial).
     */
    data class Spec(
        val prompt: ToolPrompt,
        val inTasks: Boolean,
        val run: (Context, AITool) -> ToolResult,
        val stream: (suspend (Context, AITool) -> ToolResult)? = null,
        val inSubagents: Boolean = false,
    )

    val specs: List<Spec> by lazy {
        listOf(
            Spec(OrbitFileTools.prompt, true, OrbitFileTools::execute, OrbitFileTools::run),
            Spec(OrbitLogTools.prompt, true, OrbitLogTools::execute),
            Spec(OrbitVoiceTools.prompt, true, OrbitVoiceTools::execute, OrbitVoiceTools::run),
            Spec(OrbitGoalNoteTools.prompt, true, OrbitGoalNoteTools::execute),
            Spec(OrbitDeviceTools.prompt, true, OrbitDeviceTools::execute),
            Spec(OrbitChannelTools.prompt, true, OrbitChannelTools::execute),
            Spec(OrbitChatTools.chatPrompt, false, OrbitChatTools::executeChat),
            Spec(OrbitChatTools.reactPrompt, false, OrbitChatTools::executeReact),
            Spec(OrbitChatTools.nothingToDoPrompt, false, OrbitChatTools::executeNothingToDo),
            Spec(OrbitProactivityTools.prompt, false, OrbitProactivityTools::execute),
            Spec(OrbitVaultTools.prompt, true, OrbitVaultTools::execute),
            Spec(OrbitAvatarTools.prompt, false, OrbitAvatarTools::execute),
            Spec(OrbitApprovalTools.prompt, false, OrbitApprovalTools::execute),
            Spec(OrbitPackageTools.prompt, false, OrbitPackageTools::execute),
            Spec(OrbitModelTools.prompt, false, OrbitModelTools::execute),
            Spec(OrbitComponentTools.prompt, false, OrbitComponentTools::execute),
            Spec(OrbitTerminalSourceTools.prompt, true, OrbitTerminalSourceTools::execute),
            Spec(OrbitMcpTools.prompt, true, OrbitMcpTools::execute, OrbitMcpTools::run),
            Spec(OrbitSettingsTools.prompt, false, OrbitSettingsTools::execute),
            Spec(OrbitUsageTools.prompt, true, OrbitUsageTools::execute),
            // Copying chapters and writing comments are handed to subagents.
            Spec(OrbitReadingTools.prompt, true, OrbitReadingTools::execute, OrbitReadingTools::run, inSubagents = true),
            Spec(OrbitPermissionTools.prompt, false, OrbitPermissionTools::execute),
            Spec(OrbitBrowserHandoffTools.prompt, false, OrbitBrowserHandoffTools::execute),
            Spec(OrbitBrowserShowTools.prompt, false, OrbitBrowserShowTools::execute),
            Spec(OrbitOptionsTools.prompt, false, OrbitOptionsTools::execute),
            Spec(OrbitPrivacyTools.originPrompt, true, OrbitPrivacyTools::executeOrigin),
            Spec(OrbitPrivacyTools.presendPrompt, true, OrbitPrivacyTools::executePresend),
            // Mail and calendar: background tasks and subagents may read them; sending and changing
            // are only offered where the user can confirm them on a card, the Orbit's own chats.
            Spec(OrbitMailTools.prompt, true, OrbitMailTools::execute, OrbitMailTools::run, inSubagents = true),
            Spec(OrbitMailTools.sendPrompt, false, OrbitMailTools::executeSend, OrbitMailTools::runSend),
            Spec(OrbitCalendarTools.prompt, true, OrbitCalendarTools::execute, OrbitCalendarTools::run, inSubagents = true),
            Spec(OrbitCalendarTools.changePrompt, false, OrbitCalendarTools::executeChange, OrbitCalendarTools::runChange),
        )
    }
    val prompts: List<ToolPrompt> get() = specs.map { it.prompt }
    val names: Set<String> by lazy { specs.map { it.prompt.name }.toSet() }
    val taskPrompts: List<ToolPrompt> get() = specs.filter { it.inTasks }.map { it.prompt }
    val taskNames: Set<String> by lazy { specs.filter { it.inTasks }.map { it.prompt.name }.toSet() }

    /** The connector tools (mail, calendar), shown only once the Orbit has a mailbox. */
    val mailNames = setOf(OrbitMailTools.MAIL, OrbitMailTools.SEND, OrbitCalendarTools.CALENDAR, OrbitCalendarTools.CHANGE)

    fun visibility(isOrbitChat: Boolean, isTaskRoot: Boolean, isOrbitSubagent: Boolean = false, hasMail: Boolean = true): Map<String, Boolean> =
        specs.associate {
            it.prompt.name to ((hasMail || it.prompt.name !in mailNames) &&
                (isOrbitChat || (it.inTasks && isTaskRoot) || (it.inSubagents && isOrbitSubagent)))
        }
}

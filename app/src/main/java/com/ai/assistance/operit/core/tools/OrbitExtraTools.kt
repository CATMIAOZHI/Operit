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
    /** [inTasks]: background task runs (the root task chat) get it too, not only the Orbit chat. */
    data class Spec(val prompt: ToolPrompt, val inTasks: Boolean, val run: (Context, AITool) -> ToolResult)

    val specs: List<Spec> by lazy {
        listOf(
            Spec(OrbitFileTools.prompt, true, OrbitFileTools::execute),
            Spec(OrbitVoiceTools.prompt, true, OrbitVoiceTools::execute),
            Spec(OrbitGoalNoteTools.prompt, true, OrbitGoalNoteTools::execute),
            Spec(OrbitDeviceTools.prompt, true, OrbitDeviceTools::execute),
            Spec(OrbitChatTools.chatPrompt, false, OrbitChatTools::executeChat),
            Spec(OrbitChatTools.reactPrompt, false, OrbitChatTools::executeReact),
            Spec(OrbitChatTools.nothingToDoPrompt, false, OrbitChatTools::executeNothingToDo),
            Spec(OrbitVaultTools.prompt, true, OrbitVaultTools::execute),
            Spec(OrbitApprovalTools.prompt, false, OrbitApprovalTools::execute),
            Spec(OrbitPrivacyTools.forgetPrompt, false, OrbitPrivacyTools::executeForget),
            Spec(OrbitPrivacyTools.originPrompt, true, OrbitPrivacyTools::executeOrigin),
            Spec(OrbitPrivacyTools.presendPrompt, true, OrbitPrivacyTools::executePresend),
        )
    }
    val prompts: List<ToolPrompt> get() = specs.map { it.prompt }
    val names: Set<String> by lazy { specs.map { it.prompt.name }.toSet() }
    val taskPrompts: List<ToolPrompt> get() = specs.filter { it.inTasks }.map { it.prompt }
    val taskNames: Set<String> by lazy { specs.filter { it.inTasks }.map { it.prompt.name }.toSet() }

    fun visibility(isOrbitChat: Boolean, isTaskRoot: Boolean): Map<String, Boolean> =
        specs.associate { it.prompt.name to (isOrbitChat || (it.inTasks && isTaskRoot)) }
}

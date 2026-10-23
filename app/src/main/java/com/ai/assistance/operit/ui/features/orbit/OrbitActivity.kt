package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.collaboration.CollaborationCoordinator
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.features.orbit.OrbitTaskRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * What the Orbit is doing right now, for the line under its name, like Muse's "working": a tool in
 * use in plain words, or just "working" while it, one of its helpers or one of its background tasks
 * runs, in the main window or the floating one. Null while it rests. [currentChatId]'s own tool
 * comes first when that chat is the one running.
 */
@Composable
internal fun orbitActivity(orbit: OrbitProfile, currentChatId: String?): String? {
    val context = LocalContext.current.applicationContext
    val talkIds = orbit.talkChatIds
    val orbitId = orbit.id
    val label: State<Int?> = remember(talkIds, orbitId, currentChatId) {
        val holder = ChatRuntimeHolder.getInstance(context)
        val main = holder.getCore(ChatRuntimeSlot.MAIN)
        val floating = holder.getCore(ChatRuntimeSlot.FLOATING)
        // Taken on the flow's own thread below: the first use reads the subagent store from disk.
        val coordinator by lazy { CollaborationCoordinator.getInstance(context) }
        combine(
            main.activeStreamingChatIds, main.inputProcessingStateByChatId,
            floating.activeStreamingChatIds, floating.inputProcessingStateByChatId,
            OrbitTaskRunner.runningChats,
        ) { mainStreaming, mainStates, floatingStreaming, floatingStates, taskChats ->
            val tasks = taskChats.filterValues { it == orbitId }.keys
            val roots = talkIds.toSet() + tasks
            // Each window keeps old states of chats it once ran; only the window running a chat speaks for it.
            val states = floatingStates.filterKeys { it in floatingStreaming } + mainStates.filterKeys { it in mainStreaming }
            val mine = (mainStreaming + floatingStreaming).distinct()
                .filter { it in roots || coordinator.rootChatOf(it) in roots }
            if (mine.isEmpty() && tasks.isEmpty()) return@combine null
            val ordered = listOfNotNull(currentChatId?.takeIf { it in mine }) + mine
            ordered.firstNotNullOfOrNull { toolLabel(states[it]) } ?: R.string.orbit_activity_working
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }.collectAsState(initial = null)
    return label.value?.let { stringResource(it) }
}

private fun toolLabel(state: InputProcessingState?): Int? {
    val tool = when (state) {
        is InputProcessingState.ExecutingTool -> state.toolName
        is InputProcessingState.ToolProgress -> state.toolName
        is InputProcessingState.ProcessingToolResult -> state.toolName
        else -> return null
    }.substringAfterLast(':').lowercase()
    return when {
        "browser" in tool || "web_session" in tool || tool == "visit_web" -> R.string.orbit_activity_browsing
        "search" in tool -> R.string.orbit_activity_searching
        "file" in tool -> R.string.orbit_activity_files
        else -> R.string.orbit_activity_tool
    }
}

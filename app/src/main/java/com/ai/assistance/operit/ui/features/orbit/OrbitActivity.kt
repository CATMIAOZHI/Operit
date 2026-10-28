package com.ai.assistance.operit.ui.features.orbit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
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
import com.ai.assistance.operit.pet.PetActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

internal data class OrbitLiveActivity(
    val label: Int? = null,
    val pet: PetActivity = PetActivity.IDLE,
)

/** The header, profile and pet avatar share one observer for this Orbit's runs. */
internal val LocalOrbitActivity = compositionLocalOf<State<OrbitLiveActivity>> {
    mutableStateOf(OrbitLiveActivity())
}

@Composable
internal fun orbitActivity(): String? =
    LocalOrbitActivity.current.value.label?.let { stringResource(it) }

/** Only active runs belonging to this Orbit can drive its avatar and status. */
@Composable
internal fun rememberOrbitActivity(orbit: OrbitProfile, currentChatId: String?): State<OrbitLiveActivity> {
    val context = LocalContext.current.applicationContext
    val talkIds = orbit.talkChatIds
    val orbitId = orbit.id
    return remember(talkIds, orbitId, currentChatId) {
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
            // A chat summarizing its history before a send runs no turn yet, but Orbit is busy with it.
            // Each window is asked on its own: an old idle state in one must not hide the other's summary.
            fun summarizingIn(states: Map<String, InputProcessingState>) =
                states.filter { (id, state) -> state is InputProcessingState.Summarizing && id in roots }
            val summarizing = summarizingIn(floatingStates) + summarizingIn(mainStates)
            val states = summarizing + floatingStates.filterKeys { it in floatingStreaming } + mainStates.filterKeys { it in mainStreaming }
            val mine = (mainStreaming + floatingStreaming + summarizing.keys).distinct()
                .filter { it in roots || coordinator.rootChatOf(it) in roots }
            val ordered = listOfNotNull(currentChatId?.takeIf { it in mine }) + mine
            orbitLiveActivity(ordered.map { states[it] }, tasks.isNotEmpty())
        }.distinctUntilChanged().flowOn(Dispatchers.Default)
    }.collectAsStateWithLifecycle(initialValue = OrbitLiveActivity())
}

internal fun orbitLiveActivity(states: List<InputProcessingState?>, hasBackgroundTask: Boolean): OrbitLiveActivity {
    if (states.isEmpty() && !hasBackgroundTask) return OrbitLiveActivity()
    val tool = states.firstNotNullOfOrNull(::toolLabel)
    return when {
        tool != null -> OrbitLiveActivity(tool, PetActivity.TOOL)
        states.any { it is InputProcessingState.Summarizing } ->
            OrbitLiveActivity(R.string.orbit_activity_compacting, PetActivity.SUMMARIZING)
        else -> OrbitLiveActivity(R.string.orbit_activity_working, PetActivity.THINKING)
    }
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

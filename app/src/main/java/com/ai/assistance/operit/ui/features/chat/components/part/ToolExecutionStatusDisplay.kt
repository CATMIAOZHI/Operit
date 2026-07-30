package com.ai.assistance.operit.ui.features.chat.components.part

import android.os.SystemClock
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.core.agent.SubagentCoordinator
import com.ai.assistance.operit.core.tools.ToolExecutionTimingKey
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.ToolExecutionTimingSnapshot
import com.ai.assistance.operit.data.model.InputProcessingState
import com.ai.assistance.operit.data.model.SubagentRunStatus
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.repository.SubagentRunRepository
import com.ai.assistance.operit.util.ChatMarkupRegex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

data class PersistedToolExecution(
    val callId: String?,
    val toolName: String,
    val state: ToolExecutionState,
    val durationMs: Long?,
    val success: Boolean,
    val resultText: String,
)

internal fun parsePersistedToolExecutions(content: String): Map<Int, PersistedToolExecution> {
    if (content.isBlank()) return emptyMap()

    return buildMap {
        ChatMarkupRegex.toolResultTagWithAttrs.findAll(content).forEach { match ->
            val attrs = match.groupValues[2]
            if (readXmlAttribute(attrs, "final") != "true") return@forEach
            val invocationIndex =
                readXmlAttribute(attrs, "invocation_index")?.toIntOrNull() ?: return@forEach
            val state =
                readXmlAttribute(attrs, "execution_state")
                    ?.uppercase()
                    ?.let { runCatching { ToolExecutionState.valueOf(it) }.getOrNull() }
                    ?: ToolExecutionState.COMPLETED
            val durationMs = readXmlAttribute(attrs, "duration_ms")?.toLongOrNull()
            val success = readXmlAttribute(attrs, "status").equals("success", ignoreCase = true)
            val toolName = readXmlAttribute(attrs, "name").orEmpty()
            val callId = readXmlAttribute(attrs, "call_id")
            val body = match.groupValues[3]
            val resultText =
                ChatMarkupRegex.contentTag.find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            put(
                invocationIndex,
                PersistedToolExecution(
                    callId = callId,
                    toolName = toolName,
                    state = state,
                    durationMs = durationMs,
                    success = success,
                    resultText = resultText,
                ),
            )
        }
    }
}

private fun readXmlAttribute(attrs: String, name: String): String? =
    Regex("""\b${Regex.escape(name)}="([^"]*)"""", RegexOption.IGNORE_CASE)
        .find(attrs)
        ?.groupValues
        ?.getOrNull(1)

@Composable
internal fun ToolExecutionStatusDisplay(
    timingScopeId: String?,
    invocationIndex: Int,
    persistedExecution: PersistedToolExecution?,
    requestedToolName: String? = null,
    requestedSubagentName: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timings by ToolExecutionTimingRepository.timings.collectAsState()
    val liveExecution =
        timingScopeId
            ?.let { scopeId -> timings[ToolExecutionTimingKey(scopeId, invocationIndex)] }
    val state = liveExecution?.state ?: persistedExecution?.state ?: return
    val toolName =
        requestedToolName
            ?: liveExecution?.toolName
            ?: persistedExecution?.toolName
            ?: return

    if (toolName == "task") {
        val success = liveExecution?.success ?: persistedExecution?.success ?: false
        SubagentTaskStatusDisplay(
            callId = liveExecution?.callId ?: persistedExecution?.callId,
            fallbackState = state,
            fallbackStartedAtElapsedMs = liveExecution?.startedAtElapsedMs,
            fallbackDurationMs = liveExecution?.durationMs ?: persistedExecution?.durationMs,
            executionSuccess = success,
            executionResultText =
                resolveResultText(liveExecution, persistedExecution, success),
            requestedSubagentName = requestedSubagentName,
            modifier = modifier,
        )
        return
    }

    var currentElapsedMs by remember(liveExecution?.startedAtElapsedMs) {
        mutableLongStateOf(resolveElapsedMs(liveExecution))
    }
    LaunchedEffect(liveExecution?.state, liveExecution?.startedAtElapsedMs) {
        while (liveExecution?.state == ToolExecutionState.RUNNING) {
            currentElapsedMs = resolveElapsedMs(liveExecution)
            delay(100L)
        }
    }

    when (state) {
        ToolExecutionState.WAITING_AUTHORIZATION -> {
            ToolPendingStatusRow(
                text = stringResource(R.string.tool_waiting_authorization),
                modifier = modifier,
            )
        }
        ToolExecutionState.WAITING_EXECUTION -> {
            ToolPendingStatusRow(
                text = stringResource(R.string.tool_waiting_execution),
                modifier = modifier,
            )
        }
        ToolExecutionState.RUNNING -> {
            ToolPendingStatusRow(
                text =
                    stringResource(
                        R.string.tool_executing_duration,
                        formatToolExecutionDuration(context, currentElapsedMs),
                    ),
                modifier = modifier,
            )
        }
        ToolExecutionState.COMPLETED -> {
            val durationMs = liveExecution?.durationMs ?: persistedExecution?.durationMs ?: 0L
            val success = liveExecution?.success ?: persistedExecution?.success ?: false
            val resultText =
                resolveResultText(liveExecution, persistedExecution, success)
            val durationText = formatToolExecutionDuration(context, durationMs)
            val fileDiff =
                parseFileDiffResult(
                    toolName = toolName,
                    isSuccess = success,
                    resultContent = resultText,
                )
            if (fileDiff != null) {
                FileDiffDisplay(
                    diff = fileDiff,
                    summaryPrefix = durationText,
                )
            } else {
                ToolResultDisplay(
                    toolName = toolName,
                    result = resultText,
                    isSuccess = success,
                    summaryPrefix = durationText,
                    modifier = modifier,
                )
            }
        }
        ToolExecutionState.NOT_EXECUTED -> {
            val success = false
            val resultText = resolveResultText(liveExecution, persistedExecution, success)
            ToolResultDisplay(
                toolName = liveExecution?.toolName ?: persistedExecution?.toolName.orEmpty(),
                result = resultText,
                isSuccess = false,
                summaryPrefix = stringResource(R.string.tool_not_executed),
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ToolPendingStatusRow(
    text: String,
    modifier: Modifier,
    isSuccess: Boolean = true,
    showStatusIcon: Boolean = false,
    onClick: (() -> Unit)? = null,
    onStopClick: (() -> Unit)? = null,
) {
    CanvasToolResultRow(
        summary = text,
        isSuccess = isSuccess,
        semanticDescription = text,
        modifier = modifier,
        showStatusIcon = showStatusIcon,
        onClick = onClick,
        onStopClick = onStopClick,
        stopDescription = stringResource(R.string.subagent_stop_task),
    )
}

@Composable
private fun SubagentTaskStatusDisplay(
    callId: String?,
    fallbackState: ToolExecutionState,
    fallbackStartedAtElapsedMs: Long?,
    fallbackDurationMs: Long?,
    executionSuccess: Boolean,
    executionResultText: String,
    requestedSubagentName: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember(context) { SubagentRunRepository.getInstance(context) }
    val chatCore =
        remember(context) {
            ChatRuntimeHolder.getInstance(context.applicationContext)
                .getCore(ChatRuntimeSlot.MAIN)
        }
    val parentChatId by chatCore.currentChatId.collectAsState()
    val runFlow =
        remember(parentChatId, callId) {
            if (parentChatId.isNullOrBlank() || callId.isNullOrBlank()) {
                flowOf(null)
            } else {
                repository.observeByParentToolCallId(requireNotNull(parentChatId), callId)
            }
        }
    val run by runFlow.collectAsState(initial = null)

    if (run == null) {
        if (
            fallbackState == ToolExecutionState.COMPLETED ||
                fallbackState == ToolExecutionState.NOT_EXECUTED
        ) {
            ToolResultDisplay(
                toolName = requestedSubagentName?.ifBlank { "task" } ?: "task",
                result = extractSubagentTaskResult(executionResultText),
                isSuccess = executionSuccess,
                summaryPrefix =
                    fallbackDurationMs?.let {
                        formatToolExecutionDuration(context, it)
                    },
                modifier = modifier,
            )
            return
        }
        val fallbackText =
            when (fallbackState) {
                ToolExecutionState.WAITING_AUTHORIZATION ->
                    stringResource(R.string.tool_waiting_authorization)
                ToolExecutionState.WAITING_EXECUTION ->
                    stringResource(R.string.tool_waiting_execution)
                ToolExecutionState.RUNNING ->
                    stringResource(
                        R.string.tool_executing_duration,
                        formatToolExecutionDuration(
                            context,
                            fallbackStartedAtElapsedMs
                                ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                                ?: 0L,
                        ),
                    )
                ToolExecutionState.COMPLETED -> stringResource(R.string.subagent_status_completed)
                ToolExecutionState.NOT_EXECUTED -> stringResource(R.string.tool_not_executed)
            }
        val displayText =
            requestedSubagentName
                ?.takeIf { it.isNotBlank() }
                ?.let { agentName ->
                    stringResource(
                        R.string.subagent_status_with_agent,
                        agentName,
                        fallbackText,
                    )
                }
                ?: fallbackText
        ToolPendingStatusRow(
            text = displayText,
            modifier = modifier,
            isSuccess = fallbackState != ToolExecutionState.NOT_EXECUTED,
            showStatusIcon =
                fallbackState == ToolExecutionState.COMPLETED ||
                    fallbackState == ToolExecutionState.NOT_EXECUTED,
        )
        return
    }

    val resolvedRun = requireNotNull(run)
    val parentRunsFlow =
        remember(resolvedRun.parentChatId) {
            repository.observeByParentChatId(resolvedRun.parentChatId)
        }
    val parentRuns by parentRunsFlow.collectAsState(initial = emptyList())
    val processingStates by chatCore.inputProcessingStateByChatId.collectAsState()
    val lastToolNames by chatCore.lastToolNameByChatId.collectAsState()
    val lastTurnToolInvocationCounts by
        chatCore.lastTurnToolInvocationCountByChatId.collectAsState()
    val childProcessingState = processingStates[resolvedRun.childChatId]
    val status =
        runCatching { SubagentRunStatus.valueOf(resolvedRun.status) }
            .getOrDefault(SubagentRunStatus.FAILED)

    var nowMs by remember(resolvedRun.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status, resolvedRun.startedAt, resolvedRun.completedAt) {
        while (
            status == SubagentRunStatus.CREATED ||
                status == SubagentRunStatus.QUEUED ||
                status == SubagentRunStatus.RUNNING
        ) {
            nowMs = System.currentTimeMillis()
            delay(100L)
        }
    }
    val startMs = resolvedRun.startedAt ?: resolvedRun.createdAt
    val endMs = resolvedRun.completedAt ?: nowMs
    val durationText =
        formatToolExecutionDuration(context, (endMs - startMs).coerceAtLeast(0L))
    val queuePosition =
        parentRuns
            .asSequence()
            .filter { it.status == SubagentRunStatus.QUEUED.name }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .indexOfFirst { it.id == resolvedRun.id }
            .let { if (it >= 0) it + 1 else 1 }
    val currentTool =
        resolveSubagentDisplayedTool(
            childProcessingState = childProcessingState,
            lastToolName = lastToolNames[resolvedRun.childChatId],
        )
    val toolInvocationCount =
        maxOf(
            lastTurnToolInvocationCounts[resolvedRun.childChatId]?.coerceAtLeast(0) ?: 0,
            resolvedRun.toolInvocationCount.coerceAtLeast(0),
        )
    val baseStatusText =
        when (status) {
            SubagentRunStatus.CREATED -> stringResource(R.string.subagent_status_creating)
            SubagentRunStatus.QUEUED ->
                stringResource(R.string.subagent_status_queued, queuePosition)
            SubagentRunStatus.RUNNING ->
                if (currentTool.isNullOrBlank()) {
                    stringResource(R.string.subagent_status_thinking)
                } else {
                    stringResource(R.string.subagent_status_calling_tool, currentTool)
                }
            SubagentRunStatus.COMPLETED ->
                if (toolInvocationCount > 0) {
                    stringResource(
                        R.string.subagent_status_completed_with_tool_count,
                        toolInvocationCount,
                    )
                } else {
                    stringResource(R.string.subagent_status_completed)
                }
            SubagentRunStatus.FAILED,
            SubagentRunStatus.INTERRUPTED -> stringResource(R.string.subagent_status_error)
            SubagentRunStatus.CANCELLED -> stringResource(R.string.subagent_status_cancelled)
        }
    val statusText =
        stringResource(
            R.string.subagent_status_with_agent,
            resolvedRun.agentProfileId.ifBlank { requestedSubagentName ?: "subagent" },
            baseStatusText,
        )
    val isSuccess =
        status != SubagentRunStatus.FAILED &&
            status != SubagentRunStatus.INTERRUPTED &&
            status != SubagentRunStatus.CANCELLED
    val isTerminal =
        status == SubagentRunStatus.COMPLETED ||
            status == SubagentRunStatus.FAILED ||
            status == SubagentRunStatus.INTERRUPTED ||
            status == SubagentRunStatus.CANCELLED
    val terminalResult =
        extractSubagentTaskResult(executionResultText)
            .ifBlank { stringResource(R.string.subagent_result_empty) }
    var showResultDialog by remember(resolvedRun.id) { androidx.compose.runtime.mutableStateOf(false) }

    if (showResultDialog && isTerminal) {
        ToolResultDetailDialog(
            toolName = "task",
            result = terminalResult,
            isSuccess = isSuccess,
            titleOverride =
                stringResource(
                    R.string.subagent_result_title,
                    resolvedRun.title,
                ),
            metadata = "$durationText · $statusText",
            onDismiss = { showResultDialog = false },
            onCopy = {
                clipboardManager.setText(AnnotatedString(terminalResult))
            },
            primaryActionLabel = stringResource(R.string.subagent_open_conversation),
            onPrimaryAction = {
                showResultDialog = false
                chatCore.switchChat(
                    resolvedRun.childChatId,
                    scrollToBottom = false,
                )
            },
        )
    }

    val rowText =
        if (isTerminal) {
            listOf(
                    "$durationText  $statusText",
                    terminalResult.toSingleLineResultSummary(),
                )
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        } else {
            "$durationText  $statusText"
        }
    ToolPendingStatusRow(
        text = rowText,
        modifier = modifier,
        isSuccess = isSuccess,
        showStatusIcon =
            status == SubagentRunStatus.COMPLETED ||
                status == SubagentRunStatus.FAILED ||
                status == SubagentRunStatus.INTERRUPTED ||
                status == SubagentRunStatus.CANCELLED,
        onClick = {
            if (isTerminal) {
                showResultDialog = true
            } else {
                chatCore.switchChat(
                    resolvedRun.childChatId,
                    scrollToBottom = false,
                )
            }
        },
        onStopClick =
            if (
                status == SubagentRunStatus.CREATED ||
                    status == SubagentRunStatus.QUEUED ||
                    status == SubagentRunStatus.RUNNING
            ) {
                {
                    coroutineScope.launch {
                        SubagentCoordinator.getInstance(context).cancelTask(resolvedRun.id)
                    }
                }
            } else {
                null
            },
    )
}

internal fun extractSubagentTaskResult(rawResult: String): String {
    val result =
        Regex("""<task_result>([\s\S]*?)</task_result>""", RegexOption.IGNORE_CASE)
            .find(rawResult)
            ?.groupValues
            ?.getOrNull(1)
        ?: Regex("""<task_error>([\s\S]*?)</task_error>""", RegexOption.IGNORE_CASE)
            .find(rawResult)
            ?.groupValues
            ?.getOrNull(1)
        ?: rawResult

    return result
        .trim()
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

private fun String.toSingleLineResultSummary(): String =
    replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()

internal fun resolveSubagentDisplayedTool(
    childProcessingState: InputProcessingState?,
    lastToolName: String?,
): String? =
    when (childProcessingState) {
        is InputProcessingState.ExecutingTool -> childProcessingState.toolName
        is InputProcessingState.ToolProgress -> childProcessingState.toolName
        is InputProcessingState.ProcessingToolResult -> childProcessingState.toolName
        else -> lastToolName
    }?.takeIf { it.isNotBlank() }

private fun resolveElapsedMs(snapshot: ToolExecutionTimingSnapshot?): Long {
    val startedAt = snapshot?.startedAtElapsedMs ?: return 0L
    return (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
}

private fun resolveResultText(
    liveExecution: ToolExecutionTimingSnapshot?,
    persistedExecution: PersistedToolExecution?,
    success: Boolean,
): String {
    val raw =
        if (liveExecution != null) {
            if (success) liveExecution.resultText else liveExecution.errorText.ifBlank {
                liveExecution.resultText
            }
        } else {
            persistedExecution?.resultText.orEmpty()
        }
    if (success) return raw

    return ChatMarkupRegex.errorTag.find(raw)?.groupValues?.getOrNull(1)?.trim() ?: raw
}

internal fun formatToolExecutionDuration(context: Context, durationMs: Long): String {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    return when {
        safeDurationMs < 1_000L ->
            context.getString(R.string.tool_duration_milliseconds, safeDurationMs)
        safeDurationMs < 60_000L ->
            context.getString(R.string.tool_duration_seconds, safeDurationMs / 1_000.0)
        else -> {
            val totalSeconds = safeDurationMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            context.getString(R.string.tool_duration_minutes_seconds, minutes, seconds)
        }
    }
}

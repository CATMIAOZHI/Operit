package com.ai.assistance.operit.ui.features.chat.components.part

import android.os.SystemClock
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.ToolExecutionTimingKey
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.ToolExecutionTimingSnapshot
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.util.ChatMarkupRegex
import kotlinx.coroutines.delay

data class PersistedToolExecution(
    val callId: String?,
    val toolName: String,
    val state: ToolExecutionState,
    val durationMs: Long?,
    val success: Boolean,
    val resultText: String,
)

private val FILE_EDIT_TOOL_NAMES = setOf("apply_file", "create_file", "edit_file")

internal fun parsePersistedToolExecutions(content: String): Map<Int, PersistedToolExecution> {
    if (content.isBlank()) return emptyMap()

    return buildMap {
        ChatMarkupRegex.toolResultTagWithAttrs.findAll(content).forEach { match ->
            val attrs = match.groupValues[2]
            val invocationIndex = readFinalInvocationIndex(attrs) ?: return@forEach
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

private fun readFinalInvocationIndex(attrs: String): Int? {
    if (!readXmlAttribute(attrs, "final").equals("true", ignoreCase = true)) return null
    return readXmlAttribute(attrs, "invocation_index")
        ?.toIntOrNull()
        ?.takeIf { it >= 0 }
}

internal fun shouldRenderStandaloneToolResult(content: String): Boolean {
    val match = ChatMarkupRegex.toolResultTagWithAttrs.matchEntire(content.trim()) ?: return true
    return readFinalInvocationIndex(match.groupValues[2]) == null
}

internal fun resolveLiveToolExecution(
    liveExecution: ToolExecutionTimingSnapshot?,
    persistedExecution: PersistedToolExecution?,
    allowUnmatchedLiveExecution: Boolean,
): ToolExecutionTimingSnapshot? {
    val live = liveExecution ?: return null
    val persistedCallId = persistedExecution?.callId?.takeIf { it.isNotBlank() }
    if (persistedCallId == null) {
        return live.takeIf { allowUnmatchedLiveExecution }
    }
    return live.takeIf { it.callId == persistedCallId }
}

@Composable
internal fun ToolExecutionStatusDisplay(
    timingScopeId: String?,
    invocationIndex: Int,
    persistedExecution: PersistedToolExecution?,
    allowUnmatchedLiveExecution: Boolean,
    enableDialogs: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timings by ToolExecutionTimingRepository.timings.collectAsState()
    val liveExecution =
        resolveLiveToolExecution(
            liveExecution =
                timingScopeId
                    ?.let { scopeId -> timings[ToolExecutionTimingKey(scopeId, invocationIndex)] },
            persistedExecution = persistedExecution,
            allowUnmatchedLiveExecution = allowUnmatchedLiveExecution,
        )
    val state = liveExecution?.state ?: persistedExecution?.state ?: return

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
            ToolExecutionResultDisplay(
                toolName = liveExecution?.toolName ?: persistedExecution?.toolName.orEmpty(),
                result = resultText,
                isSuccess = success,
                summaryPrefix = formatToolExecutionDuration(context, durationMs),
                enableDialog = enableDialogs,
                modifier = modifier,
            )
        }
        ToolExecutionState.NOT_EXECUTED -> {
            val success = false
            val resultText = resolveResultText(liveExecution, persistedExecution, success)
            ToolExecutionResultDisplay(
                toolName = liveExecution?.toolName ?: persistedExecution?.toolName.orEmpty(),
                result = resultText,
                isSuccess = false,
                summaryPrefix = stringResource(R.string.tool_not_executed),
                enableDialog = enableDialogs,
                modifier = modifier,
            )
        }
    }
}

@Composable
internal fun ToolExecutionResultDisplay(
    toolName: String,
    result: String,
    isSuccess: Boolean,
    modifier: Modifier = Modifier,
    summaryPrefix: String? = null,
    enableDialog: Boolean = true,
) {
    val fileDiff = remember(toolName, result, isSuccess) {
        parseFileDiffResult(toolName, result, isSuccess)
    }
    if (fileDiff != null) {
        FileDiffDisplay(
            diff = fileDiff,
            modifier = modifier,
            summaryPrefix = summaryPrefix,
            enableDialog = enableDialog,
        )
    } else {
        ToolResultDisplay(
            toolName = toolName,
            result = result,
            isSuccess = isSuccess,
            modifier = modifier,
            summaryPrefix = summaryPrefix,
            enableDialog = enableDialog,
        )
    }
}

internal fun parseFileDiffResult(
    toolName: String,
    result: String,
    isSuccess: Boolean,
): FileDiff? {
    if (!isSuccess || toolName !in FILE_EDIT_TOOL_NAMES) return null
    if (!result.contains("<file-diff")) return null

    val fileDiffBlock = Regex("""<file-diff\b[^>]*>[\s\S]*?</file-diff\s*>""")
        .find(result)
        ?.value
        ?: return null
    val path = Regex("""<file-diff\s+[^>]*path="([^"]+)"""")
        .find(fileDiffBlock)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val details = Regex("""\bdetails="([^"]*)"""")
        .find(fileDiffBlock)
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    val diffContent = Regex("""<!\[CDATA\[([\s\S]*?)]]>""")
        .find(fileDiffBlock)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?: return null
    return FileDiff(path = path, diffContent = diffContent, details = details)
}

@Composable
private fun ToolPendingStatusRow(
    text: String,
    modifier: Modifier,
) {
    CanvasToolResultRow(
        summary = text,
        isSuccess = true,
        semanticDescription = text,
        modifier = modifier,
        showStatusIcon = false,
    )
}

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
        if (persistedExecution != null) {
            persistedExecution.resultText
        } else if (liveExecution != null) {
            if (success) liveExecution.resultText else liveExecution.errorText.ifBlank {
                liveExecution.resultText
            }
        } else {
            ""
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

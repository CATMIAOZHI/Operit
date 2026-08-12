package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ToolExecutionTimingKey(
    val scopeId: String,
    val invocationIndex: Int,
)

data class ToolExecutionTimingSnapshot(
    val callId: String,
    val toolName: String,
    val state: ToolExecutionState,
    val startedAtElapsedMs: Long? = null,
    val durationMs: Long? = null,
    val success: Boolean? = null,
    val resultText: String = "",
    val errorText: String = "",
)

object ToolExecutionTimingRepository {
    private const val MAX_RETAINED_CALLS = 512
    internal const val MAX_RETAINED_TEXT_CHARS =
        ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS * 16

    private val mutableTimings =
        MutableStateFlow<Map<ToolExecutionTimingKey, ToolExecutionTimingSnapshot>>(emptyMap())

    val timings: StateFlow<Map<ToolExecutionTimingKey, ToolExecutionTimingSnapshot>> =
        mutableTimings.asStateFlow()

    fun get(scopeId: String?, invocationIndex: Int): ToolExecutionTimingSnapshot? {
        if (scopeId.isNullOrBlank() || invocationIndex < 0) return null
        return mutableTimings.value[ToolExecutionTimingKey(scopeId, invocationIndex)]
    }

    fun clearScope(scopeId: String?) {
        if (scopeId.isNullOrBlank()) return
        mutableTimings.update { current ->
            current.filterKeys { key -> key.scopeId != scopeId }
        }
    }

    fun register(scopeId: String?, invocation: ToolInvocation) {
        val callId = invocation.callId ?: return
        if (scopeId.isNullOrBlank() || invocation.invocationIndex < 0) return

        update(scopeId, invocation.invocationIndex) {
            ToolExecutionTimingSnapshot(
                callId = callId,
                toolName = invocation.tool.name,
                state = ToolExecutionState.WAITING_EXECUTION,
            )
        }
    }

    fun markWaitingAuthorization(scopeId: String?, invocation: ToolInvocation) {
        updateExisting(scopeId, invocation) {
            it.copy(state = ToolExecutionState.WAITING_AUTHORIZATION)
        }
    }

    fun markWaitingExecution(scopeId: String?, invocation: ToolInvocation) {
        updateExisting(scopeId, invocation) {
            it.copy(state = ToolExecutionState.WAITING_EXECUTION)
        }
    }

    fun markRunning(scopeId: String?, invocation: ToolInvocation, startedAtElapsedMs: Long) {
        updateExisting(scopeId, invocation) {
            it.copy(
                state = ToolExecutionState.RUNNING,
                startedAtElapsedMs = startedAtElapsedMs,
                durationMs = null,
            )
        }
    }

    fun markFinished(
        scopeId: String?,
        invocation: ToolInvocation,
        result: ToolResult,
        durationMs: Long?,
        state: ToolExecutionState,
    ) {
        updateExisting(scopeId, invocation) {
            it.copy(
                state = state,
                durationMs = durationMs,
                success = result.success,
                toolName = result.toolName.ifBlank { it.toolName },
                // 与持久化工具结果消息的上限保持一致：流式窗口内（工具边界快照使
                // persisted 结果不可用）UI 依赖 live 快照展示结果，若按更小的
                // MAX_TEXT_RESULT_LENGTH 截断，较大的 <file-diff> 块会被切断，
                // 导致 diff 解析失败而回退为普通工具结果展示。
                resultText =
                    result.result
                        .toString()
                        .take(ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS),
                errorText =
                    result.error
                        .orEmpty()
                        .take(ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS),
            )
        }
    }

    private fun updateExisting(
        scopeId: String?,
        invocation: ToolInvocation,
        transform: (ToolExecutionTimingSnapshot) -> ToolExecutionTimingSnapshot,
    ) {
        if (scopeId.isNullOrBlank() || invocation.invocationIndex < 0) return
        val key = ToolExecutionTimingKey(scopeId, invocation.invocationIndex)
        mutableTimings.update { current ->
            val existing = current[key] ?: return@update current
            enforceRetentionLimits(
                snapshots = current + (key to transform(existing)),
                protectedKey = key,
            )
        }
    }

    private fun update(
        scopeId: String,
        invocationIndex: Int,
        create: () -> ToolExecutionTimingSnapshot,
    ) {
        val key = ToolExecutionTimingKey(scopeId, invocationIndex)
        mutableTimings.update { current ->
            enforceRetentionLimits(current + (key to create()))
        }
    }

    /**
     * Keep timing metadata for recent calls, but cap the much larger live result payloads
     * independently. The newest completed result remains available for the persistence/rendering
     * handoff; older payloads are released first once the shared text budget is exhausted.
     */
    private fun enforceRetentionLimits(
        snapshots: Map<ToolExecutionTimingKey, ToolExecutionTimingSnapshot>,
        protectedKey: ToolExecutionTimingKey? = null,
    ): Map<ToolExecutionTimingKey, ToolExecutionTimingSnapshot> {
        val retained = LinkedHashMap<ToolExecutionTimingKey, ToolExecutionTimingSnapshot>()
        snapshots.entries
            .takeLast(MAX_RETAINED_CALLS)
            .forEach { (key, snapshot) -> retained[key] = snapshot }

        var retainedTextChars = retained.values.sumOf { it.resultText.length + it.errorText.length }
        if (retainedTextChars <= MAX_RETAINED_TEXT_CHARS) return retained

        val payloadEvictionOrder =
            retained.entries
                .toList()
                .sortedBy { (key, _) -> key == protectedKey }
        for ((key, snapshot) in payloadEvictionOrder) {
            if (retainedTextChars <= MAX_RETAINED_TEXT_CHARS) break
            val payloadChars = snapshot.resultText.length + snapshot.errorText.length
            if (payloadChars == 0) continue
            retained[key] = snapshot.copy(resultText = "", errorText = "")
            retainedTextChars -= payloadChars
        }
        return retained
    }
}

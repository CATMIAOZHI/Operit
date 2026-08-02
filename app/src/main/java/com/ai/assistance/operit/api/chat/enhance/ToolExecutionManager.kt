package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.agent.SubagentToolPolicy
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.AIToolHookDecision
import com.ai.assistance.operit.core.tools.PermissionReviewInternalTools
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.ToolExecutionLimits
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.ui.common.displays.MessageContentParser
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.PermissionReviewCircuitBreaker
import com.ai.assistance.operit.ui.permissions.PermissionReviewEventRepository
import com.ai.assistance.operit.ui.permissions.PermissionReviewStatus
import com.ai.assistance.operit.ui.permissions.ToolPermissionDecision
import com.ai.assistance.operit.ui.permissions.ToolPermissionDenialSource
import com.ai.assistance.operit.ui.permissions.resolveApprovalDecisionWithPermanentOverride
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.markdown.NestedMarkdownProcessor
import com.ai.assistance.operit.util.stream.plugins.StreamXmlPlugin
import com.ai.assistance.operit.util.stream.splitBy
import com.ai.assistance.operit.util.stream.stream
import com.ai.assistance.operit.util.LocaleUtils
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Utility class for managing tool executions */
object ToolExecutionManager {
    private const val TAG = "ToolExecutionManager"
    private const val PACKAGE_PROXY_TOOL_NAME = "package_proxy"
    private const val PACKAGE_CALLER_NAME_PARAM = "__operit_package_caller_name"
    private const val PACKAGE_CHAT_ID_PARAM = "__operit_package_chat_id"
    private const val PACKAGE_CALLER_CARD_ID_PARAM = "__operit_package_caller_card_id"
    private const val SUBAGENT_EXACT_REPEAT_REVIEW_THRESHOLD = 3
    private val toolRuntimeContextThreadLocal = ThreadLocal<ToolRuntimeContext?>()

    private data class ExactToolInstruction(
        val name: String,
        val orderedParameters: List<Pair<String, String>>,
    )

    /**
     * Per model turn state. Equality is deliberately exact: tool name, parameter order, names and
     * raw values must all match. Similar or normalized instructions never share a count.
     */
    class SubagentToolLoopGuard {
        private var previous: ExactToolInstruction? = null
        private var consecutiveCount: Int = 0

        private fun fingerprint(tool: AITool): ExactToolInstruction =
            ExactToolInstruction(
                name = tool.name,
                orderedParameters = tool.parameters.map { it.name to it.value },
            )

        @Synchronized
        internal fun record(tool: AITool): Int {
            val current = fingerprint(tool)
            if (current == previous) {
                consecutiveCount += 1
            } else {
                previous = current
                consecutiveCount = 1
            }
            return consecutiveCount
        }

        /**
         * Simulates a batch without mutating turn state so calls before the first review boundary
         * can finish before the third identical call is paused.
         */
        @Synchronized
        internal fun firstReviewIndex(tools: List<AITool>, threshold: Int): Int {
            var simulatedPrevious = previous
            var simulatedCount = consecutiveCount
            tools.forEachIndexed { index, tool ->
                val current = fingerprint(tool)
                if (current == simulatedPrevious) {
                    simulatedCount += 1
                } else {
                    simulatedPrevious = current
                    simulatedCount = 1
                }
                if (simulatedCount >= threshold) {
                    return index
                }
            }
            return -1
        }
    }

    class SubagentLoopRejectedException(message: String) : IllegalStateException(message)

    data class ToolRuntimeContext(
        val callerName: String? = null,
        val callerCardId: String? = null,
        val callerChatId: String? = null,
        val toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        val conversationLabel: String? = null,
        val workspacePath: String? = null,
        val workspaceEnv: String? = null,
        val parentModelConfigId: String? = null,
        val parentModelIndex: Int? = null,
        val isSubagent: Boolean = false,
        val callId: String? = null,
        val invocationIndex: Int? = null,
        val timingScopeId: String? = null,
        val batchPosition: Int = 1,
        val batchSize: Int = 1,
        val permissionCheckedToolName: String? = null,
    )

    internal class BoundedToolResultAccumulator(
        private val maxChars: Int = ToolExecutionLimits.MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS,
    ) {
        private val combinedResult = StringBuilder()

        var resultCount: Int = 0
            private set

        var lastResultSuccess: Boolean? = null
            private set

        var lastResultError: String? = null
            private set

        fun add(result: ToolResult) {
            val resultText =
                (if (result.success) {
                    result.result.toString()
                } else {
                    "Step error: ${result.error ?: "Unknown error"}"
                }).trim()

            if (resultCount > 0) {
                appendBounded("\n")
            }
            appendBounded(resultText)
            resultCount += 1
            lastResultSuccess = result.success
            lastResultError = result.error?.take(maxChars)
        }

        fun isEmpty(): Boolean = resultCount == 0

        fun combinedResultText(): String = combinedResult.toString().trim()

        private fun appendBounded(value: String) {
            val remainingChars = maxChars - combinedResult.length
            if (remainingChars <= 0) return
            combinedResult.append(value, 0, minOf(value.length, remainingChars))
        }
    }

    private data class ResolvedToolTarget(
        val tool: AITool,
        val displayName: String
    )

    private fun ensureEndsWithNewline(content: String): String {
        return if (content.endsWith("\n")) content else "$content\n"
    }

    private fun ToolResult.withExecutionMetadata(
        invocation: ToolInvocation,
        state: ToolExecutionState,
        durationMs: Long? = null,
        isFinal: Boolean = true,
    ): ToolResult =
        copy(
            callId = invocation.callId,
            invocationIndex = invocation.invocationIndex.takeIf { it >= 0 },
            executionDurationMs = durationMs,
            executionState = state,
            isFinal = isFinal,
        )

    private suspend fun emitFinalResult(
        collector: StreamCollector<String>,
        result: ToolResult,
    ) {
        collector.emit(
            ensureEndsWithNewline(ConversationMarkupManager.formatToolResultForMessage(result))
        )
    }

    internal fun createCancelledToolResult(
        displayToolName: String,
        invocation: ToolInvocation,
        durationMs: Long,
        partialResultText: String,
    ): ToolResult =
        ToolResult(
            toolName = displayToolName,
            success = false,
            result = StringResultData(partialResultText),
            error = "Tool execution cancelled.",
        ).withExecutionMetadata(
            invocation = invocation,
            state = ToolExecutionState.COMPLETED,
            durationMs = durationMs,
        )

    internal fun reserveToolInvocationIndices(
        nextInvocationIndex: AtomicInteger,
        invocationCount: Int,
    ) {
        if (invocationCount > 0) {
            nextInvocationIndex.addAndGet(invocationCount)
        }
    }

    internal suspend fun countDisplayedToolInvocations(content: String): Int {
        var invocationCount = 0
        content.stream().splitBy(NestedMarkdownProcessor.getBlockPlugins()).collect { group ->
            val blockContent = StringBuilder()
            group.stream.collect { chunk -> blockContent.append(chunk) }
            val blockText = blockContent.toString()
            if (group.tag is StreamXmlPlugin &&
                ChatMarkupRegex.isToolCall(blockText)
            ) {
                invocationCount += 1
            }
        }
        return invocationCount
    }

    private fun resolveToolTarget(tool: AITool): ResolvedToolTarget {
        if (tool.name != PACKAGE_PROXY_TOOL_NAME &&
            tool.name != CliToolModeSupport.PROXY_TOOL_NAME
        ) {
            return ResolvedToolTarget(tool = tool, displayName = tool.name)
        }

        val targetToolName = tool.parameters
            .firstOrNull { it.name == "tool_name" }
            ?.value
            ?.trim()
            .orEmpty()
        if (targetToolName.isBlank()) {
            return ResolvedToolTarget(tool = tool, displayName = tool.name)
        }

        val forwardedParameters = resolveProxyParameters(tool)
        return ResolvedToolTarget(
            tool = AITool(name = targetToolName, parameters = forwardedParameters),
            displayName = targetToolName
        )
    }

    private fun resolveDisplayToolName(tool: AITool): String {
        return resolveToolTarget(tool).displayName
    }

    private fun isJsPackageTool(toolName: String, jsPackageNames: Set<String>): Boolean {
        val toolNameParts = toolName.split(':', limit = 2)
        val packageName = toolNameParts.getOrNull(0)
        return toolNameParts.size == 2 &&
            packageName != null &&
            jsPackageNames.contains(packageName)
    }

    internal fun currentToolRuntimeContext(): ToolRuntimeContext? =
        toolRuntimeContextThreadLocal.get()

    private fun addPackageContextParamIfMissing(
        params: MutableList<ToolParameter>,
        name: String,
        value: String?
    ) {
        if (value.isNullOrBlank()) {
            return
        }
        if (params.any { it.name == name }) {
            return
        }
        params.add(ToolParameter(name, value))
    }

    private fun injectPackageCallContext(
        invocation: ToolInvocation,
        jsPackageNames: Set<String>,
        callerName: String?,
        callerChatId: String?,
        callerCardId: String?
    ): ToolInvocation {
        val resolvedTargetTool = resolveToolTarget(invocation.tool).tool
        if (!isJsPackageTool(resolvedTargetTool.name, jsPackageNames)) {
            return invocation
        }

        val updatedParams = invocation.tool.parameters.toMutableList()
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CALLER_NAME_PARAM, callerName)
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CHAT_ID_PARAM, callerChatId)
        addPackageContextParamIfMissing(updatedParams, PACKAGE_CALLER_CARD_ID_PARAM, callerCardId)

        if (updatedParams.size == invocation.tool.parameters.size) {
            return invocation
        }

        return invocation.copy(
            tool = invocation.tool.copy(parameters = updatedParams)
        )
    }

    private fun getParameterValue(tool: AITool, name: String): String? {
        return tool.parameters.firstOrNull { it.name == name }?.value?.trim()
    }

    private fun isInvocationAllowedForRoleCard(
        invocation: ToolInvocation,
        roleCardToolAccess: com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
    ): Boolean {
        val toolName = invocation.tool.name.trim()
        val resolvedTarget = resolveToolTarget(invocation.tool).tool

        return when {
            toolName == CliToolModeSupport.SEARCH_TOOL_NAME -> true

            toolName == CliToolModeSupport.PROXY_TOOL_NAME -> {
                isResolvedTargetAllowedForRoleCard(resolvedTarget, roleCardToolAccess)
            }

            toolName == "use_package" -> {
                if (!roleCardToolAccess.isBuiltinToolAllowed("use_package")) {
                    false
                } else {
                    val sourceName = getParameterValue(invocation.tool, "package_name").orEmpty()
                    sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
                }
            }

            toolName == PACKAGE_PROXY_TOOL_NAME -> {
                if (!roleCardToolAccess.isBuiltinToolAllowed("package_proxy")) {
                    false
                } else {
                    val resolvedTargetName = resolvedTarget.name.trim()
                    if (resolvedTargetName.isBlank() || !resolvedTargetName.contains(':')) {
                        true
                    } else {
                        isResolvedTargetAllowedForRoleCard(resolvedTarget, roleCardToolAccess)
                    }
                }
            }

            toolName.contains(':') -> {
                val sourceName = toolName.substringBefore(':').trim()
                sourceName.isBlank() || roleCardToolAccess.isExternalSourceAllowed(sourceName)
            }

            else -> roleCardToolAccess.isBuiltinToolAllowed(toolName)
        }
    }

    private fun buildRoleCardDeniedResult(
        context: Context,
        invocation: ToolInvocation
    ): ToolResult {
        return ToolResult(
            toolName = resolveDisplayToolName(invocation.tool),
            success = false,
            result = StringResultData(""),
            error = context.getString(R.string.character_card_tool_access_denied_runtime)
        )
    }

    private fun isEnglishLanguage(context: Context): Boolean {
        return LocaleUtils.getCurrentLanguage(context).lowercase().startsWith("en")
    }

    private fun buildToolExposureDeniedResult(
        context: Context,
        invocation: ToolInvocation,
        toolExposureMode: ToolExposureMode
    ): ToolResult? {
        val toolName = invocation.tool.name.trim()
        if (toolName in PermissionReviewInternalTools.names) return null
        val useEnglish = isEnglishLanguage(context)
        val errorMessage =
            when {
                toolExposureMode == ToolExposureMode.CLI &&
                    !CliToolModeSupport.isCliPublicTool(toolName) -> {
                    CliToolModeSupport.buildCliTopLevelRestrictionErrorMessage(
                        attemptedToolName = resolveDisplayToolName(invocation.tool),
                        useEnglish = useEnglish
                    )
                }

                toolExposureMode == ToolExposureMode.FULL &&
                    CliToolModeSupport.isCliPublicTool(toolName) -> {
                    CliToolModeSupport.buildCliModeUnavailableMessage(useEnglish)
                }

                else -> null
            } ?: return null

        val resultToolName =
            if (toolExposureMode == ToolExposureMode.CLI &&
                !CliToolModeSupport.isCliPublicTool(toolName)
            ) {
                resolveDisplayToolName(invocation.tool)
            } else {
                toolName
            }

        return ToolResult(
            toolName = resultToolName,
            success = false,
            result = StringResultData(""),
            error = errorMessage
        )
    }

    private fun isResolvedTargetAllowedForRoleCard(
        resolvedTarget: AITool,
        roleCardToolAccess: com.ai.assistance.operit.data.preferences.ResolvedCharacterCardToolAccess
    ): Boolean {
        val resolvedTargetName = resolvedTarget.name.trim()
        if (resolvedTargetName.isBlank()) {
            return true
        }

        val usePackageSourceName =
            if (resolvedTargetName == "use_package") {
                getParameterValue(resolvedTarget, "package_name")
            } else {
                null
            }

        return CliToolModeSupport.isToolNameAllowedForRoleCard(
            toolName = resolvedTargetName,
            usePackageSourceName = usePackageSourceName,
            roleCardToolAccess = roleCardToolAccess
        )
    }

    private fun resolveProxyParameters(tool: AITool): List<ToolParameter> {
        val paramsRaw = tool.parameters
            .firstOrNull { it.name == "params" }
            ?.value
            ?.trim()
            .orEmpty()
        if (paramsRaw.isBlank()) {
            return emptyList()
        }

        val paramsObject = runCatching { JSONObject(paramsRaw) }.getOrNull() ?: return emptyList()
        val forwardedParameters = mutableListOf<ToolParameter>()
        val keys = paramsObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = paramsObject.opt(key)
            val valueString = when (value) {
                null, JSONObject.NULL -> "null"
                is String -> value
                else -> value.toString()
            }
            forwardedParameters.add(ToolParameter(name = key, value = valueString))
        }
        return forwardedParameters
    }

    /**
     * 从 AI 响应中提取工具调用。
     * @param response AI 的响应字符串。
     * @return 检测到的工具调用列表。
     */
    suspend fun extractToolInvocations(response: String): List<ToolInvocation> {
        val invocations = mutableListOf<ToolInvocation>()
        val content = response

        val charStream = content.stream()
        val plugins = NestedMarkdownProcessor.getBlockPlugins()

        charStream.splitBy(plugins).collect { group ->
            val chunkContent = StringBuilder()
            group.stream.collect { chunk -> chunkContent.append(chunk) }
            val chunkString = chunkContent.toString()

            if (chunkString.isEmpty()) return@collect

            if (group.tag is StreamXmlPlugin) {
                ChatMarkupRegex.matchToolCall(chunkString)?.let { toolMatch ->
                    val toolName = toolMatch.groupValues.getOrNull(2) ?: return@let
                    val toolBody = toolMatch.groupValues.getOrNull(3).orEmpty()

                    val parameters = mutableListOf<ToolParameter>()
                    MessageContentParser.toolParamPattern.findAll(toolBody)
                        .forEach { paramMatch ->
                            val paramName = paramMatch.groupValues[1]
                            val paramValue = paramMatch.groupValues[2]
                            parameters.add(ToolParameter(paramName, unescapeXml(paramValue)))
                        }

                    val tool = AITool(name = toolName, parameters = parameters)
                    invocations.add(
                        ToolInvocation(
                            tool = tool,
                            rawText = toolMatch.value,
                            responseLocation = toolMatch.range
                        )
                    )
                }
            }
        }

        AppLogger.d(
            TAG,
            "Found ${invocations.size} tool invocations: ${invocations.map { resolveDisplayToolName(it.tool) }}"
        )
        return invocations
    }

    /**
     * Unescapes XML special characters
     * @param input The XML escaped string
     * @return Unescaped string
     */
    private fun unescapeXml(input: String): String {
        var result = input

        // 处理 CDATA 标记
        if (result.startsWith("<![CDATA[") && result.endsWith("]]>")) {
            result = result.substring(9, result.length - 3)
        }

        // 即使没有完整的 CDATA 标记，也尝试清理末尾的 ]]> 和开头的 <![CDATA[
        if (result.endsWith("]]>")) {
            result = result.substring(0, result.length - 3)
        }

        if (result.startsWith("<![CDATA[")) {
            result = result.substring(9)
        }

        return result.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    /**
     * Execute a tool safely, with parameter validation
     *
     * @param invocation The tool invocation to execute
     * @param executor The tool executor to use
     * @return The result of the tool execution
     */
    fun executeToolSafely(
        invocation: ToolInvocation,
        executor: ToolExecutor,
        toolHandler: AIToolHandler? = null
    ): Flow<ToolResult> {
        val validationResult = executor.validateParameters(invocation.tool)
        if (!validationResult.valid) {
            return flow {
                emit(
                    ToolResult(
                        toolName = invocation.tool.name,
                        success = false,
                        result = StringResultData(""),
                        error = "Invalid parameters: ${validationResult.errorMessage}"
                    )
                )
            }
        }

        return executor.invokeAndStream(invocation.tool).catch { e ->
            AppLogger.e(TAG, "Tool execution error: ${invocation.tool.name}", e)
            toolHandler?.notifyToolExecutionError(invocation.tool, e)
            emit(
                ToolResult(
                    toolName = invocation.tool.name,
                    success = false,
                    result = StringResultData(""),
                    error = "Tool execution error: ${e.message}"
                )
            )
        }
    }

    /**
     * Check if a tool requires permission and verify if it has permission
     *
     * @param toolHandler The AIToolHandler instance to use for permission checks
     * @param invocation The tool invocation to check permissions for
     * @return A pair containing (has permission, error result if no permission)
     */
    internal suspend fun checkToolPermission(
        toolHandler: AIToolHandler,
        invocation: ToolInvocation,
        toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        callerChatId: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        timingScopeId: String? = null,
        batchPosition: Int = 1,
        batchSize: Int = 1,
        deferCircuitBreaker: Boolean = false,
        liveAssistantContent: String? = null,
    ): ToolPermissionCheckResult {
        if (invocation.tool.name in PermissionReviewInternalTools.names) {
            toolHandler.notifyToolPermissionChecked(
                invocation.tool,
                granted = true,
                reason = "Capability-bound internal permission-review tool",
            )
            return ToolPermissionCheckResult(ToolPermissionDecision.Allowed, null)
        }

        val resolvedTarget = resolveToolTarget(invocation.tool)
        val permissionTool = resolvedTarget.tool

        if (toolExposureMode == ToolExposureMode.CLI &&
            invocation.tool.name == CliToolModeSupport.SEARCH_TOOL_NAME
        ) {
            toolHandler.notifyToolPermissionChecked(
                permissionTool,
                granted = true,
                reason = "CLI public tool"
            )
            return ToolPermissionCheckResult(ToolPermissionDecision.Allowed, null)
        }

        // Always check the resolved target. Model-controlled raw XML must never bypass permission.
        val toolPermissionSystem = toolHandler.getToolPermissionSystem()
        val permissionDecision =
            toolPermissionSystem.checkToolPermission(
                tool = permissionTool,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                callerChatId = callerChatId,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                timingScopeId = timingScopeId,
                targetId = invocation.callId ?: "$timingScopeId:${invocation.invocationIndex}",
                invocationIndex = invocation.invocationIndex,
                batchPosition = batchPosition,
                batchSize = batchSize,
                deferCircuitBreaker = deferCircuitBreaker,
                liveAssistantContent = liveAssistantContent,
            )

        if (permissionDecision is ToolPermissionDecision.Denied) {
            val errorResult =
                ToolResult(
                    toolName = resolvedTarget.displayName,
                    success = false,
                    result = StringResultData(""),
                    error = permissionDecision.rejection,
                    interruptTurn = permissionDecision.interruptTurn,
                )
            toolHandler.notifyToolPermissionChecked(
                permissionTool,
                granted = false,
                reason = errorResult.error
            )
            return ToolPermissionCheckResult(permissionDecision, errorResult)
        }

        toolHandler.notifyToolPermissionChecked(permissionTool, granted = true)
        return ToolPermissionCheckResult(permissionDecision, null)
    }

    internal suspend fun extractExecutableToolInvocations(response: String): List<ToolInvocation> =
        extractToolInvocations(ChatUtils.removeThinkingContent(response))

    /**
     *
     * 执行工具调用，包括权限检查、并行/串行执行和结果聚合。
     * @param invocations 要执行的工具调用列表。
     * @param toolHandler AIToolHandler 的实例。
     * @param packageManager PackageManager 的实例。
     * @param collector 用于实时输出结果的 StreamCollector。
     * @return 所有工具执行结果的列表。
     */
    suspend fun executeInvocations(
        invocations: List<ToolInvocation>,
        context: Context,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>,
        timingScopeId: String? = null,
        toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        callerName: String? = null,
        callerChatId: String? = null,
        callerCardId: String? = null,
        conversationLabel: String? = null,
        workspacePath: String? = null,
        workspaceEnv: String? = null,
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
        liveAssistantContent: String? = null,
        isSubagent: Boolean = false,
        subagentToolLoopGuard: SubagentToolLoopGuard? = null,
    ): List<ToolResult> = coroutineScope {
        if (isSubagent && subagentToolLoopGuard != null && invocations.size > 1) {
            val firstReviewIndex =
                subagentToolLoopGuard.firstReviewIndex(
                    tools = invocations.map { it.tool },
                    threshold = SUBAGENT_EXACT_REPEAT_REVIEW_THRESHOLD,
                )
            if (firstReviewIndex > 0) {
                val commonPrefixResults =
                    executeInvocations(
                        context = context,
                        invocations = invocations.take(firstReviewIndex),
                        toolHandler = toolHandler,
                        packageManager = packageManager,
                        collector = collector,
                        timingScopeId = timingScopeId,
                        toolExposureMode = toolExposureMode,
                        callerName = callerName,
                        callerChatId = callerChatId,
                        callerCardId = callerCardId,
                        conversationLabel = conversationLabel,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv,
                        parentModelConfigId = parentModelConfigId,
                        parentModelIndex = parentModelIndex,
                        liveAssistantContent = liveAssistantContent,
                        isSubagent = true,
                        subagentToolLoopGuard = subagentToolLoopGuard,
                    )
                val reviewedSuffixResults =
                    executeInvocations(
                        context = context,
                        invocations = invocations.drop(firstReviewIndex),
                        toolHandler = toolHandler,
                        packageManager = packageManager,
                        collector = collector,
                        timingScopeId = timingScopeId,
                        toolExposureMode = toolExposureMode,
                        callerName = callerName,
                        callerChatId = callerChatId,
                        callerCardId = callerCardId,
                        conversationLabel = conversationLabel,
                        workspacePath = workspacePath,
                        workspaceEnv = workspaceEnv,
                        parentModelConfigId = parentModelConfigId,
                        parentModelIndex = parentModelIndex,
                        liveAssistantContent = liveAssistantContent,
                        isSubagent = true,
                        subagentToolLoopGuard = subagentToolLoopGuard,
                    )
                return@coroutineScope commonPrefixResults + reviewedSuffixResults
            }
        }

        invocations.forEach { invocation ->
            ToolExecutionTimingRepository.register(timingScopeId, invocation)
        }

        try {
        // 默认工具注册现在可能在启动阶段被延后；这里确保在真正执行工具前已完成注册
        // registerDefaultTools() 是幂等且线程安全的，可安全重复调用
        withContext(Dispatchers.Default) {
            toolHandler.registerDefaultTools()
        }

        val loopApprovedInvocations =
            java.util.IdentityHashMap<ToolInvocation, Boolean>()
        if (isSubagent && subagentToolLoopGuard != null) {
            val permissionSystem = toolHandler.getToolPermissionSystem()
            for (invocation in invocations) {
                val resolvedTool = resolveToolTarget(invocation.tool).tool
                val consecutiveCount = subagentToolLoopGuard.record(invocation.tool)
                if (resolvedTool.name == "task") {
                    // Nested Subagents are rejected below, so they must never trigger
                    // an approval dialog that cannot make the invocation executable.
                    // Recording first still lets this distinct call break another
                    // tool's exact-repeat sequence.
                    continue
                }
                if (consecutiveCount < SUBAGENT_EXACT_REPEAT_REVIEW_THRESHOLD) {
                    continue
                }
                val isCliPublicTool =
                    toolExposureMode == ToolExposureMode.CLI &&
                        (
                            invocation.tool.name == CliToolModeSupport.SEARCH_TOOL_NAME ||
                                invocation.tool.name == CliToolModeSupport.PROXY_TOOL_NAME
                            )
                val effectivePermission =
                    permissionSystem.getEffectivePermissionLevel(resolvedTool.name)
                if (!isCliPublicTool && effectivePermission == PermissionLevel.FORBID) {
                    // The normal permission path will reject this call without offering an override.
                    continue
                }
                ToolExecutionTimingRepository.markWaitingAuthorization(
                    timingScopeId,
                    invocation,
                )
                val approvedByPrompt =
                    permissionSystem.requestExplicitApproval(
                        tool = resolvedTool,
                        operationDescription =
                            context.getString(
                                R.string.subagent_loop_review_operation,
                                resolveDisplayToolName(invocation.tool),
                                consecutiveCount,
                            ),
                        conversationLabel = conversationLabel,
                    )
                val approved =
                    resolveApprovalDecisionWithPermanentOverride(
                        approvalGranted = approvedByPrompt,
                        latestToolOverride =
                            permissionSystem.getToolPermissionOverride(resolvedTool.name),
                    )
                if (!approved) {
                    throw SubagentLoopRejectedException(
                        context.getString(
                            R.string.subagent_loop_rejected,
                            resolveDisplayToolName(invocation.tool),
                        )
                    )
                }
                loopApprovedInvocations[invocation] = true
            }
        }

        val roleCardToolAccess = if (callerCardId.isNullOrBlank()) {
            null
        } else {
            runCatching {
                CharacterCardToolAccessResolver
                    .getInstance(context)
                    .resolve(callerCardId, packageManager)
            }.onFailure { error ->
                AppLogger.e(TAG, "角色卡工具权限解析失败: callerCardId=$callerCardId", error)
            }.getOrNull()
        }
        val toolRuntimeContext =
            ToolRuntimeContext(
                callerName = callerName,
                callerCardId = callerCardId,
                callerChatId = callerChatId,
                toolExposureMode = toolExposureMode,
                conversationLabel = conversationLabel,
                workspacePath = workspacePath,
                workspaceEnv = workspaceEnv,
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                isSubagent = isSubagent,
                timingScopeId = timingScopeId,
                batchSize = invocations.size.coerceAtLeast(1),
            )

        // 1. 顶层工具暴露模式拦截
        val toolExposurePermittedInvocations = mutableListOf<ToolInvocation>()
        val toolExposureDeniedResults = mutableListOf<ToolResult>()
        for (invocation in invocations) {
            val deniedResult =
                if (
                    isSubagent &&
                        SubagentToolPolicy.isForbidden(resolveDisplayToolName(invocation.tool))
                ) {
                    val targetToolName = resolveDisplayToolName(invocation.tool)
                    ToolResult(
                        toolName = targetToolName,
                        success = false,
                        result = StringResultData(""),
                        error = "Subagents cannot invoke tools that start nested AI turns.",
                    )
                } else {
                    buildToolExposureDeniedResult(context, invocation, toolExposureMode)
                }
            if (deniedResult == null) {
                toolExposurePermittedInvocations.add(invocation)
            } else {
                val finalResult =
                    deniedResult.withExecutionMetadata(
                        invocation = invocation,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                toolExposureDeniedResults.add(finalResult)
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalResult,
                    durationMs = null,
                    state = ToolExecutionState.NOT_EXECUTED,
                )
            }
        }

        // 2. 角色卡工具权限拦截（优先于权限弹窗与包自动激活）
        val roleCardPermittedInvocations = mutableListOf<ToolInvocation>()
        val roleCardDeniedResults = mutableListOf<ToolResult>()
        for (invocation in toolExposurePermittedInvocations) {
            val deniedResult = if (roleCardToolAccess?.customEnabled == true &&
                !isInvocationAllowedForRoleCard(invocation, roleCardToolAccess)
            ) {
                buildRoleCardDeniedResult(context, invocation)
            } else {
                null
            }

            if (deniedResult == null) {
                roleCardPermittedInvocations.add(invocation)
            } else {
                val finalResult =
                    deniedResult.withExecutionMetadata(
                        invocation = invocation,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                roleCardDeniedResults.add(finalResult)
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalResult,
                    durationMs = null,
                    state = ToolExecutionState.NOT_EXECUTED,
                )
            }
        }

        // 3. Hook 拦截后并发完成整批权限检查。人工 ASK 仍由 ToolPermissionSystem 的
        // askMutex 串行显示；自动审核则可按模型并发能力并行运行。
        val permittedInvocations = mutableListOf<ToolInvocation>()
        val hookDeniedResults = mutableListOf<ToolResult>()
        val permissionDeniedResults = mutableListOf<ToolResult>()
        val permissionCandidates =
            mutableListOf<Triple<Int, ToolInvocation, AITool>>()
        for ((batchIndex, invocation) in roleCardPermittedInvocations.withIndex()) {
            toolHandler.notifyToolCallRequested(invocation.tool)
            val interceptionTool = resolveToolTarget(invocation.tool).tool
            val interception =
                if (interceptionTool.name in PermissionReviewInternalTools.names) {
                    AIToolHookDecision.Allow
                } else {
                    toolHandler.checkToolInterception(interceptionTool)
                }
            when (interception) {
                AIToolHookDecision.Allow -> {
                    ToolExecutionTimingRepository.markWaitingAuthorization(
                        timingScopeId,
                        invocation,
                    )
                    permissionCandidates += Triple(batchIndex, invocation, interceptionTool)
                }

                is AIToolHookDecision.Block -> {
                    val interceptedResult =
                        toolHandler.buildToolInterceptionResult(
                            resolveDisplayToolName(invocation.tool),
                            interception
                        )
                    val finalResult =
                        interceptedResult.withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    hookDeniedResults.add(finalResult)
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        finalResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    toolHandler.notifyToolExecutionFinished(invocation.tool)
                }
            }
        }

        val permissionChecks =
            parallelMapPreservingOrder(permissionCandidates) {
                    (batchIndex, invocation, interceptionTool) ->
                        val check =
                        if (loopApprovedInvocations.containsKey(invocation)) {
                            toolHandler.notifyToolPermissionChecked(
                                interceptionTool,
                                granted = true,
                                reason = "Approved by Subagent exact-repeat loop review",
                            )
                            ToolPermissionCheckResult(ToolPermissionDecision.Allowed, null)
                        } else {
                            checkToolPermission(
                                toolHandler,
                                invocation,
                                toolExposureMode,
                                conversationLabel,
                                workspacePath,
                                workspaceEnv,
                                callerChatId,
                                parentModelConfigId,
                                parentModelIndex,
                                timingScopeId,
                                batchIndex + 1,
                                roleCardPermittedInvocations.size,
                                deferCircuitBreaker = true,
                                liveAssistantContent = liveAssistantContent,
                            )
                        }
                        val finalError =
                            check.errorResult?.withExecutionMetadata(
                                invocation = invocation,
                                state = ToolExecutionState.NOT_EXECUTED,
                            )
                        if (check.granted) {
                            ToolExecutionTimingRepository.markWaitingExecution(
                                timingScopeId,
                                invocation,
                            )
                        } else if (finalError != null) {
                            ToolExecutionTimingRepository.markFinished(
                                timingScopeId,
                                invocation,
                                finalError,
                                durationMs = null,
                                state = ToolExecutionState.NOT_EXECUTED,
                            )
                        }
                        Triple(invocation, check, finalError)
                }

        // awaitAll preserves the candidate order, so UI state and later execution remain stable
        // even when individual reviewers finish out of order.
        for ((invocation, permissionCheck, initialErrorResult) in permissionChecks) {
            var errorResult = initialErrorResult
            if (!callerChatId.isNullOrBlank()) {
                val adjustedDecision =
                    applyDeferredPermissionReviewCircuit(
                        parentChatId = callerChatId,
                        turnScopeId = timingScopeId,
                        decision = permissionCheck.decision,
                        wasAutomaticallyReviewed =
                            PermissionReviewEventRepository.findForInvocation(
                                callerChatId,
                                timingScopeId,
                                invocation.invocationIndex,
                            )?.status in
                                setOf(
                                    PermissionReviewStatus.APPROVED,
                                    PermissionReviewStatus.DENIED,
                                ),
                    )
                if (adjustedDecision is ToolPermissionDecision.Denied &&
                    adjustedDecision.interruptTurn &&
                    errorResult != null
                ) {
                    errorResult = errorResult.copy(interruptTurn = true)
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        errorResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                }
            }
            if (permissionCheck.granted) {
                        permittedInvocations.add(invocation)
            } else if (errorResult != null) {
                permissionDeniedResults.add(errorResult)
            }
        }

        // A Guardian circuit-breaker decision stops the whole pending batch. Permission checks are
        // completed before execution, so previously approved siblings have not started yet and can
        // still be cancelled atomically instead of partially executing a suspicious model turn.
        if (shouldInterruptPendingToolBatch(permissionDeniedResults)) {
            toolHandler.getToolPermissionSystem().showAutomaticReviewCircuitBreakerWarning()
            val interruptedApprovedResults =
                permittedInvocations.map { invocation ->
                    val interrupted =
                        ToolResult(
                            toolName = resolveDisplayToolName(invocation.tool),
                            success = false,
                            result = StringResultData(""),
                            error =
                                "Tool execution cancelled because automatic permission review " +
                                    "stopped this model turn after repeated denied actions.",
                            interruptTurn = true,
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        interrupted,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    interrupted
                }
            permittedInvocations.clear()
            permissionDeniedResults.addAll(interruptedApprovedResults)
        }

        val injectedInvocations =
            if (callerName.isNullOrBlank() && callerChatId.isNullOrBlank() && callerCardId.isNullOrBlank()) {
                permittedInvocations
            } else {
                val jsPackageNames = packageManager.getAvailablePackages().keys
                permittedInvocations.map { invocation ->
                    injectPackageCallContext(
                        invocation = invocation,
                        jsPackageNames = jsPackageNames,
                        callerName = callerName,
                        callerChatId = callerChatId,
                        callerCardId = callerCardId
                    )
                }
            }

        // 4. 所有权限结果齐备后，严格按模型原始调用顺序逐项返回或执行。
        // A rejected item occupies its original slot; a later rejection can no longer be emitted
        // before an earlier allowed tool has finished.
        fun key(callId: String?, invocationIndex: Int?): String =
            callId?.takeIf(String::isNotBlank) ?: "index:${invocationIndex ?: -1}"

        val deniedByInvocation =
            (
                toolExposureDeniedResults +
                    roleCardDeniedResults +
                    hookDeniedResults +
                    permissionDeniedResults
                ).associateBy { result -> key(result.callId, result.invocationIndex) }
        val executableByInvocation =
            injectedInvocations.associateBy { invocation ->
                key(invocation.callId, invocation.invocationIndex.takeIf { it >= 0 })
            }
        val orderedResults = mutableListOf<ToolResult>()
        for (originalInvocation in invocations) {
            val invocationKey =
                key(
                    originalInvocation.callId,
                    originalInvocation.invocationIndex.takeIf { it >= 0 },
                )
            val denied = deniedByInvocation[invocationKey]
            if (denied != null) {
                toolHandler.notifyToolExecutionResult(originalInvocation.tool, denied)
                emitFinalResult(collector, denied)
                orderedResults += denied
                continue
            }
            val invocation = executableByInvocation[invocationKey] ?: continue
            orderedResults +=
                executeAndEmitTool(
                    invocation = invocation,
                    toolHandler = toolHandler,
                    packageManager = packageManager,
                    collector = collector,
                    runtimeContext =
                        toolRuntimeContext.copy(
                            callId = invocation.callId,
                            invocationIndex = invocation.invocationIndex.takeIf { it >= 0 },
                            batchPosition = invocation.invocationIndex + 1,
                            permissionCheckedToolName =
                                resolveToolTarget(invocation.tool).tool.name,
                    ),
                    timingScopeId = timingScopeId,
                )
        }

        orderedResults.sortedBy { result -> result.invocationIndex ?: Int.MAX_VALUE }
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                invocations.forEach { invocation ->
                    val snapshot =
                        ToolExecutionTimingRepository.get(
                            timingScopeId,
                            invocation.invocationIndex,
                        )
                    if (snapshot?.state != ToolExecutionState.WAITING_EXECUTION &&
                        snapshot?.state != ToolExecutionState.WAITING_AUTHORIZATION
                    ) {
                        return@forEach
                    }
                    val cancelledResult =
                        ToolResult(
                            toolName = resolveDisplayToolName(invocation.tool),
                            success = false,
                            result = StringResultData(""),
                            error = "Tool execution cancelled before it started.",
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        cancelledResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    try {
                        emitFinalResult(collector, cancelledResult)
                    } catch (emitError: Exception) {
                        AppLogger.w(
                            TAG,
                            "Failed to persist cancelled pending tool result: ${emitError.message}",
                        )
                    }
                }
            }
            throw cancellation
        } catch (failure: Exception) {
            withContext(NonCancellable) {
                invocations.forEach { invocation ->
                    val snapshot =
                        ToolExecutionTimingRepository.get(
                            timingScopeId,
                            invocation.invocationIndex,
                        )
                    val terminalState =
                        when (snapshot?.state) {
                            ToolExecutionState.RUNNING -> ToolExecutionState.COMPLETED
                            ToolExecutionState.WAITING_EXECUTION,
                            ToolExecutionState.WAITING_AUTHORIZATION -> ToolExecutionState.NOT_EXECUTED
                            else -> return@forEach
                        }
                    val durationMs =
                        snapshot.startedAtElapsedMs?.let { startedAt ->
                            (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                        }
                    val failureMessage =
                        failure.message
                            ?.take(ToolExecutionLimits.MAX_TEXT_RESULT_LENGTH)
                            ?.takeIf { it.isNotBlank() }
                            ?: failure::class.java.simpleName
                    val failedResult =
                        ToolResult(
                            toolName = resolveDisplayToolName(invocation.tool),
                            success = false,
                            result = StringResultData(""),
                            error = "Tool execution failed: $failureMessage",
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = terminalState,
                            durationMs = durationMs,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        failedResult,
                        durationMs = durationMs,
                        state = terminalState,
                    )
                    try {
                        emitFinalResult(collector, failedResult)
                    } catch (emitError: Exception) {
                        AppLogger.w(
                            TAG,
                            "Failed to persist failed tool result: ${emitError.message}",
                        )
                    }
                }
            }
            throw failure
        }
    }

    /**
     * 封装单个工具的执行、实时输出和结果聚合的辅助函数
     */
    private suspend fun executeAndEmitTool(
        invocation: ToolInvocation,
        toolHandler: AIToolHandler,
        packageManager: PackageManager,
        collector: StreamCollector<String>,
        runtimeContext: ToolRuntimeContext,
        timingScopeId: String?,
    ): ToolResult {
        val toolName = invocation.tool.name
        val displayToolName = resolveDisplayToolName(invocation.tool)

        return withContext(toolRuntimeContextThreadLocal.asContextElement(runtimeContext)) {
            var startedAtElapsedMs: Long? = null
            val collectedResults = BoundedToolResultAccumulator()
            try {
                val executor = toolHandler.getToolExecutorOrActivate(toolName)
                if (executor == null) {
                    // 如果仍然为 null，则构建错误消息
                    val errorMessage =
                        buildToolNotAvailableErrorMessage(toolName, packageManager, toolHandler)
                    val notAvailableResult =
                        ToolResult(
                            toolName = displayToolName,
                            success = false,
                            result = StringResultData(""),
                            error = errorMessage
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.NOT_EXECUTED,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        notAvailableResult,
                        durationMs = null,
                        state = ToolExecutionState.NOT_EXECUTED,
                    )
                    toolHandler.notifyToolExecutionResult(invocation.tool, notAvailableResult)
                    emitFinalResult(collector, notAvailableResult)
                    return@withContext notAvailableResult
                }

                toolHandler.notifyToolExecutionStarted(invocation.tool)
                val executionStartedAtMs = SystemClock.elapsedRealtime()
                startedAtElapsedMs = executionStartedAtMs
                ToolExecutionTimingRepository.markRunning(
                    timingScopeId,
                    invocation,
                    executionStartedAtMs,
                )

                executeToolSafely(invocation, executor, toolHandler).collect { result ->
                    // Intermediate emissions belong to this same invocation. Aggregate them and
                    // publish one final row so streaming tools do not create duplicate result rows.
                    collectedResults.add(result)
                }

                // 为此调用聚合最终结果
                if (collectedResults.isEmpty()) {
                    val durationMs =
                        (SystemClock.elapsedRealtime() - executionStartedAtMs).coerceAtLeast(0L)
                    val emptyResult =
                        ToolResult(
                            toolName = displayToolName,
                            success = false,
                            result = StringResultData(""),
                            error = "The tool execution returned no results."
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.COMPLETED,
                            durationMs = durationMs,
                        )
                    ToolExecutionTimingRepository.markFinished(
                        timingScopeId,
                        invocation,
                        emptyResult,
                        durationMs = durationMs,
                        state = ToolExecutionState.COMPLETED,
                    )
                    emitFinalResult(collector, emptyResult)
                    toolHandler.notifyToolExecutionResult(invocation.tool, emptyResult)
                    return@withContext emptyResult
                }

                val lastResultSuccess = requireNotNull(collectedResults.lastResultSuccess)
                val combinedResultString = collectedResults.combinedResultText()

                val durationMs =
                    (SystemClock.elapsedRealtime() - executionStartedAtMs).coerceAtLeast(0L)
                val finalResult =
                    ToolResult(
                        toolName = displayToolName,
                        success = lastResultSuccess,
                        result = StringResultData(combinedResultString),
                        error = collectedResults.lastResultError
                    ).withExecutionMetadata(
                        invocation = invocation,
                        state = ToolExecutionState.COMPLETED,
                        durationMs = durationMs,
                    )
                ToolExecutionTimingRepository.markFinished(
                    timingScopeId,
                    invocation,
                    finalResult,
                    durationMs = durationMs,
                    state = ToolExecutionState.COMPLETED,
                )
                emitFinalResult(collector, finalResult)
                toolHandler.notifyToolExecutionResult(invocation.tool, finalResult)
                return@withContext finalResult
            } catch (cancellation: CancellationException) {
                val start = startedAtElapsedMs
                if (start != null) {
                    val durationMs =
                        (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L)
                    val cancelledResult =
                        createCancelledToolResult(
                            displayToolName = displayToolName,
                            invocation = invocation,
                            durationMs = durationMs,
                            partialResultText = collectedResults.combinedResultText(),
                        )
                    withContext(NonCancellable) {
                        ToolExecutionTimingRepository.markFinished(
                            timingScopeId,
                            invocation,
                            cancelledResult,
                            durationMs = durationMs,
                            state = ToolExecutionState.COMPLETED,
                        )
                        try {
                            emitFinalResult(collector, cancelledResult)
                        } catch (emitError: Exception) {
                            AppLogger.w(
                                TAG,
                                "Failed to persist cancelled tool result: ${emitError.message}",
                            )
                        }
                        toolHandler.notifyToolExecutionResult(invocation.tool, cancelledResult)
                    }
                }
                throw cancellation
            } finally {
                toolHandler.notifyToolExecutionFinished(invocation.tool)
            }
        }
    }

    /**
     * 构建工具不可用的错误信息，统一逻辑避免重复
     */
    private suspend fun buildToolNotAvailableErrorMessage(
        toolName: String,
        packageManager: PackageManager,
        toolHandler: AIToolHandler
    ): String {
        return when {
            toolName.contains('.') && !toolName.contains(':') -> {
                val parts = toolName.split('.', limit = 2)
                "Tool invocation syntax error: for tools inside a package, use the 'packName:toolName' format instead of '${toolName}'. You may want to call '${parts.getOrNull(0)}:${parts.getOrNull(1)}'."
            }

            toolName.contains(':') -> {
                val parts = toolName.split(':', limit = 2)
                val packName = parts[0]
                val toolNamePart = parts.getOrNull(1) ?: ""
                val isJsPackageAvailable = packageManager.getAvailablePackages().containsKey(packName)
                val isMcpServerAvailable = packageManager.getAvailableServerPackages().containsKey(packName)
                val isAvailable = isJsPackageAvailable || isMcpServerAvailable

                if (!isAvailable) {
                    "The tool package or MCP server '$packName' does not exist."
                } else {
                    // 包存在，检查是否已激活（通过检查该包的任何工具是否已注册）
                    val packageTools =
                        packageManager.getPackageTools(packName)?.tools ?: emptyList()
                    val isAdviceTool = packageTools.any { it.advice && it.name == toolNamePart }
                    val isPackageActivated = packageTools
                        .filter { !it.advice }
                        .any { toolHandler.getToolExecutor("$packName:${it.name}") != null }

                    if (isAdviceTool) {
                        "Tool '$toolNamePart' is an advice-only entry in package '$packName' and is not executable."
                    } else if (isPackageActivated) {
                        // 包已激活但工具不存在
                        "Tool '$toolNamePart' does not exist in tool package '$packName'. Please use the 'use_package' tool and specify package name '$packName' to list all available tools in this package."
                    } else {
                        // 包未激活
                        "Tool package '$packName' is not activated. Auto-activation was attempted but failed, or tool '$toolNamePart' does not exist. Please use 'use_package' with package name '$packName' to check available tools."
                    }
                }
            }

            else -> {
                // 检查是否直接把包名当作工具名调用了
                val isPackageName = packageManager.getAvailablePackages().containsKey(toolName)
                if (isPackageName) {
                    "Error: '$toolName' is a tool package, not a tool. Please use the 'use_package' tool with package name '$toolName' to activate this package before using its tools."
                } else {
                    "Tool '${toolName}' is unavailable or does not exist. If this is a tool inside a package, call it using the 'packName:toolName' format."
                }
            }
        }
    }

}

internal fun shouldInterruptPendingToolBatch(results: List<ToolResult>): Boolean =
    results.any { result -> result.interruptTurn }

internal fun applyDeferredPermissionReviewCircuit(
    parentChatId: String,
    turnScopeId: String?,
    decision: ToolPermissionDecision,
    wasAutomaticallyReviewed: Boolean,
): ToolPermissionDecision =
    when {
        decision is ToolPermissionDecision.Denied &&
            decision.source == ToolPermissionDenialSource.AUTOMATIC_REVIEW -> {
            val circuit = PermissionReviewCircuitBreaker.recordDenial(parentChatId, turnScopeId)
            decision.copy(interruptTurn = circuit.interruptTurn)
        }
        decision is ToolPermissionDecision.Allowed && wasAutomaticallyReviewed -> {
            PermissionReviewCircuitBreaker.recordNonDenial(parentChatId, turnScopeId)
            decision
        }
        else -> decision
    }

internal data class ToolPermissionCheckResult(
    val decision: ToolPermissionDecision,
    val errorResult: ToolResult?,
) {
    val granted: Boolean
        get() = decision is ToolPermissionDecision.Allowed
}

internal suspend fun <T, R> parallelMapPreservingOrder(
    values: List<T>,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    values.map { value -> async { transform(value) } }.awaitAll()
}

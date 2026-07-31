package com.ai.assistance.operit.api.chat.enhance

import android.content.Context
import android.os.SystemClock
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.AIToolHookDecision
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.core.tools.ToolExecutionTimingRepository
import com.ai.assistance.operit.core.tools.climode.CliToolModeSupport
import com.ai.assistance.operit.core.tools.climode.ToolExposureMode
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.util.stream.StreamCollector
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import java.util.concurrent.ConcurrentHashMap
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
import com.ai.assistance.operit.util.ChatMarkupRegex
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
        val parentModelConfigId: String? = null,
        val parentModelIndex: Int? = null,
        val isSubagent: Boolean = false,
        val callId: String? = null,
        val invocationIndex: Int? = null,
    )

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
        val plugins = listOf(StreamXmlPlugin())

        charStream.splitBy(plugins).collect { group ->
            val chunkContent = StringBuilder()
            group.stream.collect { chunk -> chunkContent.append(chunk) }
            val chunkString = chunkContent.toString()

            if (chunkString.isEmpty()) return@collect

            if (group.tag is StreamXmlPlugin) {
                ChatMarkupRegex.toolCallPattern.findAll(chunkString).forEach { toolMatch ->
                    val toolName = toolMatch.groupValues.getOrNull(2) ?: return@forEach
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
    suspend fun checkToolPermission(
        toolHandler: AIToolHandler,
        invocation: ToolInvocation,
        toolExposureMode: ToolExposureMode = ToolExposureMode.FULL,
        conversationLabel: String? = null
    ): Pair<Boolean, ToolResult?> {
        val resolvedTarget = resolveToolTarget(invocation.tool)
        val permissionTool =
            if (toolExposureMode == ToolExposureMode.CLI &&
                invocation.tool.name == CliToolModeSupport.PROXY_TOOL_NAME
            ) {
                invocation.tool
            } else {
                resolvedTarget.tool
            }

        if (toolExposureMode == ToolExposureMode.CLI &&
            (invocation.tool.name == CliToolModeSupport.SEARCH_TOOL_NAME ||
                invocation.tool.name == CliToolModeSupport.PROXY_TOOL_NAME)
        ) {
            toolHandler.notifyToolPermissionChecked(
                permissionTool,
                granted = true,
                reason = "CLI public tool"
            )
            return Pair(true, null)
        }

        // 检查是否强制拒绝权限（deny_tool标记）
        val hasPromptForPermission = !invocation.rawText.contains("deny_tool")

        if (hasPromptForPermission) {
            // 检查权限，如果需要则弹出权限请求界面
            val toolPermissionSystem = toolHandler.getToolPermissionSystem()
            val hasPermission =
                toolPermissionSystem.checkToolPermission(permissionTool, conversationLabel)

            // 如果权限被拒绝，创建错误结果
            if (!hasPermission) {
                val errorResult =
                    ToolResult(
                        toolName = resolvedTarget.displayName,
                        success = false,
                        result = StringResultData(""),
                        error = "User cancelled the tool execution."
                    )
                toolHandler.notifyToolPermissionChecked(
                    permissionTool,
                    granted = false,
                    reason = errorResult.error
                )
                return Pair(false, errorResult)
            }

            toolHandler.notifyToolPermissionChecked(permissionTool, granted = true)
            return Pair(true, null)
        }

        toolHandler.notifyToolPermissionChecked(
            permissionTool,
            granted = true,
            reason = "Permission check bypassed by deny_tool tag."
        )
        return Pair(true, null)
    }

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
        parentModelConfigId: String? = null,
        parentModelIndex: Int? = null,
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
                        parentModelConfigId = parentModelConfigId,
                        parentModelIndex = parentModelIndex,
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
                        parentModelConfigId = parentModelConfigId,
                        parentModelIndex = parentModelIndex,
                        isSubagent = true,
                        subagentToolLoopGuard = subagentToolLoopGuard,
                    )
                return@coroutineScope commonPrefixResults + reviewedSuffixResults
            }
        }

        invocations.forEach { invocation ->
            ToolExecutionTimingRepository.register(timingScopeId, invocation)
        }

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
                val approved =
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
                parentModelConfigId = parentModelConfigId,
                parentModelIndex = parentModelIndex,
                isSubagent = isSubagent,
            )

        // 1. 顶层工具暴露模式拦截
        val toolExposurePermittedInvocations = mutableListOf<ToolInvocation>()
        val toolExposureDeniedResults = mutableListOf<ToolResult>()
        for (invocation in invocations) {
            val deniedResult =
                if (isSubagent && resolveDisplayToolName(invocation.tool) == "task") {
                    ToolResult(
                        toolName = "task",
                        success = false,
                        result = StringResultData(""),
                        error = "Subagents cannot invoke task or create nested Subagents.",
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
                toolHandler.notifyToolExecutionResult(invocation.tool, finalResult)
                emitFinalResult(collector, finalResult)
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
                toolHandler.notifyToolExecutionResult(invocation.tool, finalResult)
                emitFinalResult(collector, finalResult)
            }
        }

        // 3. Hook 拦截与权限检查
        val permittedInvocations = mutableListOf<ToolInvocation>()
        val hookDeniedResults = mutableListOf<ToolResult>()
        val permissionDeniedResults = mutableListOf<ToolResult>()
        for (invocation in roleCardPermittedInvocations) {
            toolHandler.notifyToolCallRequested(invocation.tool)
            val interceptionTool = resolveToolTarget(invocation.tool).tool
            when (val interception = toolHandler.checkToolInterception(interceptionTool)) {
                AIToolHookDecision.Allow -> {
                    ToolExecutionTimingRepository.markWaitingAuthorization(
                        timingScopeId,
                        invocation,
                    )
                    val (hasPermission, errorResult) =
                        if (loopApprovedInvocations.containsKey(invocation)) {
                            toolHandler.notifyToolPermissionChecked(
                                interceptionTool,
                                granted = true,
                                reason = "Approved by Subagent exact-repeat loop review",
                            )
                            Pair(true, null)
                        } else {
                            checkToolPermission(
                                toolHandler,
                                invocation,
                                toolExposureMode,
                                conversationLabel
                            )
                        }
                    if (hasPermission) {
                        permittedInvocations.add(invocation)
                        ToolExecutionTimingRepository.markWaitingExecution(
                            timingScopeId,
                            invocation,
                        )
                    } else {
                        errorResult?.let {
                            val finalResult =
                                it.withExecutionMetadata(
                                    invocation = invocation,
                                    state = ToolExecutionState.NOT_EXECUTED,
                                )
                            permissionDeniedResults.add(finalResult)
                            ToolExecutionTimingRepository.markFinished(
                                timingScopeId,
                                invocation,
                                finalResult,
                                durationMs = null,
                                state = ToolExecutionState.NOT_EXECUTED,
                            )
                            emitFinalResult(collector, finalResult)
                        }
                    }
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
                    toolHandler.notifyToolExecutionResult(invocation.tool, finalResult)
                    toolHandler.notifyToolExecutionFinished(invocation.tool)
                    emitFinalResult(collector, finalResult)
                }
            }
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

        // 4. 按并行/串行对工具进行分组
        val parallelizableToolNames = setOf(
            "list_files", "read_file", "read_file_part", "read_file_full", "file_exists",
            "find_files", "file_info", "grep_code", "calculate", "ffmpeg_info",
            "visit_web", "download_file", "task"
        )
        val (parallelInvocations, serialInvocations) = injectedInvocations.partition {
            parallelizableToolNames.contains(
                it.tool.name
            )
        }

        try {
            // 5. 执行工具并收集聚合结果
            val executionResults = ConcurrentHashMap<ToolInvocation, ToolResult>()

            // 启动并行工具
            val parallelJobs = parallelInvocations.map { invocation ->
                async {
                    val result =
                        executeAndEmitTool(
                            invocation = invocation,
                            toolHandler = toolHandler,
                            packageManager = packageManager,
                            collector = collector,
                            runtimeContext =
                                toolRuntimeContext.copy(
                                    callId = invocation.callId,
                                    invocationIndex =
                                        invocation.invocationIndex.takeIf { it >= 0 },
                                ),
                            timingScopeId = timingScopeId,
                        )
                    executionResults[invocation] = result
                }
            }

            // 顺序执行串行工具
            for (invocation in serialInvocations) {
                val result =
                    executeAndEmitTool(
                        invocation = invocation,
                        toolHandler = toolHandler,
                        packageManager = packageManager,
                        collector = collector,
                        runtimeContext =
                            toolRuntimeContext.copy(
                                callId = invocation.callId,
                                invocationIndex = invocation.invocationIndex.takeIf { it >= 0 },
                            ),
                        timingScopeId = timingScopeId,
                    )
                executionResults[invocation] = result
            }

            // 等待所有并行任务完成
            parallelJobs.awaitAll()

            // 6. 按原始顺序重新排序结果
            val orderedAggregated = injectedInvocations.mapNotNull { executionResults[it] }

            // 7. 组合所有结果并返回
            toolExposureDeniedResults +
                roleCardDeniedResults +
                hookDeniedResults +
                permissionDeniedResults +
                orderedAggregated
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                injectedInvocations.forEach { invocation ->
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

                val collectedResults = mutableListOf<ToolResult>()
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

                val lastResult = collectedResults.last()
                val combinedResultString = collectedResults.joinToString("\n") { res ->
                    (if (res.success) res.result.toString() else "Step error: ${res.error ?: "Unknown error"}").trim()
                }.trim()

                val durationMs =
                    (SystemClock.elapsedRealtime() - executionStartedAtMs).coerceAtLeast(0L)
                val finalResult =
                    ToolResult(
                        toolName = displayToolName,
                        success = lastResult.success,
                        result = StringResultData(combinedResultString),
                        error = lastResult.error
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
                        ToolResult(
                            toolName = displayToolName,
                            success = false,
                            result = StringResultData(""),
                            error = "Tool execution cancelled.",
                        ).withExecutionMetadata(
                            invocation = invocation,
                            state = ToolExecutionState.COMPLETED,
                            durationMs = durationMs,
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

package com.ai.assistance.operit.integrations.http.bridge

import android.content.Context
import com.ai.assistance.operit.integrations.http.WebInputSettingsState
import com.ai.assistance.operit.integrations.http.WebUpdateInputSettingsRequest
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.ToolPermissionStop
import com.ai.assistance.operit.ui.permissions.ToolPermissionSystem
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

internal class WebChatInputSettingsBridge(
    private val appContext: Context,
    private val core: ChatServiceCore
) {
    /**
     * [thinkingChatId] is the chat whose thinking level is shown, by default the open one; null
     * shows the level picked with no chat open.
     */
    suspend fun resolveState(thinkingChatId: String? = core.currentChatId.value): WebInputSettingsState {
        val apiConfigDelegate = core.getApiConfigDelegate()
        val toolPermissionSystem = ToolPermissionSystem.getInstance(appContext)
        val permissionLevel = core.getUiStateDelegate().masterPermissionLevel.first()
        val permissionStop = toolPermissionSystem.permissionStopFlow.first()
        val currentWindowTokens = core.currentWindowSizeFlow.first()
        val target = apiConfigDelegate.effectiveChatConfigTarget.value
        val resolved = apiConfigDelegate.resolveChatContextSettings(
            target.configId, core.currentChatId.value, target.modelIndex.takeIf { target.isResolved })
        val baseContextLengthK = resolved.baseContextLength
        val maxContextLengthK = resolved.maxContextLength
        val activeContextLengthK = resolved.effectiveContextLength
        return WebInputSettingsState(
            enableThinkingMode = apiConfigDelegate.enableThinkingMode.first(),
            // The chat's own level, as the app's composer shows it.
            thinkingQualityLevel = chatThinkingLevel(thinkingChatId)
                ?: apiConfigDelegate.thinkingQualityLevel.first(),
            enableMemoryAutoUpdate = apiConfigDelegate.enableMemoryAutoUpdate.first(),
            enableAutoRead = apiConfigDelegate.enableAutoRead.first(),
            enableMaxContextMode = resolved.enableMaxContextMode,
            enableTools = apiConfigDelegate.enableTools.first(),
            disableStreamOutput = apiConfigDelegate.disableStreamOutput.first(),
            disableUserPreferenceDescription = apiConfigDelegate.disableUserPreferenceDescription.first(),
            permissionLevel = permissionLevel.name,
            permissionStop = permissionStop.name,
            currentWindowTokens = currentWindowTokens,
            baseContextLengthK = baseContextLengthK,
            maxContextLengthK = maxContextLengthK,
            activeContextLengthK = activeContextLengthK,
            maxWindowTokens = (activeContextLengthK * 1024).toLong().coerceAtLeast(0L)
        )
    }

    private suspend fun chatThinkingLevel(chatId: String?): Int? =
        com.ai.assistance.operit.data.preferences.ChatModelSelections.getInstance(appContext).level(chatId)

    suspend fun update(request: WebUpdateInputSettingsRequest): WebInputSettingsState {
        val apiConfigDelegate = core.getApiConfigDelegate()
        val uiStateDelegate = core.getUiStateDelegate()
        val toolPermissionSystem = ToolPermissionSystem.getInstance(appContext)
        val targetChatId = core.currentChatId.value
        val targetConfigId = apiConfigDelegate.effectiveChatConfigTarget.value.configId
        if (request.enableMaxContextMode != null) {
            require(targetChatId != null && request.chatId == targetChatId) {
                "Select the target conversation before changing Max mode"
            }
        }
        // The web chat always names its chat; null is the next chat, made when it first sends.
        val thinkingChatId = request.chatId
        if (request.thinkingQualityLevel != null && thinkingChatId != null) {
            require(thinkingChatId == targetChatId) {
                "Select the target conversation before changing the thinking level"
            }
        }

        request.enableThinkingMode?.let { target ->
            if (apiConfigDelegate.enableThinkingMode.value != target) {
                apiConfigDelegate.toggleThinkingMode()
            }
        }
        request.thinkingQualityLevel?.let { target ->
            // Only the named chat changes level (with none, the chat made for the next send).
            val shown = chatThinkingLevel(thinkingChatId) ?: apiConfigDelegate.thinkingQualityLevel.value
            if (shown != target) {
                com.ai.assistance.operit.ui.features.orbit.selectChatThinkingLevel(appContext, thinkingChatId, target)
            }
        }
        request.enableMemoryAutoUpdate?.let { target ->
            if (apiConfigDelegate.enableMemoryAutoUpdate.value != target) {
                apiConfigDelegate.toggleMemoryAutoUpdate()
            }
        }
        request.enableAutoRead?.let { target ->
            if (apiConfigDelegate.enableAutoRead.value != target) {
                apiConfigDelegate.toggleAutoRead()
            }
        }
        request.enableMaxContextMode?.let { target ->
            apiConfigDelegate.updateChatContextPolicy(requireNotNull(targetChatId), targetConfigId) {
                it.copy(maxMode = target)
            }
        }
        request.enableTools?.let { target ->
            if (apiConfigDelegate.enableTools.value != target) {
                apiConfigDelegate.toggleTools()
            }
        }
        request.disableStreamOutput?.let { target ->
            if (apiConfigDelegate.disableStreamOutput.value != target) {
                apiConfigDelegate.toggleDisableStreamOutput()
            }
        }
        request.disableUserPreferenceDescription?.let { target ->
            if (apiConfigDelegate.disableUserPreferenceDescription.value != target) {
                apiConfigDelegate.toggleDisableUserPreferenceDescription()
            }
        }
        val stopTarget =
            request.permissionStop
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { rawValue -> ToolPermissionStop.fromString(rawValue.uppercase(Locale.US)) }
        if (stopTarget != null) {
            // A client that names the stop names the whole choice, so the level it carries is written
            // with it and the level field is not applied on top of it.
            if (toolPermissionSystem.permissionStopFlow.first() != stopTarget) {
                toolPermissionSystem.savePermissionStop(stopTarget)
                uiStateDelegate.updateMasterPermissionStop(stopTarget)
            }
        } else {
            // A client from before the stops names only the level, and it keeps the stored reuse level.
            request.permissionLevel?.trim()?.takeIf { it.isNotBlank() }?.let { rawValue ->
                val target = PermissionLevel.fromString(rawValue.uppercase(Locale.US))
                if (uiStateDelegate.masterPermissionLevel.value != target) {
                    toolPermissionSystem.saveMasterSwitch(target)
                    uiStateDelegate.updateMasterPermissionLevel(target)
                }
            }
        }

        return waitForUpdate(request, thinkingChatId)
    }

    private suspend fun waitForUpdate(
        request: WebUpdateInputSettingsRequest,
        thinkingChatId: String?,
    ): WebInputSettingsState {
        val requestedStop =
            request.permissionStop
                ?.trim()
                ?.let { rawValue -> ToolPermissionStop.fromString(rawValue.uppercase(Locale.US)) }
        repeat(12) {
            val snapshot = resolveState(thinkingChatId)
            val matches =
                (request.enableThinkingMode == null || snapshot.enableThinkingMode == request.enableThinkingMode) &&
                    (request.thinkingQualityLevel == null || snapshot.thinkingQualityLevel == request.thinkingQualityLevel) &&
                    (request.enableMemoryAutoUpdate == null || snapshot.enableMemoryAutoUpdate == request.enableMemoryAutoUpdate) &&
                    (request.enableAutoRead == null || snapshot.enableAutoRead == request.enableAutoRead) &&
                    (request.enableMaxContextMode == null || snapshot.enableMaxContextMode == request.enableMaxContextMode) &&
                    (request.enableTools == null || snapshot.enableTools == request.enableTools) &&
                    (request.disableStreamOutput == null || snapshot.disableStreamOutput == request.disableStreamOutput) &&
                    (
                        request.disableUserPreferenceDescription == null ||
                            snapshot.disableUserPreferenceDescription ==
                                request.disableUserPreferenceDescription
                    ) &&
                    (
                        request.permissionLevel.isNullOrBlank() ||
                            snapshot.permissionLevel ==
                                PermissionLevel.fromString(
                                    request.permissionLevel?.uppercase(Locale.US)
                                ).name
                    ) &&
                    (requestedStop == null || snapshot.permissionStop == requestedStop.name)
            if (matches) {
                return snapshot
            }
            delay(30)
        }
        return resolveState(thinkingChatId)
    }
}

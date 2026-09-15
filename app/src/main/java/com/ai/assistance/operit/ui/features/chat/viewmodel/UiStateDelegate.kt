package com.ai.assistance.operit.ui.features.chat.viewmodel

import android.content.Intent
import com.ai.assistance.operit.data.model.AiReference
import com.ai.assistance.operit.ui.permissions.PermissionLevel
import com.ai.assistance.operit.ui.permissions.ToolPermissionStop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 委托类，负责管理UI状态相关功能 */
class UiStateDelegate {
    // UI状态
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _popupMessage = MutableStateFlow<String?>(null)
    val popupMessage: StateFlow<String?> = _popupMessage.asStateFlow()

    private val _toastEvent = MutableStateFlow<String?>(null)
    val toastEvent: StateFlow<String?> = _toastEvent.asStateFlow()

    /** Before the stores emit, the chat shows the level a fresh install starts on. */
    private val _masterPermissionLevel = MutableStateFlow(ToolPermissionStop.DEFAULT.level)
    val masterPermissionLevel: StateFlow<PermissionLevel> = _masterPermissionLevel.asStateFlow()

    /** The same choice as the permission slider shows it: the level plus its reuse level. */
    private val _masterPermissionStop = MutableStateFlow(ToolPermissionStop.DEFAULT)
    val masterPermissionStop: StateFlow<ToolPermissionStop> = _masterPermissionStop.asStateFlow()

    // 文件选择器请求
    private val _fileChooserRequest = MutableStateFlow<Intent?>(null)
    val fileChooserRequest: StateFlow<Intent?> = _fileChooserRequest.asStateFlow()

    /** 显示错误消息 */
    fun showErrorMessage(message: String) {
        _errorMessage.value = message
    }

    /** 清除错误消息 */
    fun clearError() {
        _errorMessage.value = null
    }

    /** 显示弹出消息 */
    fun showPopupMessage(message: String) {
        _popupMessage.value = message
    }

    /** 清除弹出消息 */
    fun clearPopupMessage() {
        _popupMessage.value = null
    }

    /** 显示Toast消息 */
    fun showToast(message: String) {
        _toastEvent.value = message
    }

    /** 清除Toast消息 */
    fun clearToastEvent() {
        _toastEvent.value = null
    }

    /**
     * 更新主权限级别。只改变等级，复用等级由 [updateMasterPermissionStop] 跟随存储值维护。
     */
    fun updateMasterPermissionLevel(level: PermissionLevel) {
        _masterPermissionLevel.value = level
    }

    /** 更新主权限档位（滑块上选中的那一档），等级随之同步。 */
    fun updateMasterPermissionStop(stop: ToolPermissionStop) {
        _masterPermissionStop.value = stop
        _masterPermissionLevel.value = stop.level
    }

    /** 请求文件选择器 */
    fun requestFileChooser(intent: Intent) {
        _fileChooserRequest.value = intent
    }

    /** 清除文件选择器请求 */
    fun clearFileChooserRequest() {
        _fileChooserRequest.value = null
    }
}

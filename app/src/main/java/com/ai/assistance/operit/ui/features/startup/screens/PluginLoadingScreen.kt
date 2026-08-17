package com.ai.assistance.operit.ui.features.startup.screens

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.mcp.MCPLocalServer
import com.ai.assistance.operit.data.mcp.MCPRepository
import com.ai.assistance.operit.data.mcp.plugins.MCPStarter
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceConfig
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceManager
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceRepository
import com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceState
import com.ai.assistance.operit.ui.features.startup.components.SmoothLinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** 表示插件加载状态的枚举 */
enum class PluginStatus {
    WAITING, // 等待加载
    LOADING, // 正在加载
    SUCCESS, // 加载成功
    FAILED // 加载失败
}

private const val TERMINAL_SERVICE_ITEM_PREFIX = "terminal-service:"
private const val TERMINAL_SERVICE_CONFIG_ERROR_ID = "terminal-service:config-error"
private fun terminalServiceItemId(serviceId: String): String = "$TERMINAL_SERVICE_ITEM_PREFIX$serviceId"

enum class PluginStartupScope {
    APP_BOOT,
    MCP_ONLY,
}

internal fun shouldStartTerminalServices(startupScope: PluginStartupScope): Boolean =
    startupScope == PluginStartupScope.APP_BOOT

private const val BASE_PLUGIN_LOADING_TIMEOUT_MS = 30_000L
private const val TERMINAL_SESSION_INITIALIZATION_BUDGET_MS = 30_000L
private const val TERMINAL_PROCESS_SHUTDOWN_BUDGET_MS = 3_000L
private const val STARTUP_COMPLETION_GRACE_MS = 5_000L

internal fun combinedStartupLoadingTimeoutMs(
    enabledServices: List<TerminalStartupServiceConfig>,
): Long {
    val terminalBudget = enabledServices.maxOfOrNull { config ->
        val retryCount = if (config.autoRestart) config.maxRestartAttempts.coerceAtLeast(0) else 0
        val attemptCount = retryCount + 1L
        val attemptsBudget = attemptCount * (
            TERMINAL_SESSION_INITIALIZATION_BUDGET_MS +
                config.startupTimeoutMs +
                TERMINAL_PROCESS_SHUTDOWN_BUDGET_MS
            )
        val restartDelayBudget = (1..retryCount).sumOf { attempt ->
            (1_000L * (1L shl (attempt - 1).coerceIn(0, 4))).coerceAtMost(8_000L)
        }
        attemptsBudget + restartDelayBudget + STARTUP_COMPLETION_GRACE_MS
    } ?: 0L
    return maxOf(BASE_PLUGIN_LOADING_TIMEOUT_MS, terminalBudget)
}

internal fun shouldReplaceStartupMessageWithSummary(
    mcpStatus: MCPStarter.PluginInitStatus,
): Boolean = mcpStatus == MCPStarter.PluginInitStatus.SUCCESS

@Suppress("UNUSED_PARAMETER")
internal fun shouldInitializeMcpRuntime(
    enabledPluginCount: Int,
    pluginDiscoveryError: Throwable?,
    terminalConfigError: Throwable? = null
): Boolean = pluginDiscoveryError == null

/** 表示单个插件的加载信息 */
data class PluginInfo(
        val id: String,
        val displayName: String,
        var status: PluginStatus = PluginStatus.WAITING,
        var message: String = "",
        var serviceName: String = ""
) {
    val shortName: String
        get() = id.split("/").lastOrNull() ?: id
}

/**
 * 插件加载屏幕
 *
 * 在应用启动时显示插件加载进度的全屏界面
 */
@Composable
fun PluginLoadingScreen(
        isVisible: Boolean,
        progress: Float,
        message: String,
        pluginsStarted: Int,
        pluginsTotal: Int,
        pluginsList: List<PluginInfo>,
        isExpanded: Boolean,
        onToggleExpansion: () -> Unit,
        onSkip: () -> Unit = {},
        onPluginClick: (PluginInfo) -> Unit = {},
        modifier: Modifier = Modifier
) {
    AnimatedVisibility(
            visible = isVisible,
            enter =
                    fadeIn(
                            initialAlpha = 0f,
                            animationSpec = androidx.compose.animation.core.tween(500)
                    ),
            exit =
                    fadeOut(
                            targetAlpha = 0f,
                            animationSpec = androidx.compose.animation.core.tween(800)
                    )
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            AnimatedContent(targetState = isExpanded, modifier = Modifier.align(if(isExpanded) Alignment.BottomCenter else Alignment.TopEnd), label = "") { expanded ->
                if (expanded) {
                    ExpandedLoadingView(
                        progress,
                        message,
                        pluginsStarted,
                        pluginsTotal,
                        pluginsList,
                        onSkip,
                        onCollapse = onToggleExpansion,
                        onPluginClick = onPluginClick
                    )
                } else {
                    DraggableCollapsedIndicator(progress, pluginsStarted, pluginsTotal, onClick = onToggleExpansion)
                }
            }
        }
    }
}

@Composable
private fun DraggableCollapsedIndicator(
    progress: Float,
    pluginsStarted: Int,
    pluginsTotal: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset += dragAmount
                }
            }
    ) {
        CollapsedLoadingIndicator(
            progress = progress,
            pluginsStarted = pluginsStarted,
            pluginsTotal = pluginsTotal,
            onClick = onClick
        )
    }
}

@Composable
private fun CollapsedLoadingIndicator(
    progress: Float,
    pluginsStarted: Int,
    pluginsTotal: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp)
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                text = "$pluginsStarted/$pluginsTotal",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExpandedLoadingView(
    progress: Float,
    message: String,
    pluginsStarted: Int,
    pluginsTotal: Int,
    pluginsList: List<PluginInfo>,
    onSkip: () -> Unit,
    onCollapse: () -> Unit,
    onPluginClick: (PluginInfo) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 450.dp), // Constrain height
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)) {
            // 折叠按钮
            IconButton(onClick = onCollapse, modifier = Modifier
                .align(Alignment.TopStart)
                .padding(0.dp)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Collapse")
            }
            // 跳过加载文本 - 放在右上角
            Text(
                    text = stringResource(id = R.string.plugin_skip),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .clickable {
                                    onSkip()
                                }
            )

            // 主要内容区域
            Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
            ) {
                // 应用名称/Logo
                Text(
                        text = stringResource(id = R.string.plugin_app_name),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 32.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 使用平滑过渡的进度条组件
                SmoothLinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        height = 8.dp,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        progressColor = MaterialTheme.colorScheme.primary,
                        intermediateSteps = 20, // 增加中间步骤数量，使过渡更加平滑
                        stepDuration = 50 // 减少每步时长，保持总体流畅感
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 简洁的状态消息
                Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 总插件统计
                Text(
                        text =
                                stringResource(
                                        id = R.string.plugin_status,
                                        pluginsStarted,
                                        pluginsTotal
                                ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                )

                // 移除此处的跳过按钮
                Spacer(modifier = Modifier.height(16.dp))

                // 插件列表
                if (pluginsList.isNotEmpty()) {
                    Text(
                            text = stringResource(id = R.string.plugin_loading_status_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 插件加载状态列表
                    LazyColumn(
                            modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .heightIn(max = 200.dp) // reduce height for smaller card
                    ) {
                        items(pluginsList) { plugin ->
                            PluginStatusItem(
                                    plugin = plugin,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = if (plugin.status == PluginStatus.FAILED) {
                                        { onPluginClick(plugin) }
                                    } else {
                                        null
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 底部版权信息
                Text(
                        text = stringResource(id = R.string.about_copyright),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 单个插件状态项 */
@Composable
fun PluginStatusItem(
    plugin: PluginInfo,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val animatedProgress by
            animateFloatAsState(
                    targetValue = if (plugin.status == PluginStatus.LOADING) 1f else 0f,
                    label = "loading_progress"
            )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(vertical = 4.dp)
            .let { base ->
                if (onClick != null) base.clickable(onClick = onClick) else base
            }
    ) {
        // 状态图标或加载指示器
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
            when (plugin.status) {
                PluginStatus.WAITING -> {
                    Box(
                            modifier =
                                    Modifier.size(10.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                PluginStatus.LOADING -> {
                    CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                    )
                }
                PluginStatus.SUCCESS -> {
                    Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription =
                                    stringResource(id = R.string.plugin_loading_success),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                    )
                }
                PluginStatus.FAILED -> {
                    Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription =
                                    stringResource(id = R.string.plugin_loading_failed),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // 插件名称和状态
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )

            if (plugin.message.isNotEmpty()) {
                Text(
                        text = plugin.message,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                                when (plugin.status) {
                                    PluginStatus.FAILED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// 跳过加载的回调函数接口
interface SkipLoadingCallback {
    fun onSkip()
}

/**
 * 插件加载状态管理器
 *
 * 用于管理插件加载过程中的各种状态
 */
class PluginLoadingState {
    private data class McpStartupResult(
        val successCount: Int,
        val totalCount: Int,
        val status: MCPStarter.PluginInitStatus
    )
    // 进度值 (0.0f - 1.0f)
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    // 当前状态消息
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    // 已启动的插件数量
    private val _pluginsStarted = MutableStateFlow(0)
    val pluginsStarted: StateFlow<Int> = _pluginsStarted

    // 总插件数量
    private val _pluginsTotal = MutableStateFlow(0)
    val pluginsTotal: StateFlow<Int> = _pluginsTotal

    // 是否显示加载屏幕
    private val _isVisible = MutableStateFlow(false)
    val isVisible: StateFlow<Boolean> = _isVisible

    // 是否展开
    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded

    // 插件列表及其状态
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins

    private val _pluginLogs = MutableStateFlow<Map<String, String>>(emptyMap())
    val pluginLogs: StateFlow<Map<String, String>> = _pluginLogs

    // 应用上下文，用于获取MCP相关服务
    private var appContext: Context? = null

    // 是否已超时
    private val _hasTimedOut = MutableStateFlow(false)
    val hasTimedOut: StateFlow<Boolean> = _hasTimedOut

    // Timeout ownership prevents an old batch from publishing after replacement or cancelling
    // the timer owned by a newer batch.
    private val timeoutLock = Any()
    private var nextTimeoutOwner = 0L
    private var activeTimeoutOwner: Long? = null
    private var timeoutJob: Job? = null

    private val initializationGuard = PluginInitializationGuard()
    private val orchestrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mcpInitJob: Job? = null

    // 跳过加载事件回调
    private var onSkipCallback: (() -> Unit)? = null

    // 保护插件状态与日志的并发更新，避免回调互相覆盖
    private val pluginStateLock = Any()

    // 设置应用上下文
    fun setAppContext(context: Context) {
        this.appContext = context.applicationContext
    }

    fun toggleExpansion() {
        _isExpanded.value = !_isExpanded.value
    }

    private fun forceExpanded() {
        _isExpanded.value = true
    }

    /** 更新进度信息 */
    fun updateProgress(progress: Float) {
        _progress.value = progress
    }

    /** 更新状态消息 */
    fun updateMessage(message: String) {
        _message.value = message
    }

    /** 更新插件统计 */
    fun updatePluginStats(started: Int, total: Int) {
        synchronized(pluginStateLock) {
            _pluginsStarted.value = started
            _pluginsTotal.value = total
        }
    }

    /** 设置插件列表 */
    fun setPlugins(pluginIds: List<String>) {
        val context = appContext
        val plugins =
                pluginIds.map { id ->
                    // 尝试从metadata获取插件名称
                    var displayName = id.split("/").lastOrNull() ?: id

                    // 如果上下文可用，尝试从元数据获取名称
                    if (context != null) {
                        try {
                            val mcpLocalServer = MCPLocalServer.getInstance(context)
                            val pluginInfo = mcpLocalServer.getPluginMetadata(id)
                            if (pluginInfo != null) {
                                displayName = pluginInfo.name
                            }
                        } catch (e: Exception) {
                            // 获取元数据失败，使用默认名称
                        }
                    }

                    PluginInfo(id = id, displayName = displayName)
                }
        synchronized(pluginStateLock) {
            _plugins.value = plugins
            _pluginsTotal.value = plugins.size
            _pluginsStarted.value = plugins.count { it.status == PluginStatus.SUCCESS }
        }
    }

    private fun setStartupItems(
        pluginIds: List<String>,
        services: List<TerminalStartupServiceConfig>,
        terminalConfigError: String?
    ) {
        val context = appContext
        val items = pluginIds.map { id ->
            val displayName = runCatching {
                context?.let { MCPLocalServer.getInstance(it).getPluginMetadata(id)?.name }
            }.getOrNull() ?: id.split("/").lastOrNull() ?: id
            PluginInfo(id = id, displayName = displayName)
        } + services.map { service ->
            PluginInfo(id = terminalServiceItemId(service.id), displayName = service.name)
        } + listOfNotNull(
            terminalConfigError?.let { message ->
                PluginInfo(
                    id = TERMINAL_SERVICE_CONFIG_ERROR_ID,
                    displayName = context?.getString(R.string.terminal_startup_title).orEmpty(),
                    status = PluginStatus.FAILED,
                    message = message
                )
            }
        )
        synchronized(pluginStateLock) {
            _plugins.value = items
            _pluginsTotal.value = items.size
            _pluginsStarted.value = 0
        }
    }

    /** 更新插件状态 */
    fun updatePluginStatus(pluginId: String, status: PluginStatus, message: String = "") {
        synchronized(pluginStateLock) {
            val currentPlugins = _plugins.value.toMutableList()
            val pluginIndex = currentPlugins.indexOfFirst { it.id == pluginId }

            if (pluginIndex >= 0) {
                val plugin = currentPlugins[pluginIndex].copy(status = status, message = message)
                currentPlugins[pluginIndex] = plugin
                _plugins.value = currentPlugins
                _pluginsStarted.value = currentPlugins.count { it.status == PluginStatus.SUCCESS }
            }
        }
    }

    private fun updatePluginMessage(pluginId: String, message: String) {
        synchronized(pluginStateLock) {
            val currentPlugins = _plugins.value.toMutableList()
            val pluginIndex = currentPlugins.indexOfFirst { it.id == pluginId }

            if (pluginIndex >= 0) {
                val plugin = currentPlugins[pluginIndex]
                currentPlugins[pluginIndex] = plugin.copy(message = message)
                _plugins.value = currentPlugins
            }
        }
    }

    private fun appendPluginLog(pluginId: String, message: String) {
        if (message.isBlank()) return

        synchronized(pluginStateLock) {
            val maxCharsPerPlugin = 2_000_000
            val existing = _pluginLogs.value[pluginId].orEmpty()
            val combined = if (existing.isBlank()) message else "$existing\n$message"
            val trimmed =
                if (combined.length > maxCharsPerPlugin) combined.takeLast(maxCharsPerPlugin) else combined

            _pluginLogs.value = _pluginLogs.value.toMutableMap().apply {
                put(pluginId, trimmed)
            }
        }
    }

    /** 更新插件注册状态 */
    fun updatePluginRegistration(pluginId: String, serviceName: String, success: Boolean) {
        synchronized(pluginStateLock) {
            val currentPlugins = _plugins.value.toMutableList()
            val pluginIndex = currentPlugins.indexOfFirst { it.id == pluginId }

            if (pluginIndex >= 0) {
                val plugin = currentPlugins[pluginIndex]
                val message = if (success) {
                    appContext?.getString(R.string.plugin_registered) ?: "Registered"
                } else {
                    appContext?.getString(R.string.plugin_registration_failed) ?: "Registration Failed"
                }
                currentPlugins[pluginIndex] = plugin.copy(
                    serviceName = serviceName,
                    // 不要改变主状态，只更新消息
                    message = message
                )
                _plugins.value = currentPlugins
            }
        }
    }

    /** 开始加载指定插件 */
    fun startLoadingPlugin(pluginId: String) {
        updatePluginStatus(
                pluginId,
                PluginStatus.LOADING,
                appContext?.getString(R.string.plugin_loading) ?: "Loading..."
        )
    }

    /** 标记插件加载成功 */
    fun setPluginSuccess(pluginId: String, message: String = "") {
        updatePluginStatus(
                pluginId,
                PluginStatus.SUCCESS,
                message.ifEmpty { appContext?.getString(R.string.plugin_loading_success) ?: "Loading successful" }
        )
    }

    /** 标记插件加载失败 */
    fun setPluginFailed(pluginId: String, message: String = "") {
        updatePluginStatus(
                pluginId,
                PluginStatus.FAILED,
                message.ifEmpty { appContext?.getString(R.string.plugin_loading_failed) ?: "Loading failed" }
        )
    }

    // 设置跳过回调
    fun setOnSkipCallback(callback: () -> Unit) {
        onSkipCallback = callback
    }



    // 触发跳过操作
    fun skip() {
        hide()
        onSkipCallback?.invoke()
    }

    // 启动超时检测
    fun startTimeoutCheck(timeoutMillis: Long = 30000L, scope: CoroutineScope): Long {
        val owner: Long
        val newJob: Job
        synchronized(timeoutLock) {
            owner = ++nextTimeoutOwner
            timeoutJob?.cancel()
            newJob = scope.launch(start = CoroutineStart.LAZY) {
                delay(timeoutMillis)
                synchronized(timeoutLock) {
                    if (activeTimeoutOwner != owner) return@launch
                    _hasTimedOut.value = true
                    updateMessage(
                        appContext?.getString(R.string.plugin_loading_timeout)
                            ?: "Loading timeout, you can click \"Skip\" in the top right corner to continue"
                    )
                    activeTimeoutOwner = null
                    timeoutJob = null
                }
            }
            activeTimeoutOwner = owner
            timeoutJob = newJob
            _hasTimedOut.value = false
        }
        newJob.start()
        return owner
    }

    /** 显示加载屏幕 */
    fun show() {
        _isVisible.value = true
        _hasTimedOut.value = false
        // _isExpanded.value = true // 默认展开
    }

    /** 隐藏加载屏幕 */
    fun hide() {
        cancelCurrentTimeoutCheck()
        _isVisible.value = false
        // _isExpanded.value = false // 关闭时重置为折叠状态
    }

    private fun resetState() {
        cancelCurrentTimeoutCheck()
        mcpInitJob?.cancel()
        _progress.value = 0f
        _message.value = ""
        synchronized(pluginStateLock) {
            _pluginsStarted.value = 0
            _pluginsTotal.value = 0
            _plugins.value = emptyList()
            _pluginLogs.value = emptyMap()
        }
        _isVisible.value = false
        _hasTimedOut.value = false
        _isExpanded.value = false
    }

    /** 重置所有状态 */
    fun reset(): Boolean = initializationGuard.resetIfIdle(::resetState)

    internal fun reserveInitialization(): PluginInitializationGuard.Lease? =
        initializationGuard.tryStart()

    internal fun snapshot(): PluginLoadingSnapshot = synchronized(pluginStateLock) {
        PluginLoadingSnapshot(
            progress = _progress.value,
            message = _message.value,
            pluginsStarted = _pluginsStarted.value,
            pluginsTotal = _pluginsTotal.value,
            plugins = _plugins.value.map(PluginInfo::copy),
            pluginLogs = _pluginLogs.value.toMap(),
        )
    }

    internal fun snapshotIfActive(
        lease: PluginInitializationGuard.Lease,
    ): PluginLoadingSnapshot? = initializationGuard.withActive(lease, ::snapshot)

    private fun cancelCurrentTimeoutCheck() {
        val jobToCancel = synchronized(timeoutLock) {
            activeTimeoutOwner = null
            timeoutJob.also { timeoutJob = null }
        }
        jobToCancel?.cancel()
    }

    internal fun cancelTimeoutCheck(owner: Long) {
        val jobToCancel = synchronized(timeoutLock) {
            if (activeTimeoutOwner != owner) return
            activeTimeoutOwner = null
            timeoutJob.also { timeoutJob = null }
        }
        jobToCancel?.cancel()
    }

    internal fun cancelTimeoutCheckIfOwned(owner: Long?) {
        if (owner != null) cancelTimeoutCheck(owner)
    }

    // 初始化 MCP 插件；应用启动时还会启动用户配置的终端服务。
    internal fun initializeMCPServer(
        context: Context,
        startupScope: PluginStartupScope,
        initialTimeoutOwner: Long? = null,
        resetBeforeStart: Boolean = false,
        showBeforeStart: Boolean = false,
        reservedInitializationLease: PluginInitializationGuard.Lease? = null,
        onFinished: (PluginInitializationCompletion) -> Unit = {},
    ): PluginInitializationGuard.Lease? {
        val appContext = context.applicationContext
        val initializationLease =
            if (reservedInitializationLease != null) {
                reservedInitializationLease.takeIf(initializationGuard::isActive)
            } else {
                initializationGuard.tryStart()
            }
        if (initializationLease == null) {
            cancelTimeoutCheckIfOwned(initialTimeoutOwner)
            AppLogger.d("PluginLoadingState", "initializeMCPServer already running, skipping")
            return null
        }
        if (resetBeforeStart) resetState()
        if (showBeforeStart) show()

        val finishReported = AtomicBoolean(false)
        fun reportFinished(completed: Boolean, successful: Boolean) {
            if (finishReported.compareAndSet(false, true)) {
                onFinished(
                    PluginInitializationCompletion(
                        completed = completed,
                        successful = successful,
                        snapshot = snapshot(),
                    )
                )
            }
        }
        var ownedTimeout = initialTimeoutOwner
        val initJob = orchestrationScope.launch {
            var completed = false
            var successful = false
            try {
                updateMessage(appContext.getString(R.string.plugin_initializing))
                updateProgress(0.05f)
                val mcpLocalServer = MCPLocalServer.getInstance(appContext)
                val serviceDiscovery: Result<List<TerminalStartupServiceConfig>> =
                    if (shouldStartTerminalServices(startupScope)) {
                        runCatching {
                            TerminalStartupServiceRepository.getInstance(appContext)
                                .snapshot()
                                .filter { it.enabled }
                        }.onFailure { error ->
                            AppLogger.e("PluginLoadingState", "读取终端启动服务配置失败", error)
                        }
                    } else {
                        Result.success(emptyList())
                    }
                val enabledServices = serviceDiscovery.getOrDefault(emptyList())
                val terminalConfigError = serviceDiscovery.exceptionOrNull()?.message
                if (shouldStartTerminalServices(startupScope) && terminalConfigError == null) {
                    ownedTimeout = startTimeoutCheck(
                        combinedStartupLoadingTimeoutMs(enabledServices),
                        orchestrationScope,
                    )
                }
                val mcpRepository = MCPRepository(appContext)
                val pluginDiscovery = runCatching {
                    updateMessage(appContext.getString(R.string.plugin_loading_list))
                    mcpRepository.refreshPluginList()
                    mcpRepository.installedPluginIds.first().filter { mcpLocalServer.isServerEnabled(it) }
                }.onFailure { error ->
                    AppLogger.e("PluginLoadingState", "读取 MCP 插件列表失败", error)
                }
                val pluginsToStart = pluginDiscovery.getOrDefault(emptyList())
                val pluginDiscoveryError = pluginDiscovery.exceptionOrNull()

                setStartupItems(pluginsToStart, enabledServices, terminalConfigError)
                updateMessage(
                    appContext.getString(
                        if (shouldStartTerminalServices(startupScope)) {
                            R.string.terminal_startup_starting_all
                        } else {
                            R.string.plugin_initializing
                        }
                    )
                )
                updateProgress(0.25f)
                val serviceDeferred = async {
                    if (!shouldStartTerminalServices(startupScope) || terminalConfigError != null) {
                        emptyList()
                    } else {
                        TerminalStartupServiceManager.getInstance(appContext).startEnabledServices(
                            services = enabledServices,
                            listener = object : TerminalStartupServiceManager.ProgressListener {
                                override fun onServiceStarting(config: TerminalStartupServiceConfig, index: Int, total: Int) {
                                    val itemId = terminalServiceItemId(config.id)
                                    updatePluginStatus(itemId, PluginStatus.LOADING, appContext.getString(R.string.terminal_startup_status_starting))
                                    appendPluginLog(itemId, appContext.getString(R.string.terminal_startup_start))
                                }

                                override fun onServiceStatus(config: TerminalStartupServiceConfig, status: com.ai.assistance.operit.data.terminal.startup.TerminalStartupServiceStatus) {
                                    val itemId = terminalServiceItemId(config.id)
                                    when (status.state) {
                                        TerminalStartupServiceState.RUNNING -> setPluginSuccess(itemId, appContext.getString(R.string.terminal_startup_status_running))
                                        TerminalStartupServiceState.FAILED -> {
                                            setPluginFailed(itemId, status.message)
                                            forceExpanded()
                                        }
                                        TerminalStartupServiceState.STOPPED -> updatePluginStatus(itemId, PluginStatus.FAILED, status.message)
                                        TerminalStartupServiceState.STARTING,
                                        TerminalStartupServiceState.RESTARTING -> updatePluginStatus(itemId, PluginStatus.LOADING, status.message)
                                    }
                                }

                                override fun onServiceLog(config: TerminalStartupServiceConfig, message: String) {
                                    appendPluginLog(terminalServiceItemId(config.id), message)
                                }
                            }
                        )
                    }
                }

                val mcpCompletion = CompletableDeferred<McpStartupResult>()
                if (!shouldInitializeMcpRuntime(
                        pluginsToStart.size,
                        pluginDiscoveryError,
                        serviceDiscovery.exceptionOrNull()
                    )
                ) {
                    updateMessage(
                        pluginDiscoveryError?.message
                            ?: appContext.getString(R.string.plugin_other_error)
                    )
                    mcpCompletion.complete(McpStartupResult(0, 0, MCPStarter.PluginInitStatus.OTHER_ERROR))
                } else {
                    // MCPStarter also removes disabled/stale bridge services and runtime tools.
                    // It must run even when there are no enabled plugins left to start.
                    MCPStarter(appContext).startAllDeployedPlugins(
                        createPluginStartProgressListener(mcpLocalServer, appContext) { success, total, status ->
                            mcpCompletion.complete(McpStartupResult(success, total, status))
                        }
                    )
                }

                val serviceResults = serviceDeferred.await()
                val mcpResult = mcpCompletion.await()
                val serviceSuccesses = serviceResults.count { it.success }
                val successCount = serviceSuccesses + mcpResult.successCount
                val terminalConfigFailureCount = if (terminalConfigError != null) 1 else 0
                val totalCount = serviceResults.size + mcpResult.totalCount + terminalConfigFailureCount
                val hasFailures = terminalConfigError != null ||
                    successCount < totalCount ||
                    mcpResult.status != MCPStarter.PluginInitStatus.SUCCESS
                cancelTimeoutCheckIfOwned(ownedTimeout)
                ownedTimeout = null
                updateProgress(1f)
                if (shouldReplaceStartupMessageWithSummary(mcpResult.status)) {
                    updateMessage(
                        appContext.getString(
                            if (hasFailures) R.string.terminal_startup_complete_with_failures
                            else R.string.terminal_startup_complete_success,
                            successCount,
                            totalCount
                        )
                    )
                }
                if (hasFailures) {
                    forceExpanded()
                } else {
                    delay(100L)
                    if (isVisible.value) hide()
                }
                successful = !hasFailures
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                AppLogger.e("PluginLoadingState", "启动 MCP 插件和终端服务时出错", e)
                cancelTimeoutCheckIfOwned(ownedTimeout)
                ownedTimeout = null
                updateMessage(e.message ?: appContext.getString(R.string.plugin_other_error))
                updateProgress(1.0f)
                forceExpanded()
                completed = true
            } finally {
                cancelTimeoutCheckIfOwned(ownedTimeout)
                try {
                    reportFinished(completed, successful)
                } finally {
                    initializationGuard.finish(initializationLease)
                }
            }
        }
        initJob.invokeOnCompletion { cause ->
            // A cancelled scope can prevent the coroutine body from starting at all, so its
            // finally block is not guaranteed to release the process-startup lease.
            if (cause is CancellationException) {
                cancelTimeoutCheckIfOwned(ownedTimeout)
                try {
                    reportFinished(completed = false, successful = false)
                } finally {
                    initializationGuard.finish(initializationLease)
                }
            }
        }
        mcpInitJob = initJob
        return initializationLease
    }

    // 创建插件启动进度监听器
    private fun createPluginStartProgressListener(
            mcpLocalServer: MCPLocalServer,
            context: Context,
            onComplete: (Int, Int, MCPStarter.PluginInitStatus) -> Unit
    ): MCPStarter.PluginStartProgressListener {
        return object : MCPStarter.PluginStartProgressListener {
            override fun onPluginStarting(pluginId: String, index: Int, total: Int) {
                // 在这里检查插件是否被启用
                val isEnabled = mcpLocalServer.isServerEnabled(pluginId) // 从配置读取

                // 更新总体状态
                val disabledSuffix =
                        if (!isEnabled) context.getString(R.string.plugin_disabled_suffix) else ""
                updateMessage(
                        context.getString(
                                R.string.plugin_starting_number,
                                index,
                                total,
                                disabledSuffix
                        )
                )
                updateProgress(0.4f + 0.1f * (index.toFloat() / total)) // 注册占10% (0.4 -> 0.5)

                // 更新特定插件状态
                startLoadingPlugin(pluginId)
                appendPluginLog(pluginId, "START")
            }

            override fun onPluginRegistered(pluginId: String, serviceName: String, success: Boolean) {
                updatePluginRegistration(pluginId, serviceName, success)
                if (!success) {
                    val lastLogLine = _pluginLogs.value[pluginId]?.lineSequence()?.lastOrNull().orEmpty()
                    val message = lastLogLine.ifBlank { context.getString(R.string.plugin_registration_failed) }
                    setPluginFailed(pluginId, message)
                    forceExpanded()
                }
            }

            override fun onPluginStarted(
                    pluginId: String,
                    success: Boolean,
                    index: Int,
                    total: Int
            ) {
                // 记录插件加载结果
                if (success) {
                    setPluginSuccess(pluginId)
                } else {
                    setPluginFailed(pluginId, context.getString(R.string.plugin_verification_failed))
                    forceExpanded()
                }

                // 更新总体进度
                updateProgress(0.5f + 0.5f * (index.toFloat() / total)) // 验证和处理占50% (0.5 -> 1.0)
            }

            override fun onPluginLog(pluginId: String, message: String) {
                appendPluginLog(pluginId, message)
                val brief = message.lineSequence().firstOrNull().orEmpty().take(160)
                updatePluginMessage(pluginId, brief)
            }

            override fun onAllPluginsStarted(
                    successCount: Int,
                    totalCount: Int,
                    status: MCPStarter.PluginInitStatus
            ) {
                // 根据初始化状态显示不同的消息
                when (status) {
                    MCPStarter.PluginInitStatus.TERMINAL_SERVICE_UNAVAILABLE -> {
                        updateMessage(context.getString(R.string.plugin_terminal_service_unavailable))
                    }
                    MCPStarter.PluginInitStatus.NODEJS_MISSING -> {
                        updateMessage(context.getString(R.string.plugin_nodejs_missing))
                    }
                    MCPStarter.PluginInitStatus.BRIDGE_FAILED -> {
                        updateMessage(context.getString(R.string.plugin_bridge_failed))
                    }
                    MCPStarter.PluginInitStatus.OTHER_ERROR -> {
                        updateMessage(context.getString(R.string.plugin_other_error))
                    }
                    else -> {
                        // 所有插件加载完成
                        val successRate =
                                if (totalCount > 0) {
                                    (successCount * 100) / totalCount
                                } else {
                                    0 // 当没有部署的插件时，成功率为0
                                }

                        // 工具注册将在验证阶段自动进行，无需在此处触发

                        // 如果有插件加载失败，则特别提示可以跳过
                        if (successCount < totalCount && totalCount > 0) {
                            updateMessage(
                                    context.getString(
                                            R.string.plugin_complete_with_failures,
                                            successRate
                                    )
                            )
                        } else if (totalCount > 0) {
                            updateMessage(
                                    context.getString(R.string.plugin_complete_success, successRate)
                            )
                        } else {
                            updateMessage(context.getString(R.string.plugin_no_plugins_to_start))
                        }
                    }
                }

                val hasFailures = successCount < totalCount && totalCount > 0
                if (status != MCPStarter.PluginInitStatus.SUCCESS || hasFailures) {
                    forceExpanded()
                }
                onComplete(successCount, totalCount, status)
            }

            override fun onAllPluginsVerified(
                    verificationResults: List<MCPStarter.VerificationResult>
            ) {
                // 不需要修改这部分
            }
        }
    }
}

internal data class PluginLoadingSnapshot(
    val progress: Float,
    val message: String,
    val pluginsStarted: Int,
    val pluginsTotal: Int,
    val plugins: List<PluginInfo>,
    val pluginLogs: Map<String, String>,
)

internal data class PluginInitializationCompletion(
    val completed: Boolean,
    val successful: Boolean,
    val snapshot: PluginLoadingSnapshot,
)

internal class PluginInitializationGuard {
    class Lease internal constructor()

    private var activeLease: Lease? = null

    fun tryStart(): Lease? = synchronized(this) {
        if (activeLease != null) return@synchronized null
        Lease().also { activeLease = it }
    }

    fun finish(lease: Lease) = synchronized(this) {
        if (activeLease === lease) activeLease = null
    }

    fun isActive(lease: Lease): Boolean = synchronized(this) { activeLease === lease }

    fun <T> withActive(lease: Lease, block: () -> T): T? = synchronized(this) {
        if (activeLease !== lease) return@synchronized null
        block()
    }

    fun resetIfIdle(reset: () -> Unit): Boolean = synchronized(this) {
        if (activeLease != null) return@synchronized false
        reset()
        true
    }
}

/** 插件加载屏幕的预览视图 */
@Composable
fun PluginLoadingScreenWithState(loadingState: PluginLoadingState, modifier: Modifier = Modifier) {
    val isVisible by loadingState.isVisible.collectAsState()
    val progress by loadingState.progress.collectAsState()
    val message by loadingState.message.collectAsState()
    val pluginsStarted by loadingState.pluginsStarted.collectAsState()
    val pluginsTotal by loadingState.pluginsTotal.collectAsState()
    val plugins by loadingState.plugins.collectAsState()
    val isExpanded by loadingState.isExpanded.collectAsState()
    val pluginLogs by loadingState.pluginLogs.collectAsState()

    var selectedLogPluginId by remember { mutableStateOf<String?>(null) }

    selectedLogPluginId?.let { pluginId ->
        val logText = pluginLogs[pluginId].orEmpty()
        AlertDialog(
            onDismissRequest = { selectedLogPluginId = null },
            title = { Text(text = pluginId) },
            text = {
                SelectionContainer {
                    Text(
                        text = if (logText.isBlank()) "(no logs)" else logText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedLogPluginId = null }) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            }
        )
    }

    PluginLoadingScreen(
            isVisible = isVisible,
            progress = progress,
            message = message,
            pluginsStarted = pluginsStarted,
            pluginsTotal = pluginsTotal,
            pluginsList = plugins,
            isExpanded = isExpanded,
            onToggleExpansion = { loadingState.toggleExpansion() },
            onSkip = { loadingState.skip() },
            onPluginClick = { plugin -> selectedLogPluginId = plugin.id },
            modifier = modifier
    )
}

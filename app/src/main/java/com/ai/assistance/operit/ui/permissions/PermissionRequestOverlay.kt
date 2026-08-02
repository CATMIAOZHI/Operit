package com.ai.assistance.operit.ui.permissions

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowMetrics
import android.widget.Toast
import com.ai.assistance.operit.util.AppLogger
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.services.ServiceLifecycleOwner
import com.ai.assistance.operit.ui.floating.FloatingWindowTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionRequestContent(
    toolName: String,
    operationDescription: String,
    conversationLabel: String?,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onAlwaysDeny: () -> Unit,
    allowPermanentChoice: Boolean,
    reviewFailureKind: PermissionReviewFailureKind?,
    onMinimize: () -> Unit,
    pendingRequestCount: Int,
    colorScheme: ColorScheme? = null,
    tool: AITool? = null,
    scrollState: androidx.compose.foundation.ScrollState
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(100)
        visible = true
    }

    FloatingWindowTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.9f)
            ) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .fillMaxHeight(0.65f)
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Header with shield centered and minimize button in top-right
                        Box(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "Permission Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(36.dp)
                                    .align(Alignment.Center)
                            )
                            IconButton(
                                onClick = onMinimize,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Remove,
                                    contentDescription = stringResource(R.string.permission_request_minimize),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.permission_request),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 20.sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = stringResource(R.string.ai_assistant_requests_operation),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        val reviewFallbackText =
                            when (reviewFailureKind) {
                                PermissionReviewFailureKind.INVALID_OUTPUT ->
                                    stringResource(R.string.permission_review_fallback_invalid)
                                PermissionReviewFailureKind.TIMED_OUT ->
                                    stringResource(R.string.permission_review_fallback_timeout)
                                PermissionReviewFailureKind.REVIEWER_ERROR ->
                                    stringResource(R.string.permission_review_fallback_error)
                                null -> null
                            }
                        if (reviewFallbackText != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = reviewFallbackText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }

                        if (pendingRequestCount > 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.permission_request_queue_count,
                                    pendingRequestCount
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            PermissionDetails(
                                operationDescription = operationDescription,
                                toolName = toolName,
                                conversationLabel = conversationLabel,
                                toolParameters = tool?.parameters
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                OutlinedButton(
                                    onClick = onDeny,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Text(
                                        stringResource(R.string.permission_request_deny),
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontSize = 15.sp
                                        )
                                    )
                                }
                                if (allowPermanentChoice) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.permission_request_always_deny),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .clickable { onAlwaysDeny() }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Button(
                                    onClick = onAllow,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.permission_request_allow),
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontSize = 15.sp
                                        )
                                    )
                                }
                                if (allowPermanentChoice) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.permission_request_always_allow),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { onAlwaysAllow() }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.permission_change_reminder),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PermissionCircuitBreakerContent(
    colorScheme: ColorScheme?,
    warningCount: Int,
    onDismiss: () -> Unit,
) {
    var remainingSeconds by remember { mutableStateOf(3) }

    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            delay(1_000L)
            remainingSeconds -= 1
        }
    }

    FloatingWindowTheme(colorScheme = colorScheme) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.68f)),
            contentAlignment = Alignment.Center,
        ) {
            ElevatedCard(
                modifier =
                    Modifier.fillMaxWidth(0.88f)
                        .fillMaxHeight(0.82f)
                        .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                colors =
                    CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier =
                        Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                            .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(42.dp),
                    )
                    Text(
                        text = stringResource(R.string.permission_review_circuit_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.permission_review_circuit_message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (warningCount > 1) {
                        Text(
                            text =
                                stringResource(
                                    R.string.permission_review_circuit_combined_count,
                                    warningCount,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    CircuitBreakerGuidanceSection(
                        title = stringResource(R.string.permission_review_circuit_what_happened_title),
                        body = stringResource(R.string.permission_review_circuit_what_happened_body),
                    )
                    CircuitBreakerGuidanceSection(
                        title = stringResource(R.string.permission_review_circuit_what_to_do_title),
                        body = stringResource(R.string.permission_review_circuit_what_to_do_body),
                    )
                    if (remainingSeconds == 0) {
                        Text(
                            text = stringResource(R.string.permission_review_circuit_ready),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    Button(
                        enabled = remainingSeconds == 0,
                        onClick = onDismiss,
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 48.dp),
                    ) {
                        Text(
                            if (remainingSeconds > 0) {
                                stringResource(
                                    R.string.permission_review_circuit_countdown,
                                    remainingSeconds,
                                )
                            } else {
                                stringResource(R.string.permission_review_circuit_confirm)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CircuitBreakerGuidanceSection(
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PermissionCircuitBreakerDialogHost() {
    val pendingCount by PermissionCircuitBreakerNoticeState.pendingCount.collectAsState()
    if (pendingCount <= 0) return

    Dialog(
        onDismissRequest = {},
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        PermissionCircuitBreakerContent(
            colorScheme = null,
            warningCount = pendingCount,
            onDismiss = PermissionCircuitBreakerNoticeState::clear,
        )
    }
}

@Composable
private fun PermissionDetails(
    operationDescription: String,
    toolName: String,
    conversationLabel: String?,
    toolParameters: List<ToolParameter>? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!conversationLabel.isNullOrBlank()) {
                DetailItem(
                    label = stringResource(R.string.permission_request_conversation_source),
                    value = conversationLabel
                )
            }
            DetailItem(label = stringResource(R.string.requested_operation), value = operationDescription)
            DetailItem(label = stringResource(R.string.used_tool), value = toolName)
            
            if (!toolParameters.isNullOrEmpty()) {
                Text(
                    text = stringResource(R.string.parameter_details),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    toolParameters.forEach { param ->
                        ParameterItem(param = param)
                    }
                }
            }
        }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 15.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp
            ),
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ParameterItem(param: ToolParameter) {
    Column {
        Text(
            text = param.name,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 15.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = param.value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 15.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionRequestMinimizedIndicator(
    accessibilityLabel: String,
    pendingRequestCount: Int,
    onRestore: () -> Unit,
    onDragBy: (dx: Int, dy: Int) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    val dragModifier = Modifier.pointerInput(Unit) {
        detectDragGestures { _, dragAmount ->
            onDragBy(dragAmount.x.roundToInt(), dragAmount.y.roundToInt())
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxSize()
            .clip(CircleShape)
            .then(dragModifier)
            .semantics {
                this.contentDescription = accessibilityLabel
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                onRestore()
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.35f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Light background layer for visibility on both light/dark backgrounds
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = primaryColor.copy(alpha = 0.12f),
                        shape = CircleShape
                    )
            )
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null, // Decorative; parent Surface has the label.
                tint = primaryColor.copy(alpha = 0.76f),
                modifier = Modifier.size(28.dp)
            )
            if (pendingRequestCount > 1) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (pendingRequestCount > 9) "9+" else pendingRequestCount.toString(),
                        color = MaterialTheme.colorScheme.onError,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

class PermissionRequestOverlay(private val context: Context) {
    private val TAG = "PermissionRequestOverlay"
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var lifecycleOwner: ServiceLifecycleOwner? = null
    private var colorScheme: ColorScheme? = null
    // Currently active request data — held here, not in Composable remember
    private var currentTool: AITool? = null
    private var currentOpDesc: String? = null
    private var currentOnResult: ((PermissionRequestResult) -> Unit)? = null
    private var currentOnMinimized: (() -> Unit)? = null
    private var currentLayoutParams: WindowManager.LayoutParams? = null
    private val circuitBreakerWarningCountState = mutableIntStateOf(0)

    // Minimized state — use MutableState for Compose reactivity
    private var isMinimizedState = mutableStateOf(false)
    // Drag position cache for same-request re-minimize
    private var cachedMinimizedX = 0
    private var cachedMinimizedY = 0
    // Shield size in pixels (48dp)
    private val shieldSizePx by lazy {
        (48 * context.resources.displayMetrics.density + 0.5f).toInt()
    }
    // Default inset from edges
    private val defaultInsetPx by lazy {
        (16 * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    // Request-specific data as MutableState for Compose UI binding
    private var toolNameState = mutableStateOf("")
    private var opDescState = mutableStateOf("")
    private var toolState = mutableStateOf<AITool?>(null)
    private var conversationLabelState = mutableStateOf<String?>(null)

    /**
     * 设置颜色方案
     */
    fun setColorScheme(colorScheme: ColorScheme?) {
        this.colorScheme = colorScheme
    }

    /**
     * 检查是否有悬浮窗权限
     */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 请求悬浮窗权限
     */
    fun requestOverlayPermission() {
        if (!hasOverlayPermission()) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.overlay_permission_grant_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error requesting overlay permission", e)
            }
        }
    }

    fun show(
        tool: AITool,
        operationDescription: String,
        conversationLabel: String?,
        pendingRequestCount: StateFlow<Int>,
        reviewFailureKind: PermissionReviewFailureKind? = null,
        onResult: (PermissionRequestResult) -> Unit,
        onMinimized: (() -> Unit)? = null,
        allowPermanentChoice: Boolean = true,
    ) {
        if (overlayView != null) {
            AppLogger.e(TAG, "Cannot show permission request while an overlay is still attached")
            onResult(PermissionRequestResult.DENY)
            return
        }

        if (!hasOverlayPermission()) {
            AppLogger.e(TAG, "Cannot show overlay without permission")
            onResult(PermissionRequestResult.DENY)
            return
        }

        // Reset minimized state for new request
        isMinimizedState.value = false
        cachedMinimizedX = 0
        cachedMinimizedY = 0

        currentTool = tool
        currentOpDesc = operationDescription
        currentOnResult = onResult
        currentOnMinimized = onMinimized

        toolNameState.value = tool.name
        opDescState.value = operationDescription
        toolState.value = tool
        conversationLabelState.value = conversationLabel

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
        }
        currentLayoutParams = params

        // Capture Compose state holders for setContent closure
        val minimizedState = isMinimizedState
        val toolName = toolNameState
        val opDesc = opDescState
        val aTool = toolState
        val sourceConversation = conversationLabelState
        val scheme = colorScheme
        val onRes = currentOnResult
        val onMin = currentOnMinimized

        overlayView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                // Scroll state lifted to root so it survives minimize/restore
                val contentScrollState = rememberScrollState()
                val pendingCount by pendingRequestCount.collectAsState()

                if (minimizedState.value) {
                    // Minimized shield
                    PermissionRequestMinimizedIndicator(
                        accessibilityLabel = if (pendingCount > 1) {
                            context.getString(
                                R.string.permission_request_restore_with_count,
                                pendingCount
                            )
                        } else {
                            context.getString(R.string.permission_request_restore)
                        },
                        pendingRequestCount = pendingCount,
                        onRestore = { restore() },
                        onDragBy = { dx, dy -> handleDrag(dx, dy) }
                    )
                } else {
                    // Expanded request content
                    PermissionRequestContent(
                        toolName = toolName.value,
                        operationDescription = opDesc.value,
                        conversationLabel = sourceConversation.value,
                        colorScheme = scheme,
                        tool = aTool.value,
                        scrollState = contentScrollState,
                        onAllow = {
                            onRes?.invoke(PermissionRequestResult.ALLOW)
                        },
                        onDeny = {
                            onRes?.invoke(PermissionRequestResult.DENY)
                        },
                        onAlwaysAllow = {
                            onRes?.invoke(PermissionRequestResult.ALWAYS_ALLOW)
                        },
                        onAlwaysDeny = {
                            onRes?.invoke(PermissionRequestResult.ALWAYS_DENY)
                        },
                        allowPermanentChoice = allowPermanentChoice,
                        reviewFailureKind = reviewFailureKind,
                        pendingRequestCount = pendingCount,
                        onMinimize = {
                            onMin?.invoke()
                            minimize()
                        }
                    )
                }
            }
        }

        lifecycleOwner = ServiceLifecycleOwner().apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        overlayView?.apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }

        try {
            windowManager?.addView(overlayView, params)
            AppLogger.d(TAG, "Overlay view added successfully")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error adding overlay view", e)
            dismiss()
            onResult(PermissionRequestResult.DENY)
        }
    }

    fun showCircuitBreakerWarning(onUnavailable: () -> Unit = {}) {
        if (overlayView != null) {
            circuitBreakerWarningCountState.intValue += 1
            AppLogger.w(TAG, "Consecutive circuit-breaker warning merged into the active notice")
            return
        }
        if (!hasOverlayPermission()) {
            AppLogger.e(TAG, "Cannot show circuit-breaker warning without overlay permission")
            onUnavailable()
            return
        }
        circuitBreakerWarningCountState.intValue = 1

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
        }
        currentLayoutParams = params
        val scheme = colorScheme
        overlayView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                PermissionCircuitBreakerContent(
                    colorScheme = scheme,
                    warningCount = circuitBreakerWarningCountState.intValue,
                    onDismiss = {
                        dismiss()
                    },
                )
            }
        }
        lifecycleOwner = ServiceLifecycleOwner().apply {
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        overlayView?.apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        }
        try {
            windowManager?.addView(overlayView, params)
            AppLogger.d(TAG, "Circuit-breaker warning overlay added")
        } catch (error: Exception) {
            AppLogger.e(TAG, "Error adding circuit-breaker warning overlay", error)
            dismiss()
            onUnavailable()
        }
    }

    private fun minimize() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = currentLayoutParams ?: return

        // Use cached position for same-request re-minimize, or compute default
        if (cachedMinimizedX == 0 && cachedMinimizedY == 0) {
            val displayBounds = getDisplayBounds()
            cachedMinimizedX = displayBounds.right - shieldSizePx - defaultInsetPx
            cachedMinimizedY = displayBounds.top + defaultInsetPx
        }

        params.width = shieldSizePx
        params.height = shieldSizePx
        params.gravity = Gravity.TOP or Gravity.START
        params.x = cachedMinimizedX
        params.y = cachedMinimizedY
        // Remove touch-modal flag so touches pass through outside the shield
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error updating layout for minimize", e)
        }

        isMinimizedState.value = true

        AppLogger.d(TAG, "Overlay minimized to (${cachedMinimizedX}, ${cachedMinimizedY})")
    }

    private fun restore() {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = currentLayoutParams ?: return

        // Restore to fullscreen size first
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.x = 0
        params.y = 0
        params.gravity = Gravity.TOP or Gravity.START
        // Restore original flags (remove touch-pass-through)
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error updating layout for restore", e)
        }

        isMinimizedState.value = false

        AppLogger.d(TAG, "Overlay restored to fullscreen")
    }

    private fun handleDrag(dx: Int, dy: Int) {
        val view = overlayView ?: return
        val wm = windowManager ?: return
        val params = currentLayoutParams ?: return
        if (!isMinimizedState.value) return

        val bounds = getDisplayBounds()
        val minX = bounds.left
        val maxX = bounds.right - shieldSizePx
        val minY = bounds.top
        val maxY = bounds.bottom - shieldSizePx

        val newX = (params.x + dx).coerceIn(minX, maxX)
        val newY = (params.y + dy).coerceIn(minY, maxY)

        params.x = newX
        params.y = newY
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error updating layout for drag", e)
        }

        // Remember position for same-request re-minimize
        cachedMinimizedX = newX
        cachedMinimizedY = newY
    }

    private fun getDisplayBounds(): Rect {
        val wm = windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return Rect(
                0, 0,
                context.resources.displayMetrics.widthPixels,
                context.resources.displayMetrics.heightPixels
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics: WindowMetrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val bounds = metrics.bounds
            return Rect(
                bounds.left + insets.left,
                bounds.top + insets.top,
                bounds.right - insets.right,
                bounds.bottom - insets.bottom
            )
        } else {
            val display = wm.defaultDisplay
                ?: return Rect(
                    0, 0,
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels
                )
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            return Rect(0, 0, size.x, size.y)
        }
    }

    fun dismiss() {
        val viewToRemove: View? = overlayView
        val wm = windowManager
        val owner = lifecycleOwner

        // Clear references first so no stale state lingers
        overlayView = null
        lifecycleOwner = null
        currentLayoutParams = null
        currentTool = null
        currentOpDesc = null
        currentOnResult = null
        currentOnMinimized = null
        circuitBreakerWarningCountState.intValue = 0
        conversationLabelState.value = null
        isMinimizedState.value = false
        cachedMinimizedX = 0
        cachedMinimizedY = 0

        try {
            owner?.let {
                it.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                it.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                it.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }

            viewToRemove?.let { view ->
                try {
                    wm?.removeView(view)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error removing overlay view", e)
                }
            }
            AppLogger.d(TAG, "Overlay view dismissed")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during overlay dismiss lifecycle", e)
        } finally {
            windowManager = null
        }
    }
}

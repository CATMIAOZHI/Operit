package com.ai.assistance.operit.ui.floating.ui.window.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.pet.PetPreferences
import com.ai.assistance.operit.pet.PetTasks
import com.ai.assistance.operit.pet.isReady
import com.ai.assistance.operit.ui.floating.FloatContext
import com.ai.assistance.operit.ui.floating.FloatingMode
import com.ai.assistance.operit.ui.floating.ui.window.components.FloatingChatWindowInputControls
import com.ai.assistance.operit.ui.floating.ui.window.viewmodel.rememberFloatingChatWindowModeViewModel
import com.ai.assistance.operit.ui.main.MainActivity

/** Both window sizes keep the same conversation, composer and list composition. */
@Composable
internal fun FloatingConversationSurface(floatContext: FloatContext, fullscreen: Boolean) {
    val viewModel = rememberFloatingChatWindowModeViewModel(floatContext)
    val core = floatContext.chatService?.getChatCore()
    val chatId = core?.currentChatId?.collectAsState()?.value
    val histories = core?.chatHistories?.collectAsState()?.value.orEmpty()
    val title = histories.firstOrNull { it.id == chatId }?.title
        ?.takeIf(String::isNotBlank) ?: stringResource(R.string.floating_empty_title)
    var menu by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val dragHint = stringResource(R.string.floating_drag_hint)
    val resizeHint = stringResource(R.string.floating_resize_hint)
    fun releaseFocus() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        floatContext.onInputFocusRequest?.invoke(false)
    }
    fun changeMode(mode: FloatingMode) {
        releaseFocus()
        floatContext.onModeChange(mode)
    }

    LaunchedEffect(floatContext.windowWidthState, floatContext.windowHeightState, floatContext.windowScale) {
        viewModel.syncWindowState()
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = if (fullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (fullscreen) 0.dp else 8.dp,
        border = if (fullscreen) null else androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant
        ),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .then(if (fullscreen) Modifier else Modifier
                                .semantics { contentDescription = dragHint }
                                .pointerInput(floatContext) {
                                    detectDragGestures(
                                        onDragEnd = { floatContext.saveWindowState?.invoke() },
                                        onDragCancel = { floatContext.saveWindowState?.invoke() },
                                    ) { change, amount ->
                                        change.consume()
                                        floatContext.onMove(amount.x, amount.y, 1f)
                                    }
                                }),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        if (!fullscreen) {
                            Box(Modifier.width(24.dp).height(3.dp).background(
                                MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)
                            ))
                            Spacer(Modifier.height(5.dp))
                        }
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.MoreHoriz, stringResource(R.string.floating_more_actions))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.chat_history)) },
                                leadingIcon = { Icon(Icons.Default.History, null) },
                                onClick = { menu = false; releaseFocus(); history = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_chat)) },
                                leadingIcon = { Icon(Icons.Default.Add, null) },
                                onClick = { menu = false; core?.createNewChat() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.floating_voice_mode)) },
                                leadingIcon = { Icon(Icons.Default.Mic, null) },
                                onClick = {
                                    menu = false
                                    floatContext.voiceAutoTimeout = false
                                    floatContext.voiceMode = true
                                    changeMode(FloatingMode.FULLSCREEN)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.floating_minimize)) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                                onClick = {
                                    menu = false
                                    val service = floatContext.chatService
                                    val pet = service?.let { PetPreferences.get(it).settings.value }
                                    val petVisible = pet?.isReady == true && service != null &&
                                        if (PetTasks.get(service).appVisible.value) pet.inApp else pet.overlay
                                    if (petVisible) {
                                        releaseFocus()
                                        service?.minimizeToPet()
                                    }
                                    else changeMode(FloatingMode.BALL)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.floating_back_to_main)) },
                                leadingIcon = { Icon(Icons.Default.Home, null) },
                                onClick = {
                                    menu = false
                                    core?.syncCurrentChatIdToGlobal()
                                    floatContext.chatService?.let { service ->
                                        service.startActivity(Intent(service, MainActivity::class.java).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                        })
                                    }
                                    floatContext.onClose()
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = { changeMode(if (fullscreen) FloatingMode.WINDOW else FloatingMode.FULLSCREEN) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            stringResource(if (fullscreen) R.string.floating_compact else R.string.floating_fullscreen),
                        )
                    }
                    IconButton(onClick = floatContext.onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, stringResource(R.string.floating_close))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (floatContext.messages.isEmpty()) {
                        Column(
                            Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text(stringResource(R.string.floating_empty_title), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.floating_empty_body), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    } else {
                        ChatMessagesView(floatContext, viewModel)
                    }
                }
                ProcessingStatusIndicator(floatContext)
                FloatingChatWindowInputControls(floatContext, viewModel)
                if (!fullscreen) {
                    Box(
                        Modifier.fillMaxWidth().height(24.dp)
                            .semantics { contentDescription = resizeHint }
                            .pointerInput(density, configuration.screenWidthDp, configuration.screenHeightDp) {
                                detectDragGestures(
                                    onDragEnd = { floatContext.saveWindowState?.invoke() },
                                    onDragCancel = { floatContext.saveWindowState?.invoke() },
                                ) { change, amount ->
                                    change.consume()
                                    with(density) {
                                        floatContext.onResize(
                                            (floatContext.windowWidthState + amount.x.toDp()).coerceIn(
                                                minOf(300.dp, (configuration.screenWidthDp - 16).dp),
                                                (configuration.screenWidthDp - 16).dp,
                                            ),
                                            (floatContext.windowHeightState + amount.y.toDp()).coerceIn(
                                                300.dp, maxOf(300.dp, (configuration.screenHeightDp * 0.8f).dp)
                                            ),
                                        )
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(36.dp).height(4.dp).background(
                            MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)
                        ))
                    }
                }
            }
            RecentChatSelectorOverlay(floatContext, history) { history = false }
        }
    }
}

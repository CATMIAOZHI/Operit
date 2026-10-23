package com.ai.assistance.operit.ui.features.chat.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.AiReference
import com.ai.assistance.operit.data.model.ChatMessage
import com.ai.assistance.operit.data.model.ChatMessageDisplayMode
import com.ai.assistance.operit.data.model.ChatMessageLocatorPreview
import com.ai.assistance.operit.data.preferences.UserPreferencesManager

import androidx.compose.ui.window.PopupProperties

import androidx.compose.material.icons.filled.AutoFixHigh

import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.ui.draw.alpha
import com.ai.assistance.operit.api.chat.llmprovider.MediaLinkParser
import com.ai.assistance.operit.plugins.chatmessage.ChatMessageMenuDialogRequest
import com.ai.assistance.operit.plugins.chatmessage.ChatMessageMenuItemDefinition
import com.ai.assistance.operit.plugins.chatmessage.ChatMessageMenuItemParams
import com.ai.assistance.operit.plugins.chatmessage.ChatMessageMenuItemRegistry
import com.ai.assistance.operit.ui.common.composedsl.ToolPkgComposeDslDialogHost
import com.ai.assistance.operit.ui.common.icons.MaterialIconNameResolver
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.ui.common.markdown.markdownToPlainTextForCopy
import com.ai.assistance.operit.ui.features.chat.components.style.cursor.CursorStyleChatMessage
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleImageStyleConfig
import com.ai.assistance.operit.ui.features.chat.components.style.bubble.BubbleStyleChatMessage
import com.ai.assistance.operit.util.ChatMarkupRegex
import com.ai.assistance.operit.util.ChatUtils
import com.ai.assistance.operit.util.LatexMathMlConverter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.ai.assistance.operit.util.stream.asFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 清理复制文本中的内部标记，保留Markdown格式和纯文本内容
 */
internal fun cleanMessageContentForCopy(content: String): String {
    return content
        // Provider元数据必须保留在消息中供后续轮次使用，但不能暴露在复制内容中
        .let(ChatMarkupRegex::removeGeminiThoughtSignatureMeta)
        .let(ChatMarkupRegex::removeOpenAiResponsesReasoningMeta)
        // Keep copy cleanup aligned with the renderer and executable-markup visibility grammar.
        .let(ChatUtils::removeThinkingContent)
        // Orbit's break between messages it sends one after another copies as a new line.
        .replace(OrbitSpeech.messageBreak, "\n")
        // 移除状态标签
        .replace(ChatMarkupRegex.statusTag, "")
        .replace(ChatMarkupRegex.statusSelfClosingTag, "")
        // 移除工具标签
        .replace(ChatMarkupRegex.toolTag, "")
        .replace(ChatMarkupRegex.toolSelfClosingTag, "")
        // 移除工具结果标签
        .replace(ChatMarkupRegex.toolResultTag, "")
        .replace(ChatMarkupRegex.toolResultSelfClosingTag, "")
        // 移除emotion标签
        .replace(ChatMarkupRegex.emotionTag, "")
        // 移除附件与工作区上下文
        .replace(ChatMarkupRegex.workspaceAttachmentTag, "")
        .replace(ChatMarkupRegex.attachmentTag, "")
        .replace(ChatMarkupRegex.attachmentSelfClosingTag, "")
        // 移除多媒体链接标签
        .let(MediaLinkParser::removeImageLinks)
        .let(MediaLinkParser::removeMediaLinks)
        .trim()
}
private fun isHiddenUserPlaceholder(message: ChatMessage): Boolean {
    return message.sender == "user" &&
        message.displayMode == ChatMessageDisplayMode.HIDDEN_PLACEHOLDER
}

enum class ChatStyle {
    CURSOR,
    BUBBLE
}

@Composable
fun ChatArea(
    chatHistory: List<ChatMessage>,
    currentChatId: String,
    aiReferences: List<AiReference> = emptyList(),
    isLoading: Boolean,
    processMetadata: List<com.ai.assistance.operit.data.model.ChatMessageProcessMetadata> = emptyList(),
    onLoadProcess: (suspend (Long) -> Unit)? = null,
    transcriptReady: Boolean = true,
    activeRunStartedAt: Long? = null,
    /** The running turn answers what the user said themselves (Orbit shows its typing dots only then). */
    orbitUserWaiting: Boolean = false,
    enableDialogs: Boolean = true,
    allowTranscriptMutation: Boolean = true,
    enableToolDetailDialogs: Boolean? = null,  // 工具详情弹窗开关，null 时跟随 enableDialogs
    userMessageColor: Color,
    aiMessageColor: Color,
    userTextColor: Color,
    aiTextColor: Color,
    systemMessageColor: Color,
    systemTextColor: Color,
    thinkingBackgroundColor: Color,
    thinkingTextColor: Color,
    hasBackgroundImage: Boolean = false,
    modifier: Modifier = Modifier,
    onSelectMessageToEdit: ((Int, ChatMessage, String) -> Unit)? = null,
    onCopyMessage: ((ChatMessage) -> Unit)? = null,
    onDeleteMessage: ((Int) -> Unit)? = null,
    onDeleteCurrentMessageVariant: ((Int) -> Unit)? = null,
    onDeleteMessagesFrom: ((Int) -> Unit)? = null,
    onRollbackToMessage: ((Int) -> Unit)? = null, // 回滚到指定消息的回调
    onRegenerateMessage: ((Int) -> Unit)? = null,
    onSwitchMessageVariant: ((Int, Int) -> Unit)? = null,
    onSpeakMessage: ((String) -> Unit)? = null, // 添加朗读回调参数
    onAutoReadMessage: ((String) -> Unit)? = null, // 添加自动朗读回调参数
    onReplyToMessage: ((ChatMessage) -> Unit)? = null, // 添加回复回调参数
    onToggleFavoriteMessage: ((Long, Boolean) -> Unit)? = null,
    onCreateBranch: ((Long) -> Unit)? = null, // 添加创建分支回调参数
    onInsertSummary: ((ChatMessage) -> Unit)? = null, // 添加插入总结回调参数
    onMentionRoleFromAvatar: ((String) -> Unit)? = null, // 长按角色头像提及
    autoScrollToBottom: Boolean = true,
    onAutoScrollToBottomChange: ((Boolean) -> Unit)? = null,
    hasOlderDisplayHistory: Boolean = false,
    hasNewerDisplayHistory: Boolean = false,
    isLoadingDisplayWindow: Boolean = false,
    onLoadOlderDisplayWindow: (() -> Unit)? = null,
    onLoadNewerDisplayWindow: (() -> Unit)? = null,
    onShowLatestDisplayWindow: (() -> Unit)? = null,
    loadMessageLocatorEntries: (suspend (String, String) -> List<ChatMessageLocatorPreview>)? = null,
    onRevealMessageForLocator: (suspend (Long) -> Boolean)? = null,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
    chatStyle: ChatStyle = ChatStyle.CURSOR, // 新增参数，默认为CURSOR风格
    cursorUserBubbleLiquidGlass: Boolean = false,
    cursorUserBubbleWaterGlass: Boolean = false,
    bubbleUserBubbleLiquidGlass: Boolean = false,
    bubbleUserBubbleWaterGlass: Boolean = false,
    bubbleAiBubbleLiquidGlass: Boolean = false,
    bubbleAiBubbleWaterGlass: Boolean = false,
    isMultiSelectMode: Boolean = false, // 是否处于多选模式
    selectedMessageIndices: Set<Int> = emptySet(), // 已选中的消息索引集合
    onToggleMultiSelectMode: ((Int?) -> Unit)? = null, // 切换多选模式的回调，可传入要初始选中的消息索引
    onToggleMessageSelection: ((Int) -> Unit)? = null, // 切换消息选中状态的回调
    horizontalPadding: Dp = 16.dp, // 水平内边距，可自定义
    bubbleUserImageStyle: BubbleImageStyleConfig? = null,
    bubbleAiImageStyle: BubbleImageStyleConfig? = null,
    bubbleUserRoundedCornersEnabled: Boolean = true,
    bubbleAiRoundedCornersEnabled: Boolean = true,
    bubbleUserContentPaddingLeft: Float = 12f,
    bubbleUserContentPaddingRight: Float = 12f,
    bubbleAiContentPaddingLeft: Float = 12f,
    bubbleAiContentPaddingRight: Float = 12f,
    showChatFloatingDotsAnimation: Boolean = true,
) {
    if (!transcriptReady) {
        // A chat switch usually finishes within a few frames; a spinner only for a slow one, so a
        // quick switch does not flash it.
        var slow by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); slow = true }
        Box(modifier) { if (slow) CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val preferencesManager = remember { UserPreferencesManager.getInstance(context) }
    val showMessageTokenStats by
        preferencesManager.showMessageTokenStats.collectAsState(initial = true)
    val showMessageTimingStats by
        preferencesManager.showMessageTimingStats.collectAsState(initial = true)
    val showMessageTimestamp by
        preferencesManager.showMessageTimestamp.collectAsState(initial = true)
    // An Orbit chat draws every message on its own: there is no process to fold.
    val orbitLook = LocalOrbitChatLook.current
    val responseProcessState =
        rememberResponseProcessState(chatHistory, currentChatId, !isMultiSelectMode && !orbitLook, processMetadata, onLoadProcess)
    val lastMessage = chatHistory.lastOrNull()
    // While a reply is being written it is the newest of the assistant's rows. The rows an Orbit
    // chat shows are cleaned copies without a stream, so this is the only way to tell.
    val lastAiIndex = remember(chatHistory) { chatHistory.indexOfLast { it.sender == "ai" } }
    var hasLastAiMessageStartedStreaming by remember(lastMessage?.timestamp) {
        mutableStateOf(lastMessage?.run { sender == "ai" && content.isNotBlank() } == true)
    }

    val messagesCount = chatHistory.size
    // Orbit's cards sit after the shown message they followed; hidden steps do not count.
    val orbitShown = remember(chatHistory, orbitLook) {
        if (orbitLook) chatHistory.indices.filterNot { OrbitSpeech.hidden(chatHistory[it]) } else emptyList()
    }
    val orbitShownAt = remember(orbitShown) { orbitShown.withIndex().associate { it.value to it.index } }
    val orbitShownTimes = remember(orbitShown, chatHistory) { orbitShown.mapTo(HashSet()) { chatHistory[it].timestamp } }
    // The loaded page's edges; a card placed beyond them waits for its page.
    val orbitLoadedFrom = if (hasOlderDisplayHistory) chatHistory.firstOrNull()?.timestamp ?: Long.MIN_VALUE else Long.MIN_VALUE
    val orbitLoadedUntil = if (hasNewerDisplayHistory) chatHistory.lastOrNull()?.timestamp ?: Long.MAX_VALUE else Long.MAX_VALUE
    val orbitCardReply = remember(onReplyToMessage) {
        onReplyToMessage?.let { reply ->
            { summary: String, time: Long -> reply(ChatMessage(sender = "ai", content = summary, timestamp = time)) }
        }
    }
    // A card belongs to the conversation that opened it: leaving that conversation closes it before
    // the next one composes, so coming back never flashes a card the user already left behind.
    DisposableEffect(currentChatId) { onDispose { SubagentDetailHost.clear() } }

    // The subagent card the user opened lives above the transcript: the message that owns a card can
    // be dropped when older history loads, and the floating card must not go with it.
    SubagentDetailHost.requestFor(currentChatId)?.let { request ->
        SubagentDetailCard(request = request, onDismiss = { SubagentDetailHost.dismiss(currentChatId) })
    }

    LaunchedEffect(lastMessage?.timestamp, lastMessage?.contentStream) {
        val lastAiMessageHasStaticContent =
            lastMessage?.let { it.sender == "ai" && it.content.isNotBlank() } == true
        hasLastAiMessageStartedStreaming = lastAiMessageHasStaticContent

        val shouldAwaitFirstChunk =
            lastMessage?.let {
                it.sender == "ai" && it.content.isBlank() && it.contentStream != null
            } == true
        val stream = lastMessage?.contentStream

        if (!lastAiMessageHasStaticContent && shouldAwaitFirstChunk && stream != null) {
            try {
                if (stream.asFlow().firstOrNull { it.isNotEmpty() } != null) {
                    hasLastAiMessageStartedStreaming = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // This observer only controls the placeholder. The sending service reports
                // errors and persists partial content; a failed turn must not crash Compose.
            }
        }
    }

    val isLatestMessageVisible = messagesCount > 0 && !hasNewerDisplayHistory
    val showLoadingIndicator =
        isLatestMessageVisible &&
            isLoading &&
            (
                lastMessage?.sender == "user" ||
                    lastMessage?.let {
                        it.sender == "ai" &&
                            it.content.isBlank() &&
                            !hasLastAiMessageStartedStreaming
                    } == true
            )
    val shouldHideLastAiMessage =
        isLatestMessageVisible &&
            showLoadingIndicator &&
            chatStyle == ChatStyle.BUBBLE &&
            lastMessage?.sender == "ai"
    VirtualTranscript(
        chatId = currentChatId.orEmpty(),
        messages = chatHistory,
        process = responseProcessState,
        following = autoScrollToBottom,
        onFollowingChange = onAutoScrollToBottomChange,
        hasOlder = hasOlderDisplayHistory,
        hasNewer = hasNewerDisplayHistory,
        loadingPage = isLoadingDisplayWindow,
        onOlder = onLoadOlderDisplayWindow,
        onNewer = onLoadNewerDisplayWindow,
        // An Orbit message stays one piece, so it swipes and carries its reaction as a whole.
        splitMarkdown = !orbitLook && (chatStyle == ChatStyle.CURSOR ||
            (bubbleAiImageStyle == null && !bubbleAiBubbleLiquidGlass && !bubbleAiBubbleWaterGlass)),
        skipMessage = if (orbitLook) OrbitSpeech::hidden else null,
        onLatest = onShowLatestDisplayWindow,
        loadLocator = loadMessageLocatorEntries,
        reveal = onRevealMessageForLocator,
        onFavorite = onToggleFavoriteMessage,
        textColor = aiTextColor,
        horizontalPadding = horizontalPadding,
        topPadding = topPadding,
        bottomPadding = bottomPadding,
        modifier = modifier,
        renderMessage = { renderIndex ->
            val renderedMessage = chatHistory[renderIndex]
            // Cards from before the first message sit above it, where they showed.
            if (orbitLook && !hasOlderDisplayHistory && orbitShownAt[renderIndex] == 0 &&
                com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.first != false) {
                LocalOrbitChatFooter.current?.let { cards ->
                    androidx.compose.runtime.CompositionLocalProvider(LocalOrbitLiveCardReply provides orbitCardReply) {
                        cards(aiMessageColor, OrbitCardSlot(Long.MIN_VALUE, Long.MIN_VALUE, renderedMessage.timestamp, orbitShownTimes, orbitLoadedFrom, orbitLoadedUntil))
                    }
                }
            }
                        MessageItem(
                            index = renderIndex,
                            currentChatId = currentChatId,
                            message = renderedMessage,
                            showAssistantHeader = !isAssistantContinuation(chatHistory, renderIndex) &&
                                com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.first != false,
                            enableDialogs = enableDialogs,
                            allowTranscriptMutation = allowTranscriptMutation,
                            enableToolDetailDialogs = enableToolDetailDialogs,
                            userMessageColor = userMessageColor,
                            aiMessageColor = aiMessageColor,
                            userTextColor = userTextColor,
                            aiTextColor = aiTextColor,
                            systemMessageColor = systemMessageColor,
                            systemTextColor = systemTextColor,
                            thinkingBackgroundColor = thinkingBackgroundColor,
                            thinkingTextColor = thinkingTextColor,
                            onSelectMessageToEdit = onSelectMessageToEdit,
                            onCopyMessage = onCopyMessage,
                            onDeleteMessage = if (allowTranscriptMutation) onDeleteMessage else null,
                            onDeleteCurrentMessageVariant = onDeleteCurrentMessageVariant,
                            onDeleteMessagesFrom = onDeleteMessagesFrom,
                            onRollbackToMessage = onRollbackToMessage,
                            onRegenerateMessage = onRegenerateMessage,
                            onSwitchMessageVariant = onSwitchMessageVariant,
                            onSpeakMessage = onSpeakMessage,
                            onReplyToMessage = onReplyToMessage,
                            onToggleFavoriteMessage = onToggleFavoriteMessage,
                            onCreateBranch = onCreateBranch,
                            onInsertSummary = onInsertSummary,
                            onMentionRoleFromAvatar = onMentionRoleFromAvatar,
                            chatStyle = chatStyle,
                            showMessageTokenStats = showMessageTokenStats && !orbitLook,
                            showMessageTimingStats = showMessageTimingStats && !orbitLook,
                            showMessageTimestamp = showMessageTimestamp && !orbitLook,
                            cursorUserBubbleLiquidGlass = cursorUserBubbleLiquidGlass,
                            cursorUserBubbleWaterGlass = cursorUserBubbleWaterGlass,
                            bubbleUserBubbleLiquidGlass = bubbleUserBubbleLiquidGlass,
                            bubbleUserBubbleWaterGlass = bubbleUserBubbleWaterGlass,
                            bubbleAiBubbleLiquidGlass = bubbleAiBubbleLiquidGlass,
                            bubbleAiBubbleWaterGlass = bubbleAiBubbleWaterGlass,
                            isHidden = shouldHideLastAiMessage && renderIndex == messagesCount - 1,
                            isWriting = isLoading && renderIndex == lastAiIndex,
                            isMultiSelectMode = isMultiSelectMode,
                            isSelected = selectedMessageIndices.contains(renderIndex),
                            onToggleSelection = { onToggleMessageSelection?.invoke(renderIndex) },
                            onToggleMultiSelectMode = onToggleMultiSelectMode,
                            messageIndex = renderIndex,
                            bubbleUserImageStyle = bubbleUserImageStyle,
                            bubbleAiImageStyle = bubbleAiImageStyle,
                            bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                            bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                            bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
                            bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
                            bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
                            bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
                        )
            if (orbitLook) {
                val cards = LocalOrbitChatFooter.current
                val at = orbitShownAt[renderIndex]
                if (cards != null && at != null &&
                    com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.last != false) {
                    val last = at == orbitShown.lastIndex
                    val time = renderedMessage.timestamp
                    // After the last loaded message, cards going by their time wait for the newer page
                    // where they belong; cards already placed after this message stay.
                    val slot = if (last && hasNewerDisplayHistory) OrbitCardSlot(time, Long.MAX_VALUE, Long.MAX_VALUE, orbitShownTimes, orbitLoadedFrom, orbitLoadedUntil)
                    else OrbitCardSlot(time, time, if (last) null else chatHistory[orbitShown[at + 1]].timestamp, orbitShownTimes, orbitLoadedFrom, orbitLoadedUntil)
                    androidx.compose.runtime.CompositionLocalProvider(LocalOrbitLiveCardReply provides orbitCardReply) {
                        cards(aiMessageColor, slot)
                    }
                }
            }
        },
        footer = {
            if (orbitLook) {
                // A chat with no shown message yet still shows its cards.
                if (orbitShown.isEmpty() && !hasNewerDisplayHistory && !hasOlderDisplayHistory) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalOrbitLiveCardReply provides orbitCardReply) {
                        LocalOrbitChatFooter.current?.invoke(aiMessageColor, OrbitCardSlot(Long.MIN_VALUE, Long.MIN_VALUE, null, emptySet()))
                    }
                }
                // Orbit is typing for as long as it works on a reply to the user, like a person would be.
                // A reply started by a reaction, an approval, a background task or a subagent result
                // shows no dots; the status under Orbit's name still says what it is doing.
                if (isLoading && orbitUserWaiting && !hasNewerDisplayHistory) {
                    val typing = stringResource(R.string.orbit_typing, currentOrbitName())
                    Surface(
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp),
                        color = aiMessageColor,
                        modifier = Modifier.padding(top = 4.dp).heightIn(min = 44.dp).semantics {
                            contentDescription = typing
                            liveRegion = LiveRegionMode.Polite
                        },
                    ) {
                        Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) { LoadingDotsIndicator(aiTextColor) }
                    }
                }
            } else if (showLoadingIndicator && showChatFloatingDotsAnimation) {
                Box(Modifier.padding(start = 16.dp)) { LoadingDotsIndicator(aiTextColor) }
            }
            if (!orbitLook && isLoading && !hasNewerDisplayHistory && activeRunStartedAt != null) {
                LiveResponseTimer(activeRunStartedAt)
            }
        },
    )
}
/** 单个消息项组件 将消息渲染逻辑提取到单独的组件，减少重组范围 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageItem(
    index: Int,
    currentChatId: String,
    message: ChatMessage,
    showAssistantHeader: Boolean,
    enableDialogs: Boolean,
    allowTranscriptMutation: Boolean,
    enableToolDetailDialogs: Boolean? = null,
    userMessageColor: Color,
    aiMessageColor: Color,
    userTextColor: Color,
    aiTextColor: Color,
    systemMessageColor: Color,
    systemTextColor: Color,
    thinkingBackgroundColor: Color,
    thinkingTextColor: Color,
    onSelectMessageToEdit: ((Int, ChatMessage, String) -> Unit)?,
    onCopyMessage: ((ChatMessage) -> Unit)?,
    onDeleteMessage: ((Int) -> Unit)?,
    onDeleteCurrentMessageVariant: ((Int) -> Unit)?,
    onDeleteMessagesFrom: ((Int) -> Unit)?,
    onRollbackToMessage: ((Int) -> Unit)? = null, // 回滚到指定消息的回调
    onRegenerateMessage: ((Int) -> Unit)? = null,
    onSwitchMessageVariant: ((Int, Int) -> Unit)? = null,
    onSpeakMessage: ((String) -> Unit)? = null, // 添加朗读回调
    onReplyToMessage: ((ChatMessage) -> Unit)? = null, // 添加回复回调
    onToggleFavoriteMessage: ((Long, Boolean) -> Unit)? = null,
    onCreateBranch: ((Long) -> Unit)? = null, // 添加创建分支回调
    onInsertSummary: ((ChatMessage) -> Unit)? = null, // 添加插入总结回调
    onMentionRoleFromAvatar: ((String) -> Unit)? = null, // 长按角色头像提及
    chatStyle: ChatStyle, // 新增参数
    showMessageTokenStats: Boolean = false,
    showMessageTimingStats: Boolean = false,
    showMessageTimestamp: Boolean = false,
    cursorUserBubbleLiquidGlass: Boolean = false,
    cursorUserBubbleWaterGlass: Boolean = false,
    bubbleUserBubbleLiquidGlass: Boolean = false,
    bubbleUserBubbleWaterGlass: Boolean = false,
    bubbleAiBubbleLiquidGlass: Boolean = false,
    bubbleAiBubbleWaterGlass: Boolean = false,
    isHidden: Boolean = false, // 新增参数控制隐藏
    isWriting: Boolean = false, // 正在生成的那条回复
    isMultiSelectMode: Boolean = false, // 是否处于多选模式
    isSelected: Boolean = false, // 是否被选中
    onToggleSelection: (() -> Unit)? = null, // 切换选中状态的回调
    onToggleMultiSelectMode: ((Int?) -> Unit)? = null, // 切换多选模式的回调，可传入要初始选中的消息索引
    messageIndex: Int, // 消息索引，用于进入多选时自动选中
    bubbleUserImageStyle: BubbleImageStyleConfig? = null,
    bubbleAiImageStyle: BubbleImageStyleConfig? = null,
    bubbleUserRoundedCornersEnabled: Boolean = true,
    bubbleAiRoundedCornersEnabled: Boolean = true,
    bubbleUserContentPaddingLeft: Float = 12f,
    bubbleUserContentPaddingRight: Float = 12f,
    bubbleAiContentPaddingLeft: Float = 12f,
    bubbleAiContentPaddingRight: Float = 12f,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showMessageInfoDialog by remember { mutableStateOf(false) }
    var showHiddenUserMessageDialog by remember { mutableStateOf(false) }
    var showDeleteMessageConfirmDialog by remember { mutableStateOf(false) }
    var copyPreviewText by remember { mutableStateOf<String?>(null) }
    var toolPkgDialogRequest by remember(currentChatId, message.timestamp) {
        mutableStateOf<ChatMessageMenuDialogRequest?>(null)
    }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val messageInteractionSource = remember { MutableInteractionSource() }

    // Collaboration events are transport input to the model, not editable user messages.
    val isActionable = !message.displayMode.isCollaborationEvent &&
        (message.sender == "user" || message.sender == "ai")
    val isHiddenUserMessage = isHiddenUserPlaceholder(message)
    val orbitLook = LocalOrbitChatLook.current
    val orbitUserReactions = LocalOrbitUserReactions.current
    val forkOrbitMessage = com.ai.assistance.operit.ui.features.orbit.LocalOrbitForkMessage.current
    // Developer view of an Orbit chat: label the rows the Orbit chat would hide.
    val showHiddenRows = LocalShowHiddenChatRows.current
    val quietOrbitReply = showHiddenRows && message.sender == "ai" && message.contentStream == null &&
        !message.displayMode.isCollaborationEvent &&
        com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.first != false &&
        OrbitSpeech.of(message.content).isBlank()
    var showEmojiPicker by remember { mutableStateOf(false) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    // The bubble the long-press menu is for: a card inside the reply, or null for the message itself.
    var menuPiece by remember { mutableStateOf<OrbitPiece?>(null) }
    val reactionKey = menuPiece?.reactionKey(orbitUserReactions?.byMessage) ?: message.timestamp.toString()
    val canReactToOrbit = orbitLook && message.sender == "ai" && orbitUserReactions != null && isActionable &&
        !isMultiSelectMode && !isWriting && message.contentStream == null
    // Double-tapping one of Orbit's bubbles likes it, like a quick tapback.
    fun like(key: String, said: String?) {
        val reactions = orbitUserReactions ?: return
        if (reactions.byMessage[key] != OrbitQuickLike) {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
            OrbitEmojiUsage.record(context, OrbitQuickLike)
            reactions.toggle(key, OrbitQuickLike, said)
        }
    }
    val quickLike: (() -> Unit)? = if (canReactToOrbit) { { like(message.timestamp.toString(), null) } } else null
    /** What a reply to [piece] quotes: a card's summary, or the message's words without its cards. */
    fun replyTarget(piece: OrbitPiece?): ChatMessage = when {
        piece?.summary != null -> message.copy(content = piece.summary, contentStream = null)
        message.sender == "ai" && orbitLook -> message.copy(content = OrbitSpeech.splitCards(message.content).first, contentStream = null)
        else -> message
    }
    var pluginMenuItems by remember(currentChatId, message.timestamp) {
        mutableStateOf<List<ChatMessageMenuItemDefinition>>(emptyList())
    }
    // Developer view: the message exactly as stored (thinking, tool calls, hidden tags), read-only.
    var showRawMessage by remember { mutableStateOf(false) }

    fun buildPluginMenuItemParams(): ChatMessageMenuItemParams =
        ChatMessageMenuItemParams(
            context = context,
            chatId = currentChatId,
            messageIndex = messageIndex,
            message = message
        )
    val openMessageMenu: () -> Unit = {
        if (!isMultiSelectMode && isActionable) {
            menuPiece = null
            pluginMenuItems =
                if (!isHiddenUserMessage && allowTranscriptMutation) {
                    ChatMessageMenuItemRegistry.createMenuItems(buildPluginMenuItemParams())
                } else {
                    emptyList()
                }
            showContextMenu = true
        }
    }
    // In an Orbit reply each bubble (words, a card, a Dynamic UI card) takes its own gestures.
    val pieceActions: OrbitPieceActions? =
        if (canReactToOrbit && !message.displayMode.isCollaborationEvent) {
            OrbitPieceActions(
                like = { piece -> like(piece.reactionKey(orbitUserReactions?.byMessage), piece.summary) },
                menu = { piece ->
                    openMessageMenu()
                    menuPiece = piece.takeIf { it.summary != null }
                },
                reply = if (allowTranscriptMutation && onReplyToMessage != null) {
                    { piece -> onReplyToMessage.invoke(replyTarget(piece.takeIf { it.summary != null })) }
                } else null,
            )
        } else null
    // The statistics are drawn outside the card, and only by the row that closes it: drawn inside,
    // they landed on the reply's own background whenever the row shared its turn's card, and a reply
    // written as several messages (waifu mode) carries the same totals on every one of them. The
    // version switcher belongs to its message, so it still shows where the statistics do not.
    val closesCard = LocalTranscriptCardEnds.current.last
    val footer: @Composable () -> Unit = {
        if (message.sender == "ai" &&
            com.ai.assistance.operit.ui.common.markdown.LocalTranscriptMarkdownSlice.current?.last != false &&
            LocalResponseMessageSection.current != ResponseMessageSection.HEADER &&
            // A chat with Orbit reads like messages between people: no version switcher under them.
            ((message.variantCount > 1 && !orbitLook) ||
                (closesCard &&
                    ((showMessageTokenStats && hasDisplayableTokenStats(message)) ||
                        (showMessageTimingStats && hasDisplayableTimingStats(message)) ||
                        (showMessageTimestamp && hasDisplayableMessageTimestamp(message)))))
        ) {
            MessageFooterBar(
                message = message,
                showMessageTokenStats = showMessageTokenStats && closesCard,
                showMessageTimingStats = showMessageTimingStats && closesCard,
                showMessageTimestamp = showMessageTimestamp && closesCard,
                allowVariantSelection = allowTranscriptMutation,
                onSelectVariant = { targetVariantIndex ->
                    onSwitchMessageVariant?.invoke(index, targetVariantIndex)
                },
            )
        }
    }

    Box(
        modifier =
        Modifier
            .alpha(if (isHidden) 0f else 1f)
            .then(
                if (isSelected) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                } else Modifier
            )
            .combinedClickable(
                interactionSource = messageInteractionSource,
                indication = null,
                onClick = {
                    if (isMultiSelectMode && isActionable) {
                        onToggleSelection?.invoke()
                    } else if (!isMultiSelectMode && enableDialogs && isHiddenUserMessage) {
                        showHiddenUserMessageDialog = true
                    }
                },
                onLongClick = openMessageMenu,
                onDoubleClick = quickLike,
            ),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalOrbitMessageGestures provides
                if (orbitLook) OrbitMessageGestures(quickLike, openMessageMenu) else null,
            LocalOrbitPieceActions provides pieceActions,
        ) {
        OrbitSwipeToReply(
            enabled = orbitLook && isActionable && !isHiddenUserMessage && !isMultiSelectMode &&
                allowTranscriptMutation && onReplyToMessage != null && pieceActions == null,
            onReply = { onReplyToMessage?.invoke(replyTarget(null)) },
            rightAligned = message.sender == "user",
        ) {
        Column {
            if (quietOrbitReply) {
                OrbitHiddenInChatBadge(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp))
            }
            if (showHiddenRows && isHiddenUserMessage) {
                OrbitHiddenUserRow(message.content)
            } else when (chatStyle) {
                ChatStyle.CURSOR -> {
                    CursorStyleChatMessage(
                        message = message,
                        showAssistantHeader = showAssistantHeader,
                        userMessageColor = userMessageColor,
                        userMessageLiquidGlassEnabled = cursorUserBubbleLiquidGlass,
                        userMessageWaterGlassEnabled = cursorUserBubbleWaterGlass,
                        aiMessageColor = aiMessageColor,
                        userTextColor = userTextColor,
                        aiTextColor = aiTextColor,
                        systemMessageColor = systemMessageColor,
                        systemTextColor = systemTextColor,
                        thinkingBackgroundColor = thinkingBackgroundColor,
                        thinkingTextColor = thinkingTextColor,
                        supportToolMarkup = true,
                        initialThinkingExpanded = false,
                        onDeleteMessage = if (allowTranscriptMutation) onDeleteMessage else null,
                        index = index,
                        enableDialogs = enableDialogs,
                        enableToolDetailDialogs = enableToolDetailDialogs,
                        onEditSummary = if (!allowTranscriptMutation) null else { summaryMessage ->
                            onSelectMessageToEdit?.invoke(index, summaryMessage, "summary")
                        }
                    )
                }

                ChatStyle.BUBBLE -> {
                    BubbleStyleChatMessage(
                        message = message,
                        showAssistantHeader = showAssistantHeader,
                        userMessageColor = userMessageColor,
                        aiMessageColor = aiMessageColor,
                        userTextColor = userTextColor,
                        aiTextColor = aiTextColor,
                        systemMessageColor = systemMessageColor,
                        systemTextColor = systemTextColor,
                        userMessageLiquidGlassEnabled = bubbleUserBubbleLiquidGlass,
                        userMessageWaterGlassEnabled = bubbleUserBubbleWaterGlass,
                        aiMessageLiquidGlassEnabled = bubbleAiBubbleLiquidGlass,
                        aiMessageWaterGlassEnabled = bubbleAiBubbleWaterGlass,
                        userBubbleImageStyle = bubbleUserImageStyle,
                        aiBubbleImageStyle = bubbleAiImageStyle,
                        bubbleUserRoundedCornersEnabled = bubbleUserRoundedCornersEnabled,
                        bubbleAiRoundedCornersEnabled = bubbleAiRoundedCornersEnabled,
                        bubbleUserContentPaddingLeft = bubbleUserContentPaddingLeft,
                        bubbleUserContentPaddingRight = bubbleUserContentPaddingRight,
                        bubbleAiContentPaddingLeft = bubbleAiContentPaddingLeft,
                        bubbleAiContentPaddingRight = bubbleAiContentPaddingRight,
                        isHidden = isHidden,
                        onDeleteMessage = if (allowTranscriptMutation) onDeleteMessage else null,
                        index = index,
                        enableDialogs = enableDialogs,
                        enableToolDetailDialogs = enableToolDetailDialogs,
                        onRoleAvatarLongPress = onMentionRoleFromAvatar,
                        onEditSummary = if (!allowTranscriptMutation) null else { summaryMessage ->
                            onSelectMessageToEdit?.invoke(index, summaryMessage, "summary")
                        }
                    )
                }
            }

            footer()
        }
        }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            modifier = Modifier.widthIn(min = 200.dp),
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            if (orbitLook && message.sender == "ai" && orbitUserReactions != null) {
                val current = orbitUserReactions.byMessage[reactionKey]
                OrbitTapbackRow(
                    selected = current,
                    onPick = { emoji ->
                        // Taking a reaction off does not count as using that emoji.
                        if (emoji != current) OrbitEmojiUsage.record(context, emoji)
                        orbitUserReactions.toggle(reactionKey, emoji, menuPiece?.summary)
                        showContextMenu = false
                    },
                    onMore = {
                        showContextMenu = false
                        showEmojiPicker = true
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            // A card has no words to copy or read out.
            if (!isHiddenUserMessage && menuPiece == null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.copy_message),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        val cleanContent = cleanMessageContentForCopy(message.content)
                        copyPreviewText = cleanContent
                        onCopyMessage?.invoke(message)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = stringResource(id = R.string.copy_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )

                // 朗读消息选项
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.read_message),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onSpeakMessage?.invoke(message.content)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                            contentDescription = stringResource(R.string.read_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }

            if (allowTranscriptMutation && isActionable && !isHiddenUserMessage && !orbitLook && pluginMenuItems.isNotEmpty()) {
                pluginMenuItems.forEach { pluginMenuItem ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                pluginMenuItem.title,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            val clickParams = buildPluginMenuItemParams()
                            showContextMenu = false
                            coroutineScope.launch {
                                try {
                                    val result = pluginMenuItem.onClick(clickParams)
                                    val dialogRequest = result?.dialog
                                    if (dialogRequest != null && enableDialogs) {
                                        toolPkgDialogRequest = dialogRequest
                                    }
                                } catch (error: Exception) {
                                    AppLogger.e(
                                        "ChatArea",
                                        "chat message menu item failed: ${pluginMenuItem.id}",
                                        error
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.operation_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector =
                                    MaterialIconNameResolver.resolveOrDefault(
                                        pluginMenuItem.icon,
                                        Icons.Default.Extension
                                    ),
                                contentDescription = pluginMenuItem.title,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        modifier = Modifier.heightIn(min = 44.dp)
                    )
                }
            }

            // Orbit's chat is one long conversation: nothing here may rewrite what was said
            // (edit, roll back, regenerate, delete, summarize). Reply and a reference-only fork work.
            if (allowTranscriptMutation && orbitLook && !isHiddenUserMessage) {
                if (forkOrbitMessage != null && !isWriting && com.ai.assistance.operit.features.orbit.OrbitChatFork.canFork(message)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.orbit_fork_here), style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            showContextMenu = false
                            // Cards move to the end of a reply on screen; their key keeps the source row.
                            val sourceTime = menuPiece?.key?.let {
                                com.ai.assistance.operit.features.orbit.OrbitReactions.timeOf(it)
                            }?.takeIf { it > 0 } ?: message.timestamp
                            forkOrbitMessage(message.copy(timestamp = sourceTime))
                        },
                        leadingIcon = {
                            Icon(Icons.Default.CallSplit, null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        modifier = Modifier.heightIn(min = 44.dp),
                    )
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.reply_message),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onReplyToMessage?.invoke(replyTarget(menuPiece))
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Reply,
                            contentDescription = stringResource(R.string.reply_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }
            if (allowTranscriptMutation && !orbitLook) {
                // 根据消息发送者显示不同的操作
                if (message.sender == "user") {
                if (!isHiddenUserMessage) {
                    // 编辑并重发选项
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(id = R.string.edit_and_resend),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        onClick = {
                            onSelectMessageToEdit?.invoke(index, message, "user")
                            showContextMenu = false
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(id = R.string.edit_and_resend),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        modifier = Modifier.heightIn(min = 44.dp)
                    )
                }
                // 回滚到此处
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.rollback_to_here),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onRollbackToMessage?.invoke(index)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(id = R.string.rollback_to_here),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
                } else if (message.sender == "ai") {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.chat_regenerate_single),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onRegenerateMessage?.invoke(index)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.chat_regenerate_single),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
                // 修改记忆选项
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.modify_memory),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onSelectMessageToEdit?.invoke(index, message, "ai")
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = stringResource(id = R.string.modify_memory),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }

                if (message.sender == "ai" && message.variantCount > 1) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.chat_delete_single_variant),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        onDeleteCurrentMessageVariant?.invoke(index)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(id = R.string.chat_delete_single_variant),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp),
                )
            }

                // 删除
                DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.delete),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                },
                onClick = {
                    showContextMenu = false
                    showDeleteMessageConfirmDialog = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier.heightIn(min = 44.dp)
            )

                // 回复选项
                if (message.sender == "ai" || (orbitLook && !isHiddenUserMessage)) {
                DropdownMenuItem(
                text = {
                        Text(
                            stringResource(R.string.reply_message),
                            style = MaterialTheme.typography.bodyMedium
                       )
                },
                onClick = {
                        onReplyToMessage?.invoke(message)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Reply,
                            contentDescription = stringResource(R.string.reply_message),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }

                if (message.sender == "user" || message.sender == "ai") {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(id = R.string.insert_summary),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        onInsertSummary?.invoke(message)
                        showContextMenu = false
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Summarize,
                            contentDescription = stringResource(id = R.string.insert_summary),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                )
            }

                // 创建分支
                DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.create_branch),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onCreateBranch?.invoke(message.timestamp)
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = stringResource(id = R.string.create_branch),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier.heightIn(min = 44.dp)
                )
            }

            if (showHiddenRows) DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.orbit_dev_view_raw),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    showContextMenu = false
                    showRawMessage = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = stringResource(id = R.string.orbit_dev_view_raw),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier.heightIn(min = 44.dp)
            )

            // 信息 (an Orbit chat keeps model and token details out of sight)
            if (!orbitLook) DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.info),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    showContextMenu = false
                    showMessageInfoDialog = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = stringResource(id = R.string.info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier.heightIn(min = 44.dp)
            )

            if (allowTranscriptMutation && !orbitLook) {
                DropdownMenuItem(
                text = {
                    Text(
                        stringResource(id = R.string.multi_select),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                onClick = {
                    onToggleMultiSelectMode?.invoke(messageIndex) // 传入消息索引
                    showContextMenu = false
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(id = R.string.multi_select),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                },
                modifier = Modifier.heightIn(min = 44.dp)
                )
            }
        }

        if (showEmojiPicker && orbitUserReactions != null) {
            val current = orbitUserReactions.byMessage[reactionKey]
            OrbitEmojiPickerSheet(
                selected = current,
                onPick = { emoji ->
                    if (emoji != current) OrbitEmojiUsage.record(context, emoji)
                    orbitUserReactions.toggle(reactionKey, emoji, menuPiece?.summary)
                    showEmojiPicker = false
                },
                onDismiss = { showEmojiPicker = false },
            )
        }

        if (enableDialogs && isHiddenUserMessage && showHiddenUserMessageDialog) {
            AlertDialog(
                onDismissRequest = { showHiddenUserMessageDialog = false },
                title = { Text(text = stringResource(R.string.chat_hidden_user_message_badge)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showHiddenUserMessageDialog = false }) {
                        Text(text = stringResource(R.string.floating_close))
                    }
                },
            )
        }

        if (showMessageInfoDialog) {
            MessageInfoDialog(
                message = message,
                onDismiss = { showMessageInfoDialog = false }
            )
        }

        if (showRawMessage) {
            OrbitRawMessageSheet(message = message, onDismiss = { showRawMessage = false })
        }

        if (showDeleteMessageConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteMessageConfirmDialog = false },
                title = { Text(text = stringResource(R.string.confirm_delete)) },
                text = { Text(text = stringResource(R.string.chat_delete_message_confirm_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDeleteMessage?.invoke(index)
                            showDeleteMessageConfirmDialog = false
                        }
                    ) {
                        Text(text = stringResource(R.string.confirm_delete_action))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteMessageConfirmDialog = false }) {
                        Text(text = stringResource(R.string.cancel))
                    }
                },
            )
        }

        copyPreviewText?.let { previewText ->
            MessageCopyPreviewBottomSheet(
                text = previewText,
                onDismiss = { copyPreviewText = null }
            )
        }

        toolPkgDialogRequest?.let { dialogRequest ->
            ToolPkgComposeDslDialogHost(
                request = dialogRequest,
                onDismiss = { toolPkgDialogRequest = null }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageCopyPreviewBottomSheet(
    text: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val textScrollState = rememberScrollState()
    var showPlainText by remember(text) { mutableStateOf(true) }
    var plainText by remember(text) { mutableStateOf<String?>(null) }
    LaunchedEffect(text, context) {
        plainText =
            withContext(Dispatchers.Default) {
                markdownToPlainTextForCopy(text) { formulas ->
                    LatexMathMlConverter.convertAll(context, formulas)
                }
            }
    }
    val displayedText = if (showPlainText) plainText.orEmpty() else text
    val copyButtonText =
        if (showPlainText) {
            stringResource(R.string.copy_plain_text)
        } else {
            stringResource(R.string.copy_markdown_source)
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
        ) {
            Text(
                text = stringResource(id = R.string.copy_message),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                FilterChip(
                    selected = showPlainText,
                    onClick = { showPlainText = true },
                    label = { Text(stringResource(R.string.plain_text)) }
                )
                FilterChip(
                    selected = !showPlainText,
                    onClick = { showPlainText = false },
                    label = { Text(stringResource(R.string.markdown_source)) }
                )
            }
            if (showPlainText && plainText == null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .padding(bottom = 12.dp)
                ) {
                    CircularProgressIndicator()
                }
            } else {
                SelectionContainer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(textScrollState)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = displayedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = !showPlainText || plainText != null,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(displayedText))
                        Toast.makeText(
                            context,
                            context.getString(R.string.message_copied_to_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Text(text = copyButtonText)
                }
            }
        }
    }
}

private fun hasDisplayableTokenStats(message: ChatMessage): Boolean {
    return message.displayMode != ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE &&
        (message.inputTokens > 0 || message.cachedInputTokens > 0 || message.outputTokens > 0)
}

private fun hasDisplayableTimingStats(message: ChatMessage): Boolean {
    return message.displayMode != ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE &&
        (message.waitDurationMs > 0L || message.outputDurationMs > 0L)
}

private fun hasDisplayableMessageTimestamp(message: ChatMessage): Boolean {
    return message.displayMode != ChatMessageDisplayMode.ASSISTANT_INTERMEDIATE &&
        message.completedAt > 0L
}

/** Sub-minute spans stay compact; longer ones read as minutes and hours instead of a huge seconds count. */
private fun formatCompactDuration(context: Context, durationMs: Long): String {
    if (durationMs <= 0L) return "0ms"
    if (durationMs < 1000L) return "${durationMs}ms"
    if (durationMs < 60_000L) {
        return if (durationMs >= 10_000L) {
            String.format(Locale.getDefault(), "%.0fs", durationMs / 1000f)
        } else {
            String.format(Locale.getDefault(), "%.1fs", durationMs / 1000f)
        }
    }
    val totalSeconds = durationMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (minutes < 60L) {
        context.getString(R.string.duration_compact_minutes, minutes, seconds)
    } else {
        context.getString(R.string.duration_compact_hours, minutes / 60L, minutes % 60L, seconds)
    }
}

private fun formatCompactTimestamp(completedAt: Long): String {
    if (completedAt <= 0L) return ""
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(completedAt))
}

@Composable
internal fun MessageFooterBar(
    message: ChatMessage,
    showMessageTokenStats: Boolean,
    showMessageTimingStats: Boolean,
    showMessageTimestamp: Boolean,
    allowVariantSelection: Boolean,
    onSelectVariant: (Int) -> Unit,
) {
    if (LocalResponseMessageSection.current == ResponseMessageSection.HEADER) return
    val hasPrevious = message.selectedVariantIndex > 0
    val hasNext = message.selectedVariantIndex < message.variantCount - 1
    val context = LocalContext.current
    val tokenSummary =
        remember(message.inputTokens, message.cachedInputTokens, message.outputTokens) {
            val totalTokens = message.inputTokens + message.outputTokens
            context.getString(
                R.string.chat_message_token_stats_compact,
                totalTokens,
                message.cachedInputTokens,
                message.inputTokens,
                message.outputTokens,
            )
        }
    val timeSummary =
        remember(message.waitDurationMs, message.outputDurationMs) {
            val totalDuration = (message.waitDurationMs + message.outputDurationMs).coerceAtLeast(0L)
            context.getString(
                R.string.chat_message_timing_stats_compact,
                formatCompactDuration(context, totalDuration),
                formatCompactDuration(context, message.waitDurationMs),
                formatCompactDuration(context, message.outputDurationMs),
            )
        }
    val messageTimeSummary =
        stringResource(
            R.string.chat_message_timestamp_compact,
            formatCompactTimestamp(message.completedAt),
        )
    val statsTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f)
    // The version switcher can be left alone on a row that does not close its card. Only the closing
    // row is spaced away from the next message, so the ones before it have to hold themselves off it.
    val hasStatistics =
        (showMessageTokenStats && hasDisplayableTokenStats(message)) ||
            (showMessageTimingStats && hasDisplayableTimingStats(message)) ||
            (showMessageTimestamp && hasDisplayableMessageTimestamp(message))
    val switcherAlone = !hasStatistics && message.variantCount > 1

    Column(
        modifier =
            Modifier.padding(
                start = 16.dp,
                top = 4.dp,
                bottom = if (switcherAlone && !LocalTranscriptCardEnds.current.last) 4.dp else 0.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (message.variantCount > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.chat_previous_variant),
                    tint =
                        if (hasPrevious && allowVariantSelection) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clickable(enabled = hasPrevious && allowVariantSelection) {
                                onSelectVariant(message.selectedVariantIndex - 1)
                            },
                )
                Text(
                    text =
                        stringResource(
                            R.string.chat_message_variant_counter,
                            message.selectedVariantIndex + 1,
                            message.variantCount,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.chat_next_variant),
                    tint =
                        if (hasNext && allowVariantSelection) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    modifier =
                        Modifier
                            .size(16.dp)
                            .clickable(enabled = hasNext && allowVariantSelection) {
                                onSelectVariant(message.selectedVariantIndex + 1)
                            },
                )
            }
        }

        if (showMessageTokenStats && hasDisplayableTokenStats(message)) {
            Text(
                text = tokenSummary,
                style = MaterialTheme.typography.labelSmall,
                color = statsTextColor,
            )
        }

        if (showMessageTimingStats && hasDisplayableTimingStats(message)) {
            Text(
                text = timeSummary,
                style = MaterialTheme.typography.labelSmall,
                color = statsTextColor,
            )
        }

        if (showMessageTimestamp && hasDisplayableMessageTimestamp(message)) {
            Text(
                text = messageTimeSummary,
                style = MaterialTheme.typography.labelSmall,
                color = statsTextColor,
            )
        }
    }
}

@Composable
private fun LoadingDotsIndicator(textColor: Color) {
    val infiniteTransition = rememberInfiniteTransition()

    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val jumpHeight = -5f
        val animationDelay = 160

        (0..2).forEach { index ->
            val offsetY by
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = jumpHeight,
                animationSpec =
                infiniteRepeatable(
                    animation =
                    keyframes {
                        durationMillis = 600
                        0f at 0
                        jumpHeight * 0.4f at 100
                        jumpHeight * 0.8f at 200
                        jumpHeight at 300
                        jumpHeight * 0.8f at 400
                        jumpHeight * 0.4f at 500
                        0f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * animationDelay),
                ),
                label = "",
            )

            Box(
                modifier =
                Modifier
                    .size(6.dp)
                    .offset(y = offsetY.dp)
                    .background(
                        color = textColor.copy(alpha = 0.6f),
                        shape = CircleShape,
                    ),
            )
        }
    }
}

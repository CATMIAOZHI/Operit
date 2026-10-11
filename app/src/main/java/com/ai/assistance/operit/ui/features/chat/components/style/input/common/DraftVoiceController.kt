package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.floating.voice.SpeechInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * One dictation session for the composer.
 *
 * Reading [isRecording] and friends during composition is observable, because they are backed by
 * the manager's snapshot state. Transcription only ever lands in the draft — speaking never sends.
 */
@Stable
class DraftVoiceController
internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val view: View,
) {
    private var activeState by mutableStateOf(false)
    private var statusState by mutableStateOf("")
    private var errorState by mutableStateOf<String?>(null)
    private var failedTextState by mutableStateOf<String?>(null)

    internal val manager: SpeechInteractionManager by lazy {
        SpeechInteractionManager(
            context = context,
            coroutineScope = scope,
            onSpeechResult = { text, final -> if (final) closeFromResult(text) },
            onStateChange = { setStatus(it) },
        )
    }

    private var insertText: (String) -> Unit = {}

    val isActive: Boolean get() = activeState
    val isRecording: Boolean get() = manager.isRecording
    val isPreparing: Boolean get() = manager.isPreparing
    val isProcessing: Boolean get() = manager.isProcessingSpeech

    /** What has been recognised so far, partial results included. */
    val liveText: String get() = failedTextState ?: manager.userMessage
    val statusText: String get() = statusState
    val errorText: String? get() = errorState
    val volumeLevelFlow: StateFlow<Float> get() = manager.volumeLevelFlow
    val recognitionStateFlow get() = manager.speechService.recognitionStateFlow

    internal fun bindInsert(block: (String) -> Unit) {
        insertText = block
    }

    /** Starts listening. Safe to call again to retry after a failure. */
    fun open() {
        errorState = null
        failedTextState = null
        activeState = true
        manager.requestFocus(view)
        manager.startListening(
            onStartFailure = { errorState = it },
            continuousMode = manager.speechService.supportsContinuousDictation ||
                manager.speechService.supportsContinuousRecognition,
        )
    }

    /** Stops the microphone and waits for the final transcription. */
    fun finishRecording() {
        manager.stopListening(isCancel = false)
    }

    /** Takes whatever has been recognised so far and closes, without waiting for the final pass. */
    fun confirmNow() {
        val text = liveText
        manager.stopListening(isCancel = true)
        activeState = false
        insertText(text)
    }

    fun cancel() {
        manager.stopListening(isCancel = true)
        activeState = false
    }

    /**
     * Stops the microphone but keeps the panel up, so the reason stays on screen instead of the
     * composer silently going back to normal.
     */
    fun fail(message: String) {
        failedTextState = liveText
        manager.stopListening(isCancel = true)
        errorState = message
        activeState = true
    }

    internal fun closeFromResult(text: String) {
        activeState = false
        insertText(text)
    }

    internal fun setStatus(text: String) {
        statusState = text
    }

    internal fun cleanup() {
        manager.cleanup()
    }
}

/**
 * Builds the controller for the current chat and keeps its transcription flowing into the draft.
 * The manager is keyed on the chat, so switching conversations ends a session the same way closing
 * the old dialog did.
 */
@Composable
fun rememberDraftVoiceController(
    viewModel: ChatViewModel,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
): DraftVoiceController {
    val chatId by viewModel.currentChatId.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValueChange)
    val didNotHearText = stringResource(R.string.floating_didnt_hear_clearly)

    return key(chatId) {
        val controller = remember { DraftVoiceController(context, scope, view) }
        controller.bindInsert { text ->
            if (text.isNotBlank()) {
                val current = latestValue
                val start = current.selection.min.coerceIn(0, current.text.length)
                val end = current.selection.max.coerceIn(start, current.text.length)
                latestChange(
                    TextFieldValue(
                        current.text.replaceRange(start, end, text),
                        TextRange(start + text.length),
                    )
                )
            }
        }
        DisposableEffect(controller) { onDispose { controller.cleanup() } }

        LaunchedEffect(controller) {
            controller.manager.recognitionResultFlow.collect {
                if (
                    it.isFinal &&
                        it.text.isBlank() &&
                        controller.liveText.isBlank() &&
                        (controller.isProcessing ||
                            (controller.isRecording &&
                                !controller.manager.speechService.supportsContinuousDictation))
                ) {
                    controller.fail(didNotHearText)
                    return@collect
                }
                // Finalises into the draft-only callback, never a chat send action.
                controller.manager.handleRecognitionResult(
                    it.text,
                    it.isFinal,
                    autoSendSilence = !controller.manager.speechService.supportsContinuousDictation,
                )
            }
        }
        LaunchedEffect(controller) {
            controller.manager.speechService.recognitionErrorFlow.collect {
                if (it.message.isNotBlank() && (controller.isRecording || controller.isProcessing)) {
                    controller.fail(it.message)
                }
            }
        }

        controller
    }
}

package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.R
import com.ai.assistance.operit.ui.features.chat.viewmodel.ChatViewModel
import com.ai.assistance.operit.ui.floating.voice.SpeechInteractionManager

/** Has no send callback: transcription can only update the editable composer. */
@Composable
fun DraftVoiceInputButton(
    viewModel: ChatViewModel,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    enabled: Boolean
) {
    val chatId by viewModel.currentChatId.collectAsState()
    key(chatId) {
        val context = LocalContext.current
        val latestValue by rememberUpdatedState(value)
        val latestChange by rememberUpdatedState(onValueChange)
        val latestEnabled by rememberUpdatedState(enabled)
        var open by remember { mutableStateOf(false) }
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it && latestEnabled) open = true
            else if (!it) Toast.makeText(context, R.string.voice_draft_permission, Toast.LENGTH_SHORT).show()
        }
        IconButton(enabled = enabled, onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED) open = true
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        }) {
            Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.voice_draft_button))
        }
        if (open) {
            DraftRecordingDialog(onDismiss = { open = false }, onResult = { text ->
                if (text.isNotBlank()) {
                    val current = latestValue
                    val start = current.selection.min.coerceIn(0, current.text.length)
                    val end = current.selection.max.coerceIn(start, current.text.length)
                    latestChange(TextFieldValue(
                        current.text.replaceRange(start, end, text), TextRange(start + text.length)))
                }
                open = false
            })
        }
    }
}

@Composable
private fun DraftRecordingDialog(onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val latestResult by rememberUpdatedState(onResult)
    var status by remember { mutableStateOf(context.getString(R.string.voice_preparing)) }
    var error by remember { mutableStateOf<String?>(null) }
    val manager = remember {
        SpeechInteractionManager(context, scope,
            onSpeechResult = { text, final -> if (final) latestResult(text) },
            onStateChange = { status = it })
    }
    DisposableEffect(manager) { onDispose { manager.cleanup() } }
    LaunchedEffect(manager) {
        manager.recognitionResultFlow.collect {
            if (it.isFinal && it.text.isBlank() &&
                (manager.isRecording || manager.isProcessingSpeech)) {
                manager.stopListening(isCancel = true)
                error = context.getString(R.string.floating_didnt_hear_clearly)
                return@collect
            }
            // Finalizes into our draft-only callback, never a chat send action.
            manager.handleRecognitionResult(it.text, it.isFinal, autoSendSilence = true)
        }
    }
    LaunchedEffect(manager) {
        manager.speechService.recognitionErrorFlow.collect {
            if (it.message.isNotBlank() && (manager.isRecording || manager.isProcessingSpeech)) {
                error = it.message
                manager.stopListening(isCancel = true)
            }
        }
    }
    LaunchedEffect(manager) {
        manager.requestFocus(view)
        manager.startListening(onStartFailure = { error = it })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_draft_button)) },
        text = {
            Column {
                Text(stringResource(R.string.voice_draft_hint))
                Text(error ?: status)
                if (manager.userMessage.isNotBlank()) Text(manager.userMessage)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = !manager.isPreparing && !manager.isProcessingSpeech,
                onClick = {
                    if (manager.isRecording) manager.stopListening(isCancel = false)
                    else {
                        error = null
                        manager.requestFocus(view)
                        manager.startListening(onStartFailure = { error = it })
                    }
                }) {
                Text(stringResource(if (manager.isRecording) R.string.voice_draft_finish
                    else R.string.voice_draft_retry))
            }
        }
    )
}

package com.ai.assistance.operit.pet

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** A visible conversation is already acknowledged; background results keep their completion cue. */
@Composable
internal fun PetViewedChatObserver(chatId: String?) {
    val context = LocalContext.current
    val model = remember(context) { PetTasks.get(context) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val tasks by model.tasks.collectAsState()
    LaunchedEffect(chatId, resumed, tasks) {
        if (resumed && chatId != null) model.acknowledgeViewedChat(chatId)
    }
}

package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatTodo
import com.ai.assistance.operit.data.model.ChatTodoStatus

@Composable
fun ChatTodoDock(
    chatId: String?,
    todos: List<ChatTodo>,
    modifier: Modifier = Modifier,
) {
    if (chatId == null || todos.isEmpty()) return

    val allTerminal =
        todos.all {
            it.status == ChatTodoStatus.COMPLETED || it.status == ChatTodoStatus.CANCELLED
        }
    var expanded by rememberSaveable(chatId) { mutableStateOf(false) }
    LaunchedEffect(chatId, allTerminal) {
        if (allTerminal) expanded = false
    }

    val currentStep =
        todos.indexOfFirst {
            it.status == ChatTodoStatus.IN_PROGRESS || it.status == ChatTodoStatus.PENDING
        }.let { index -> if (index >= 0) index + 1 else todos.size }
    val terminalCount =
        todos.count {
            it.status == ChatTodoStatus.COMPLETED || it.status == ChatTodoStatus.CANCELLED
        }
    val progress = terminalCount.toFloat() / todos.size.toFloat()

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(visible = expanded) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier =
                        Modifier.heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    todos.forEach { todo -> TodoDetailRow(todo) }
                }
            }
        }

        Surface(
            modifier = Modifier.clickable { expanded = !expanded },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = stringResource(R.string.chat_todo_step_progress, currentStep, todos.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
    }
}

@Composable
private fun TodoDetailRow(todo: ChatTodo) {
    val terminal =
        todo.status == ChatTodoStatus.COMPLETED || todo.status == ChatTodoStatus.CANCELLED
    val indicatorColor =
        when (todo.status) {
            ChatTodoStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
            ChatTodoStatus.COMPLETED -> MaterialTheme.colorScheme.primary
            ChatTodoStatus.CANCELLED -> MaterialTheme.colorScheme.outline
            ChatTodoStatus.PENDING -> MaterialTheme.colorScheme.outline
        }

    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.then(if (terminal) Modifier.alpha(0.66f) else Modifier),
    ) {
        Box(modifier = Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(14.dp)) {
                drawCircle(color = indicatorColor, style = Stroke(width = 1.8.dp.toPx()))
                if (todo.status == ChatTodoStatus.COMPLETED) {
                    drawCircle(color = indicatorColor, radius = 3.dp.toPx())
                }
            }
        }
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (todo.status == ChatTodoStatus.IN_PROGRESS) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            textDecoration = if (terminal) TextDecoration.LineThrough else TextDecoration.None,
        )
    }
}

package com.ai.assistance.operit.ui.features.chat.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.ChatRuntimeHolder
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.ChatEntity
import com.ai.assistance.operit.data.repository.ChatHistoryManager
import com.ai.assistance.operit.features.reading.ReadingCompanionAudit
import com.ai.assistance.operit.ui.main.navigation.AppRouterGateway
import com.ai.assistance.operit.ui.main.navigation.RouteEntrySource
import kotlinx.coroutines.launch

/**
 * 隐藏聊天列表（阅读伴侣审计等隐藏入口专用）。
 *
 * - 列表来自 ChatDao.observeHiddenChats（全部隐藏聊天，含审计根/审计 run 子聊天）。
 * - 点击行：switchChat(chatId) 后经路由 native.ai_chat 进入主聊天界面。
 * - 删除：ChatHistoryManager.deleteChatHistory 子树删除（先取消任务、child-first）。
 * - 列表不提供解除隐藏入口；READING_COMPANION_AUDIT_ 前缀的审计聊天永久隐藏
 *   （ChatHistoryManager.setChatHidden 已对该前缀禁止 hidden=false）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenChatsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatDao = remember { AppDatabase.getDatabase(context.applicationContext).chatDao() }
    val hiddenChats by chatDao.observeHiddenChats().collectAsState(initial = null)
    val chatHistoryManager = remember { ChatHistoryManager.getInstance(context.applicationContext) }
    val title = stringResource(R.string.hidden_chats_title)
    val emptyText = stringResource(R.string.hidden_chats_empty)
    val permanentNote = stringResource(R.string.hidden_chats_permanently_hidden_note)
    val deleteConfirmTitle = stringResource(R.string.hidden_chats_delete_confirm_title)
    val cancelText = stringResource(R.string.cancel)
    val deleteText = stringResource(R.string.delete)
    var chatToDelete by remember { mutableStateOf<ChatEntity?>(null) }
    var deleting by remember { mutableStateOf(false) }

    val openChat: (ChatEntity) -> Unit = { chat ->
        ChatRuntimeHolder.getInstance(context.applicationContext)
            .getCore(ChatRuntimeSlot.MAIN)
            .getChatHistoryDelegate()
            .switchChat(chat.id, scrollToBottom = false)
        AppRouterGateway.navigate(
            routeId = "native.ai_chat",
            args = emptyMap(),
            source = RouteEntrySource.SCRIPT,
        )
    }

    val deleteChat: (ChatEntity) -> Unit = { chat ->
        scope.launch {
            deleting = true
            try {
                chatHistoryManager.deleteChatHistory(chat.id)
            } catch (error: Throwable) {
                Toast.makeText(context, error.message, Toast.LENGTH_SHORT).show()
            } finally {
                deleting = false
                chatToDelete = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { AppRouterGateway.navigate("native.ai_chat") }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                hiddenChats == null ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                hiddenChats!!.isEmpty() ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                else ->
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = permanentNote,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            items(hiddenChats!!, key = { it.id }) { chat ->
                                androidx.compose.material3.Card(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 4.dp)
                                            .clickable { openChat(chat) },
                                ) {
                                    Row(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Default.VisibilityOff,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = chat.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = chat.hiddenReason ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { chatToDelete = chat },
                                            enabled = !deleting,
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = deleteText,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    chatToDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = {
                if (!deleting) {
                    chatToDelete = null
                }
            },
            title = { Text(deleteConfirmTitle) },
            text = {
                Text(
                    context.getString(R.string.hidden_chats_delete_confirm_message, chat.title),
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteChat(chat) }, enabled = !deleting) {
                    Text(deleteText)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { chatToDelete = null },
                    enabled = !deleting,
                ) {
                    Text(cancelText)
                }
            },
        )
    }
}

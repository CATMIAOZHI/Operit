package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.operit.R

enum class MessageRevertMode {
    ROLLBACK,
    EDIT_AND_RESEND
}

/**
 * 撤回对话前的确认提示。
 *
 * 只删除对话记录、不会撤销 AI 已经写到磁盘的文件改动，所以删除的轮次调用过工具时额外提示
 * AI 会失去这些操作的记忆。
 */
@Composable
fun MessageRevertConfirmDialog(
    mode: MessageRevertMode,
    removedRangeHasToolCalls: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dialogMetrics = rememberCompactDialogMetrics()
    val cardModifier =
        Modifier
            .fillMaxWidth(0.95f)
            .compactDialogHeightOrWrapContent(dialogMetrics)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Card(
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = when (mode) {
                            MessageRevertMode.ROLLBACK ->
                                stringResource(id = R.string.confirm_rollback_title)
                            MessageRevertMode.EDIT_AND_RESEND ->
                                stringResource(id = R.string.confirm_edit_and_resend_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when (mode) {
                        MessageRevertMode.ROLLBACK ->
                            stringResource(id = R.string.confirm_rollback_message)
                        MessageRevertMode.EDIT_AND_RESEND ->
                            stringResource(id = R.string.confirm_edit_and_resend_message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (removedRangeHasToolCalls) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.confirm_revert_tool_calls_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(id = R.string.cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            text = when (mode) {
                                MessageRevertMode.ROLLBACK ->
                                    stringResource(id = R.string.confirm_rollback_confirm)
                                MessageRevertMode.EDIT_AND_RESEND ->
                                    stringResource(id = R.string.confirm_edit_and_resend_confirm)
                            }
                        )
                    }
                }
            }
        }
    }
}

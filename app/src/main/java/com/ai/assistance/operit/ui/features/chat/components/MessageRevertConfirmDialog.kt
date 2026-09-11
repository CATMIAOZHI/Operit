package com.ai.assistance.operit.ui.features.chat.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R

enum class MessageRevertMode {
    ROLLBACK,
    EDIT_AND_RESEND
}

/**
 * 撤回对话前的确认提示。
 *
 * 撤回只删除对话记录，不会撤销 AI 已经写入磁盘的文件改动；当被删除的轮次调用过工具时，额外提示
 * AI 会失去这些操作的上下文。
 */
@Composable
fun MessageRevertConfirmDialog(
    mode: MessageRevertMode,
    removedRangeHasToolCalls: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message =
        when (mode) {
            MessageRevertMode.ROLLBACK -> stringResource(R.string.confirm_rollback_message)
            MessageRevertMode.EDIT_AND_RESEND ->
                stringResource(R.string.confirm_edit_and_resend_message)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    MessageRevertMode.ROLLBACK -> stringResource(R.string.confirm_rollback_title)
                    MessageRevertMode.EDIT_AND_RESEND ->
                        stringResource(R.string.confirm_edit_and_resend_title)
                }
            )
        },
        text = {
            Text(
                if (removedRangeHasToolCalls) {
                    message + "\n\n" + stringResource(R.string.confirm_revert_tool_calls_warning)
                } else {
                    message
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    when (mode) {
                        MessageRevertMode.ROLLBACK ->
                            stringResource(R.string.confirm_rollback_confirm)
                        MessageRevertMode.EDIT_AND_RESEND ->
                            stringResource(R.string.confirm_edit_and_resend_confirm)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

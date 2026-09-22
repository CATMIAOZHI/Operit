package com.ai.assistance.operit.ui.permissions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Use the full reviewed tool name, including its package, rather than a display label. */
@Composable
fun AlwaysAllowReviewedToolButton(toolName: String) {
    if (toolName.isBlank()) return
    val context = LocalContext.current
    val permissions = remember(context) { ToolPermissionSystem.getInstance(context) }
    val level by remember(permissions, toolName) {
        permissions.getToolPermissionFlow(toolName)
    }.collectAsState(initial = PermissionLevel.ASK)
    var confirm by rememberSaveable(toolName) { mutableStateOf(false) }
    var saving by remember(toolName) { mutableStateOf(false) }
    var failed by remember(toolName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(enabled = level != PermissionLevel.ALLOW, onClick = { failed = false; confirm = true }) {
        Text(stringResource(if (level == PermissionLevel.ALLOW)
            R.string.permission_review_always_allowed else R.string.permission_review_always_allow))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) confirm = false },
            title = { Text(stringResource(R.string.permission_review_always_allow)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(stringResource(R.string.permission_review_always_allow_confirm, toolName))
                    if (failed) Text(stringResource(R.string.permission_review_always_allow_failed),
                        color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    saving = true
                    failed = false
                    scope.launch {
                        try {
                            permissions.saveToolPermission(toolName, PermissionLevel.ALLOW)
                            confirm = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            failed = true
                        } finally {
                            saving = false
                        }
                    }
                }) { Text(stringResource(R.string.permission_review_always_allow_confirm_button)) }
            },
            dismissButton = {
                TextButton(enabled = !saving, onClick = { confirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

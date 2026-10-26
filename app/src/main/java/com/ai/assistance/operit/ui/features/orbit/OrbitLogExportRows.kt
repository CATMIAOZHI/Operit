package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.rounded.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.tools.ToolErrorRepository
import com.ai.assistance.operit.ui.common.dynamicui.CardIcons
import com.ai.assistance.operit.ui.features.settings.components.SettingsCard
import com.ai.assistance.operit.ui.features.settings.components.SettingsNavRow
import com.ai.assistance.operit.ui.features.settings.components.SettingsRowDivider
import com.ai.assistance.operit.ui.features.settings.components.SettingsSectionTitle
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatExportHelper
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.ToolErrorExportHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class LogExport { APP, TOOL_ERRORS }

/** [warning]: set when the newest log lines may be missing from the file. */
private class SavedLog(val name: String, val uri: Uri, val warning: String? = null)

/** Settings rows that save the app log or the tool-error records to Download/operit, ready to share. */
@Composable
internal fun OrbitLogExportSection(cardColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<LogExport?>(null) }
    var saved by remember { mutableStateOf<SavedLog?>(null) }

    fun export(kind: LogExport) {
        if (running != null) return
        running = kind
        scope.launch {
            try {
                saved = when (kind) {
                    LogExport.APP -> {
                        val result = LogcatExportHelper.exportLogs(context)
                        val uri = result.uri
                        if (!result.success || uri == null) {
                            logToast(context, result.message)
                            null
                        } else SavedLog(result.fileName.orEmpty(), uri, result.message.takeUnless { result.complete })
                    }
                    LogExport.TOOL_ERRORS -> {
                        if (ToolErrorRepository.getInstance(context).load(null, 0L, 0).total == 0L) {
                            logToast(context, context.getString(R.string.orbit_log_export_no_tool_errors))
                            null
                        } else {
                            val result = ToolErrorExportHelper.export(context, null, 0L)
                            SavedLog(result.name, result.uri)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logToast(context, context.getString(R.string.orbit_log_export_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                running = null
            }
        }
    }

    Column {
        SettingsSectionTitle(text = stringResource(R.string.orbit_section_troubleshoot), icon = CardIcons.get("lucide:bug") ?: androidx.compose.material.icons.Icons.Rounded.Build)
        SettingsCard(backgroundColor = cardColor) {
            LogExportRow(
                title = stringResource(R.string.orbit_log_export_app),
                subtitle = stringResource(R.string.orbit_log_export_app_desc),
                icon = "scroll-text",
                running = running == LogExport.APP,
                onClick = { export(LogExport.APP) },
            )
            SettingsRowDivider(inset = false)
            LogExportRow(
                title = stringResource(R.string.orbit_log_export_tool_errors),
                subtitle = stringResource(R.string.orbit_log_export_tool_errors_desc),
                icon = "file-warning",
                running = running == LogExport.TOOL_ERRORS,
                onClick = { export(LogExport.TOOL_ERRORS) },
            )
        }
    }

    saved?.let { log ->
        TaskConfirmSheet(
            icon = "file-check",
            title = stringResource(R.string.orbit_log_export_done),
            message = stringResource(R.string.orbit_log_export_done_message, log.name) +
                (log.warning?.let { "\n\n" + it } ?: ""),
            confirmText = stringResource(R.string.orbit_log_export_share),
            dismissText = stringResource(R.string.orbit_log_export_ok),
            destructive = false,
            onDismiss = { saved = null },
            onConfirm = {
                saved = null
                shareLog(context, log)
            },
        )
    }
}

@Composable
private fun LogExportRow(title: String, subtitle: String, icon: String, running: Boolean, onClick: () -> Unit) {
    SettingsNavRow(
        title = title,
        subtitle = if (running) stringResource(R.string.orbit_log_export_running) else subtitle,
        icon = CardIcons.get("lucide:$icon"),
        searchable = false,
        onClick = onClick,
        trailing = if (running) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
    )
}

private fun shareLog(context: Context, log: SavedLog) {
    try {
        val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, log.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, log.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        logToast(context, context.getString(R.string.orbit_file_share_failed))
    }
}

private fun logToast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

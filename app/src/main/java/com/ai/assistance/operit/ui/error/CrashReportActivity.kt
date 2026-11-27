package com.ai.assistance.operit.ui.error

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.core.config.DistributionConfig
import com.ai.assistance.operit.ui.common.OperitUtilityTheme
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatExportHelper
import com.ai.assistance.operit.ui.features.toolbox.screens.logcat.LogcatExportResult
import com.ai.assistance.operit.ui.main.MainActivity
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.ThrowableTextFormatter
import kotlinx.coroutines.launch
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel

/** Holds the export while it runs and its outcome, so turning the phone neither cuts it off nor loses it. */
class CrashReportViewModel(application: Application) : AndroidViewModel(application) {
    var exporting by mutableStateOf(false)
        private set
    var result by mutableStateOf<LogcatExportResult?>(null)
        private set

    fun export(report: String) {
        if (exporting) return
        exporting = true
        viewModelScope.launch {
            try {
                result = LogcatExportHelper.exportLogs(getApplication(), crashReport = report)
            } finally {
                exporting = false
            }
        }
    }
}

/**
 * Shown in its own process after the app crashed. The flag that keeps the crashed run's log is
 * left for the next start of the app itself, so the log can still be exported from the settings
 * after reopening.
 */
class CrashReportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_STACK_TRACE = "extra_stack_trace"
        private const val MAX_CRASH_REPORT_CHARS = 24_000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stackTrace = ThrowableTextFormatter.truncateText(
            intent.getStringExtra(EXTRA_STACK_TRACE) ?: "No stack trace available.",
            MAX_CRASH_REPORT_CHARS
        )

        AppLogger.e("CrashReportActivity", "Displaying crash report, chars=${stackTrace.length}")
        AppLogger.e("CrashReportActivity", "Stack trace:\n$stackTrace")
        setContent { OperitUtilityTheme { CrashReportScreen(stackTrace = stackTrace) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashReportScreen(stackTrace: String) {
    val context = LocalContext.current
    val model: CrashReportViewModel = viewModel()
    val exporting = model.exporting
    val exported = model.result
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            Text(stringResource(R.string.crash_page_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.crash_page_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    stringResource(R.string.crash_page_interrupted),
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(28.dp))
            // Not while the file is written: reopening ends this process and would cut it off.
            Button(onClick = { restartApp(context) }, enabled = !exporting, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.crash_page_reopen))
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { model.export(stackTrace) },
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp)
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(stringResource(R.string.crash_page_export))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.crash_page_export_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            exported?.let { result ->
                val ok = result.success && result.complete
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        SelectionContainer {
                            Text(
                                result.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        val uri = result.uri
                        if (result.success && uri != null) {
                            TextButton(onClick = { shareFile(context, uri, result.fileName.orEmpty()) }) {
                                Text(stringResource(R.string.crash_page_share))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { copyToClipboard(context, stackTrace) }) {
                    Text(stringResource(R.string.crash_page_copy))
                }
                TextButton(onClick = { openIssues(context) }) {
                    Text(stringResource(R.string.crash_page_report))
                }
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(stringResource(if (showDetails) R.string.crash_page_hide_details else R.string.crash_report_details_title))
                }
            }

            if (showDetails) {
                Spacer(Modifier.height(8.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    SelectionContainer {
                        Text(
                            text = stackTrace,
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("error_stack_trace", text))
    Toast.makeText(context, R.string.crash_report_copy_success, Toast.LENGTH_SHORT).show()
}

private fun shareFile(context: Context, uri: Uri, name: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLogger.e("CrashReportActivity", "Failed to share the crash log", e)
        Toast.makeText(context, R.string.orbit_file_share_failed, Toast.LENGTH_LONG).show()
    }
}

private fun openIssues(context: Context) {
    val url = "https://github.com/${DistributionConfig.OWNER}/${DistributionConfig.SOURCE_REPOSITORY}/issues"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("issues", url))
        Toast.makeText(context, R.string.crash_page_report_copied, Toast.LENGTH_LONG).show()
    }
}

private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, MainActivity::class.java)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    val flags = PendingIntent.FLAG_CANCEL_CURRENT or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, flags)

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    alarmManager.set(
        AlarmManager.RTC,
        System.currentTimeMillis() + 200,
        pendingIntent
    )

    (context as? Activity)?.finishAffinity()
    android.os.Process.killProcess(android.os.Process.myPid())
    kotlin.system.exitProcess(0)
}

package com.ai.assistance.operit.ui.features.toolbox.screens.logcat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LogFlushResult
import com.ai.assistance.operit.util.MemoryDiagnostics
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LogcatExportResult(
    val message: String,
    val success: Boolean,
    /** The saved file, ready to share; null when nothing was saved. */
    val uri: android.net.Uri? = null,
    val fileName: String? = null,
    /** False when some of the newest records may not have reached the file; [message] says why. */
    val complete: Boolean = true,
)

object LogcatExportHelper {

    /** Saves the app log as a zip in Downloads; [crashReport], when given, goes in first as crash-report.txt. */
    suspend fun exportLogs(context: Context, crashReport: String? = null): LogcatExportResult = withContext(Dispatchers.IO) {
        try {
            val flush = AppLogger.flushFileLogsWithResult()
            val logFile = AppLogger.getLogFile()?.takeIf { it.isFile && it.length() > 0 }
            // With a crash report the export must not fail on the log: the report alone still goes out.
            val logLineCount = logFile?.let {
                if (crashReport == null) countExportableLogLines(it) else runCatching { countExportableLogLines(it) }.getOrDefault(0L)
            } ?: 0L
            if (logLineCount == 0L && crashReport == null && !MemoryDiagnostics.hasRecords(context)) {
                return@withContext LogcatExportResult(
                    message = context.getString(R.string.logcat_no_logs_to_save),
                    success = false
                )
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            // 日志约九成是请求体转储，压成 zip 后只有原来的三成左右，所以导出统一压缩，
            // 既保留完整正文，又不用来回传几十 MB 的文本文件。
            val entryName = "operit_log_$timestamp.txt"
            val fileName = if (crashReport != null) "orbit_crash_$timestamp.zip" else "$entryName.zip"
            val (filePath, savedUri, logIncluded) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveUsingMediaStore(context, fileName, entryName, logFile, logLineCount, flush, crashReport)
            } else {
                saveUsingFileSystem(context, fileName, entryName, logFile, logLineCount, flush, crashReport)
            }

            LogcatExportResult(
                message = if (!logIncluded) context.getString(R.string.logcat_crash_log_missing, filePath)
                    else if (flush.complete) context.getString(R.string.logcat_saved_to, filePath)
                    else {
                        val reasons = buildList {
                            if (flush.pendingRecords > 0) add(context.getString(R.string.logcat_export_pending, flush.pendingRecords))
                            if (flush.droppedRecords > 0) add(context.getString(R.string.logcat_export_dropped, flush.droppedRecords))
                            if (flush.writeFailed) add(context.getString(R.string.logcat_export_write_failed))
                            if (flush.interrupted) add(context.getString(R.string.logcat_export_interrupted))
                        }
                        context.getString(R.string.logcat_flush_incomplete, filePath) + "\n" + reasons.joinToString("\n")
                    },
                success = true,
                uri = savedUri,
                fileName = fileName,
                complete = flush.complete && logIncluded,
            )
        } catch (e: Exception) {
            LogcatExportResult(
                message = context.getString(
                    R.string.logcat_save_failed,
                    e.message ?: context.getString(R.string.logcat_unknown_error)
                ),
                success = false
            )
        }
    }

    private fun countExportableLogLines(logFile: File): Long {
        var count = 0L
        logFile.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    count++
                }
            }
        }
        return count
    }

    /** Returns how many log lines went in. */
    private fun writeLogContent(
        context: Context,
        writer: Writer,
        logFile: File?,
        logLineCount: Long
    ): Long {
        val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        writer.appendLine(context.getString(R.string.logcat_header))
        writer.appendLine(context.getString(R.string.logcat_date, exportTime))
        writer.appendLine(context.getString(R.string.logcat_total_count, logLineCount))
        writer.appendLine("===================================")
        writer.appendLine()

        var written = 0L
        logFile?.bufferedReader()?.useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    writer.appendLine(line)
                    written++
                }
            }
        }
        return written
    }

    /**
     * 把日志写进 zip 中的文本条目。
     *
     * 条目内容与原来的纯文本日志完全一致，只是整体做了一次 deflate 压缩：请求体转储这类
     * 高度重复的 JSON 文本压缩比约 3:1（实测 27 MB 的日志压到约 8 MB，约三成），解压后按原样
     * 阅读和检索。
     */
    /** The whole app log as the zip the export saves, written to [outputStream]. */
    fun writeZipTo(context: Context, outputStream: OutputStream, entryName: String): Boolean {
        val flush = AppLogger.flushFileLogsWithResult()
        val logFile = AppLogger.getLogFile()?.takeIf { it.isFile && it.length() > 0 }
        val logLineCount = logFile?.let(::countExportableLogLines) ?: 0L
        if (logLineCount == 0L && !MemoryDiagnostics.hasRecords(context)) return false
        writeZipLog(context, outputStream, entryName, logFile, logLineCount, flush)
        return true
    }

    /**
     * Writes the zip; false when, for a crash export, the app log is missing or could not be read
     * whole. A plain log export keeps its old meaning: it fails on a read error instead.
     */
    private fun writeZipLog(
        context: Context,
        outputStream: OutputStream,
        entryName: String,
        logFile: File?,
        logLineCount: Long,
        flush: LogFlushResult,
        crashReport: String? = null,
    ): Boolean {
        var logFailure: java.io.IOException? = null
        var written = 0L
        ZipOutputStream(BufferedOutputStream(outputStream)).use { zip ->
            if (crashReport != null) {
                zip.putNextEntry(ZipEntry("crash-report.txt"))
                zip.write(crashReport.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(entryName))
            val writer = BufferedWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            logFailure = try {
                written = writeLogContent(context, writer, logFile, logLineCount)
                null
            } catch (e: java.io.IOException) {
                // Only a crash export goes on without the log; a plain log export fails as before.
                if (crashReport == null) throw e
                e
            }
            val logIncluded = logFailure == null && (crashReport == null || written > 0)
            writer.flush()
            zip.closeEntry()
            logFailure?.let { failure ->
                zip.putNextEntry(ZipEntry("log-export-error.txt"))
                zip.write("The app log could not be read: ${failure.message}\n".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("export-status.txt"))
            zip.write(
                ("scope=records submitted before export started\n" +
                    "complete=${flush.complete && logIncluded}\npending_records=${flush.pendingRecords}\n" +
                    "dropped_records=${flush.droppedRecords}\nwrite_failed=${flush.writeFailed}\n" +
                    "interrupted=${flush.interrupted}\napp_log_included=$logIncluded\n").toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            if (crashReport == null) {
                MemoryDiagnostics.exportTo(context, zip)
            } else {
                // The crash report already in the file is worth more than the memory samples.
                try {
                    MemoryDiagnostics.exportTo(context, zip)
                } catch (e: java.io.IOException) {
                    runCatching { zip.closeEntry() }
                    zip.putNextEntry(ZipEntry("memory-diagnostics-error.txt"))
                    zip.write("Memory diagnostics could not be read: ${e.message}\n".toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        return logFailure == null && (crashReport == null || written > 0)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveUsingMediaStore(
        context: Context,
        fileName: String,
        entryName: String,
        logFile: File?,
        logLineCount: Long,
        flush: LogFlushResult,
        crashReport: String?,
    ): Triple<String, android.net.Uri, Boolean> {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/operit")
                // Hidden from other apps until it is whole; one left half-written is removed below.
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: throw Exception(context.getString(R.string.logcat_cannot_create_file))

            val logIncluded = try {
                val included = context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    writeZipLog(context, outputStream, entryName, logFile, logLineCount, flush, crashReport)
                } ?: throw Exception(context.getString(R.string.logcat_cannot_open_output_stream))
                val published = context.contentResolver.update(
                    uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                if (published <= 0) throw Exception(context.getString(R.string.logcat_cannot_create_file))
                included
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }

            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            return Triple("${downloadsDir.absolutePath}/operit/$fileName", uri, logIncluded)
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.logcat_mediestore_save_failed, e.message ?: ""))
        }
    }

    private fun saveUsingFileSystem(
        context: Context,
        fileName: String,
        entryName: String,
        logFile: File?,
        logLineCount: Long,
        flush: LogFlushResult,
        crashReport: String?,
    ): Triple<String, android.net.Uri, Boolean> {
        try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir == null || !downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw Exception(context.getString(R.string.logcat_cannot_create_download_dir))
            }
            val operitDir = File(downloadsDir, "operit")
            if (!operitDir.exists() && !operitDir.mkdirs()) {
                throw Exception(context.getString(R.string.logcat_cannot_create_operit_dir))
            }
            val file = File(operitDir, fileName)
            val logIncluded = try {
                FileOutputStream(file).use { outputStream ->
                    writeZipLog(context, outputStream, entryName, logFile, logLineCount, flush, crashReport)
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            if (!file.exists() || file.length() == 0L) {
                throw Exception(context.getString(R.string.logcat_file_create_failed))
            }
            return Triple(file.absolutePath,
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file),
                logIncluded)
        } catch (e: Exception) {
            throw Exception(context.getString(R.string.logcat_filesystem_save_failed, e.message ?: ""))
        }
    }
}

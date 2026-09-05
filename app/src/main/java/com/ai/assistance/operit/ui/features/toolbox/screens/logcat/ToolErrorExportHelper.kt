package com.ai.assistance.operit.ui.features.toolbox.screens.logcat

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ai.assistance.operit.core.tools.ToolErrorRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ToolErrorExportHelper {
    suspend fun export(
        context: Context,
        toolName: String?,
        since: Long,
        repository: ToolErrorRepository = ToolErrorRepository.getInstance(context),
    ): String =
        withContext(Dispatchers.IO) {
            val name = "operit_tool_errors_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())}.zip"
            val temporary = File.createTempFile("tool-errors-", ".zip", context.cacheDir)
            try {
                temporary.outputStream().use {
                    repository.export(it, toolName, since)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/operit")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                    try {
                        checkNotNull(resolver.openOutputStream(uri)).use { target ->
                            temporary.inputStream().use { it.copyTo(target) }
                        }
                        resolver.update(uri, ContentValues().apply {
                            put(MediaStore.MediaColumns.IS_PENDING, 0)
                        }, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "operit")
                    check(folder.isDirectory || folder.mkdirs())
                    val target = File(folder, name)
                    try {
                        temporary.copyTo(target)
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                }
                "${Environment.DIRECTORY_DOWNLOADS}/operit/$name"
            } finally {
                temporary.delete()
            }
        }
}

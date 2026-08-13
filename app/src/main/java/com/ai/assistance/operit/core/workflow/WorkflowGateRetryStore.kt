package com.ai.assistance.operit.core.workflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

internal class WorkflowGateRetryStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    fun mark(workRequestId: UUID): Boolean = synchronized(lock) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching false
            val atomicFile = AtomicFile(markerFile(workRequestId))
            val output = atomicFile.startWrite()
            try {
                output.write(byteArrayOf(1))
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
            true
        }.getOrDefault(false)
    }

    fun isMarked(workRequestId: UUID): Boolean = synchronized(lock) {
        runCatching {
            val marker = markerFile(workRequestId)
            if (!atomicMarkerMayExist(marker.isFile, File(marker.path + ".bak").isFile)) {
                return@runCatching false
            }
            AtomicFile(marker).openRead().use { }
            true
        }.getOrDefault(false)
    }

    fun consume(workRequestId: UUID) = synchronized(lock) {
        AtomicFile(markerFile(workRequestId)).delete()
    }

    private fun markerFile(workRequestId: UUID): File =
        File(directory, "$workRequestId.gate_retry")

    private companion object {
        const val DIRECTORY_NAME = "workflow_gate_retries"
        val lock = Any()
    }
}

internal fun atomicMarkerMayExist(baseExists: Boolean, backupExists: Boolean): Boolean =
    baseExists || backupExists

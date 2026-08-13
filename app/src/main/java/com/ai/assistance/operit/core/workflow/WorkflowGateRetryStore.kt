package com.ai.assistance.operit.core.workflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

internal const val WORKFLOW_GATE_RETRY_DEFERRED: Byte = 1
internal const val WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED: Byte = 2

internal fun canClaimWorkflowGateRetry(markerState: Byte?): Boolean =
    markerState == WORKFLOW_GATE_RETRY_DEFERRED ||
        markerState == WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED

internal fun canExecuteWorkflowGateRetry(markerState: Byte?): Boolean =
    markerState != WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED

internal fun workflowGateRetryStateAfterDeferral(markerState: Byte?): Byte =
    if (markerState == WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED) {
        WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED
    } else {
        WORKFLOW_GATE_RETRY_DEFERRED
    }

internal class WorkflowGateRetryStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, DIRECTORY_NAME)

    fun mark(workRequestId: UUID): Boolean = synchronized(lock) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) return@runCatching false
            val atomicFile = AtomicFile(markerFile(workRequestId))
            val currentState = readState(atomicFile)
            val nextState = workflowGateRetryStateAfterDeferral(currentState)
            if (nextState != currentState) writeState(atomicFile, nextState)
            true
        }.getOrDefault(false)
    }

    fun claimForReconciliation(workRequestId: UUID): Boolean = synchronized(lock) {
        runCatching {
            val atomicFile = AtomicFile(markerFile(workRequestId))
            when (val state = readState(atomicFile)) {
                WORKFLOW_GATE_RETRY_DEFERRED ->
                    writeState(atomicFile, WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED)
                WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED -> Unit
                else -> return@runCatching false
            }
            true
        }.getOrDefault(false)
    }

    /** Returns false only when reconciliation already owns this previously gated request. */
    fun consumeForExecution(workRequestId: UUID): Boolean = synchronized(lock) {
        val atomicFile = AtomicFile(markerFile(workRequestId))
        runCatching { readState(atomicFile) }.fold(
            onSuccess = { state ->
                val canExecute = canExecuteWorkflowGateRetry(state)
                if (canExecute) atomicFile.delete()
                canExecute
            },
            onFailure = { false },
        )
    }

    fun consume(workRequestId: UUID) = synchronized(lock) {
        AtomicFile(markerFile(workRequestId)).delete()
    }

    private fun markerFile(workRequestId: UUID): File =
        File(directory, "$workRequestId.gate_retry")

    private fun readState(atomicFile: AtomicFile): Byte? {
        val marker = atomicFile.baseFile
        if (!atomicMarkerMayExist(marker.isFile, File(marker.path + ".bak").isFile)) return null
        val state = atomicFile.openRead().use { input -> input.read() }
        require(
            state == WORKFLOW_GATE_RETRY_DEFERRED.toInt() ||
                state == WORKFLOW_GATE_RETRY_RECONCILIATION_CLAIMED.toInt()
        ) { "Invalid workflow gate retry marker" }
        return state.toByte()
    }

    private fun writeState(atomicFile: AtomicFile, state: Byte) {
        val output = atomicFile.startWrite()
        try {
            output.write(byteArrayOf(state))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "workflow_gate_retries"
        val lock = Any()
    }
}

internal fun atomicMarkerMayExist(baseExists: Boolean, backupExists: Boolean): Boolean =
    baseExists || backupExists

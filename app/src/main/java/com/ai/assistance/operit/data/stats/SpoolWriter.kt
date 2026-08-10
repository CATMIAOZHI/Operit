package com.ai.assistance.operit.data.stats

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Worker construction and admission calculations for durable spool writes. */
internal object SpoolWriter {
    fun newDrainExecutor(): ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "operit-token-stats-writer").apply { isDaemon = true }
        }

    fun newInsertExecutor(): ThreadPoolExecutor =
        ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(1)
        ) { runnable -> Thread(runnable, "operit-token-stats-insert").apply { isDaemon = true } }

    fun newDatabaseExecutor(): ThreadPoolExecutor =
        ThreadPoolExecutor(
            1,
            1,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(1)
        ) { runnable -> Thread(runnable, "operit-token-stats-database").apply { isDaemon = true } }

    fun dataAdmissionMaxBytes(cap: Long, metadataReserveBytes: Long, maxLineBytes: Int): Long {
        val reserve = minOf(metadataReserveBytes, cap - maxLineBytes).coerceAtLeast(0L)
        return (cap - reserve).coerceAtLeast(0L)
    }

    fun metadataWriteBudgetExceeded(
        currentBytes: Long,
        contentBytes: Int,
        metadataCopyCount: Int,
        cap: Long
    ): Boolean = currentBytes + contentBytes.toLong() * metadataCopyCount > cap
}

package com.ai.assistance.operit.util

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.ai.assistance.operit.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Automatic metadata-only diagnostics. No forced GC, heap dumps, prompts or account identifiers. */
internal object MemoryDiagnostics {
    private var started = false
    private var store: MemoryDiagnosticStore? = null
    private val trimLevel = AtomicInteger(-1)

    @Synchronized
    private fun store(context: Context): MemoryDiagnosticStore =
        store ?: MemoryDiagnosticStore(File(context.noBackupFilesDir, "memory-diagnostics"))
            .also { store = it }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val target = try { store(context) } catch (_: Exception) { return }
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val policy = MemoryDiagnosticPolicy()
            val history = ArrayDeque<String>()
            while (isActive) {
                try {
                    val runtime = Runtime.getRuntime()
                    val elapsed = SystemClock.elapsedRealtime()
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    val max = runtime.maxMemory()
                    val counters = MemoryDiagnosticMetrics.sample()
                    val trim = trimLevel.getAndSet(-1)
                    val line = "wall_ms=${System.currentTimeMillis()} elapsed_ms=$elapsed " +
                        "pid=${Process.myPid()} version=${BuildConfig.VERSION_NAME} " +
                        "heap_used_bytes=$used heap_max_bytes=$max " +
                        "native_allocated_bytes=${Debug.getNativeHeapAllocatedSize()} trim_level=$trim " +
                        "streams=${counters.streams} replay_events=${counters.replayEvents} " +
                        "max_backlog_events=${counters.maxBacklog} subscribers=${counters.subscribers} " +
                        "display_buffers=${counters.displayBuffers} display_chars=${counters.displayChars} " +
                        "metrics_scope=latest_512_live_sources"
                    history.addLast(line)
                    if (history.size > 20) history.removeFirst()
                    target.append(line)
                    val reason = policy.observe(MemoryDiagnosticPolicy.Point(elapsed, used, max))
                    if (reason != null) {
                        target.append("incident=$reason\n${history.joinToString("\n")}\nend_incident", true)
                    }
                } catch (_: Exception) {
                    // Diagnostic I/O is best effort; never feed an overloaded main logger queue.
                } catch (_: OutOfMemoryError) {
                    // The pre-OOM samples are already on disk. Stop rather than allocate a report.
                    break
                }
                delay(15_000)
            }
        }
    }

    fun onTrimMemory(level: Int) { trimLevel.set(level) }
    fun hasRecords(context: Context): Boolean = store(context).hasRecords()
    fun exportTo(context: Context, zip: ZipOutputStream) = store(context).exportTo(zip)
}

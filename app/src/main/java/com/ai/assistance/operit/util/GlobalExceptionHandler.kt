package com.ai.assistance.operit.util

import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.ui.error.CrashReportActivity
import kotlin.system.exitProcess

/**
 * [processName] is this process's name. The main process records the crash (for its log and for
 * Orbit) and shows the crash page; another app process only shows the page; the crash page's own
 * process hands a failure back to the system, so a broken crash page cannot start itself again.
 */
class GlobalExceptionHandler(
    private val context: Context,
    processName: String?,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    // Worked out now, so nothing needs allocating to tell them apart when memory has run out.
    private val crashPageProcess = processName == "${context.packageName}:crash"
    private val mainProcess = processName == null || processName == context.packageName

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (crashPageProcess) {
            previous?.uncaughtException(thread, ex) ?: exitProcess(1)
            return
        }
        if (mainProcess) {
            // Orbit hears of it after the next start; a failure here must not stop the crash page.
            try {
                CrashRecoveryState.recordCrash(context, System.currentTimeMillis(), crashSummary(thread, ex))
            } catch (_: Throwable) {
            }
        }
        // Out of memory, even the report may not be built: a fixed line is shown instead.
        val stackTrace = try {
            crashReportText(thread, ex)
        } catch (_: Throwable) {
            REPORT_UNAVAILABLE
        }

        try {
            val intent =
                    Intent(context, CrashReportActivity::class.java).apply {
                        putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
            context.startActivity(intent)
        } catch (_: Throwable) {
            // No crash page: the system reports it as usual.
            previous?.uncaughtException(thread, ex)
        }

        // 终止当前进程
        exitProcess(1)
    }

    /**
     * The failure's own stack says where the exception was created, which cannot tell a failure that
     * escaped the UI thread from one that escaped a background dispatcher — and for a coroutine that
     * is exactly what decides who let it out. The report therefore names the thread and the frames it
     * was running, so the next report can be read instead of guessed at.
     */
    private fun crashReportText(thread: Thread, ex: Throwable): String = buildString {
        append("Thread: ").append(thread.name).append('\n')
        append("Thread frames:\n")
        thread.stackTrace.take(MAX_THREAD_FRAMES).forEach { frame ->
            append("    at ").append(frame).append('\n')
        }
        append('\n')
        append(ThrowableTextFormatter.format(ex))
    }

    /**
     * What failed, for Orbit: the thread, then each cause's type and the frame it was thrown at.
     * Messages are left out: they can hold request bodies, addresses or replies from elsewhere,
     * which stay in the report the user exports.
     */
    private fun crashSummary(thread: Thread, ex: Throwable): String = buildString {
        append("Thread: ").append(thread.name)
        var current: Throwable? = ex
        var depth = 0
        while (depth < MAX_SUMMARY_CAUSES) {
            val failure = current ?: break
            append('\n').append(if (depth == 0) "" else "Caused by: ").append(failure.javaClass.name)
            failure.stackTrace.firstOrNull()?.let { append(" at ").append(it) }
            current = failure.cause?.takeIf { it !== failure }
            depth++
        }
    }

    private companion object {
        /** Enough frames to name the dispatcher and the scope, few enough to keep the report small. */
        const val MAX_THREAD_FRAMES = 40
        const val MAX_SUMMARY_CAUSES = 3
        const val REPORT_UNAVAILABLE = "The crash report could not be built (the app may have run out of memory)."
    }
}

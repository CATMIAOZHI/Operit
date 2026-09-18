package com.ai.assistance.operit.util

import android.content.Context
import android.content.Intent
import com.ai.assistance.operit.ui.error.CrashReportActivity
import kotlin.system.exitProcess

class GlobalExceptionHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        CrashRecoveryState.markPendingCrashReportLaunch(context)
        val stackTrace = crashReportText(thread, ex)

        val intent =
                Intent(context, CrashReportActivity::class.java).apply {
                    putExtra(CrashReportActivity.EXTRA_STACK_TRACE, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
        context.startActivity(intent)

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

    private companion object {
        /** Enough frames to name the dispatcher and the scope, few enough to keep the report small. */
        const val MAX_THREAD_FRAMES = 40
    }
}

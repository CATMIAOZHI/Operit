package com.ai.assistance.operit.util

import android.content.Context

object CrashRecoveryState {
    private const val PREFS_NAME = "crash_recovery_state"
    private const val KEY_PRESERVE_LOGS_FOR_CRASH_REPORT = "preserve_logs_for_crash_report"
    /** The last crash, kept until Orbit has heard of it: when it happened and a short line of what failed. */
    private const val KEY_CRASH_AT = "crash_at"
    private const val KEY_CRASH_SUMMARY = "crash_summary"

    class Crash(val at: Long, val summary: String)

    /** This process recorded its own crash: its start-up must not clear the mark it just wrote. */
    @Volatile
    private var crashedHere = false

    fun markPendingCrashReportLaunch(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT, true)
            .commit()
    }

    /**
     * The main process crashed: keeps its log for the next start and remembers the crash for Orbit,
     * in one write committed at once, since the process is dying. Only the main process writes
     * this, so no other process can put back a stale copy of these preferences. The crash record's
     * reads and writes are serialized, so forgetting an old crash can never remove a newer one.
     */
    @Synchronized
    fun recordCrash(context: Context, at: Long, summary: String) {
        crashedHere = true
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT, true)
            .putLong(KEY_CRASH_AT, at)
            .putString(KEY_CRASH_SUMMARY, summary)
            .commit()
    }

    @Synchronized
    fun lastCrash(context: Context): Crash? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_CRASH_AT, 0L).takeIf { it > 0L } ?: return null
        return Crash(at, prefs.getString(KEY_CRASH_SUMMARY, null).orEmpty())
    }

    /** Forgets the crash at [at]; a newer one recorded meanwhile stays. */
    @Synchronized
    fun forgetCrash(context: Context, at: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_CRASH_AT, 0L) == at) {
            prefs.edit().remove(KEY_CRASH_AT).remove(KEY_CRASH_SUMMARY).commit()
        }
    }

    @Synchronized
    fun consumePendingCrashReportLaunch(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shouldPreserveLogs = prefs.getBoolean(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT, false)
        if (shouldPreserveLogs && !crashedHere) {
            // Read during start-up on the main thread; the process lives on, so the write can wait.
            prefs.edit().remove(KEY_PRESERVE_LOGS_FOR_CRASH_REPORT).apply()
        }
        return shouldPreserveLogs
    }
}

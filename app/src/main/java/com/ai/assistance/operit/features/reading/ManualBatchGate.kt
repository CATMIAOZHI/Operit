package com.ai.assistance.operit.features.reading

/** Process-wide mutual exclusion for explicit manual reading-companion batches. */
internal object ManualBatchGate {
    class Lease internal constructor(
        internal val id: Long,
        val kind: String,
        val bookId: String,
    )

    private var nextLeaseId = 1L
    private var activeLease: Lease? = null

    /** Returns an ownership token, or null while any other manual batch call is active. */
    @Synchronized
    fun acquire(kind: String, bookId: String): Lease? {
        if (activeLease != null) return null
        return Lease(
            id = nextLeaseId++,
            kind = kind,
            bookId = bookId,
        ).also { activeLease = it }
    }

    /** Releases only the exact lease returned by [acquire], preventing stale finally blocks. */
    @Synchronized
    fun release(lease: Lease) {
        if (activeLease?.id == lease.id) {
            activeLease = null
        }
    }
}

package com.ai.assistance.operit.util

import org.junit.Assert.*
import org.junit.Test

class MemoryDiagnosticPolicyTest {
    private fun point(seconds: Long, megabytes: Long) =
        MemoryDiagnosticPolicy.Point(seconds * 1000, megabytes * 1024 * 1024, 512L * 1024 * 1024)

    @Test fun briefSpikeDoesNotCountAsSustainedPressure() {
        val policy = MemoryDiagnosticPolicy()
        assertNull(policy.observe(point(0, 420)))
        assertNull(policy.observe(point(15, 200)))
        assertNull(policy.observe(point(30, 420)))
        assertNull(policy.observe(point(45, 420)))
        assertEquals("sustained_high_heap", policy.observe(point(60, 420)))
        assertNull(policy.observe(point(75, 420)))
    }

    @Test fun criticalPressureEscalatesDespiteNormalCooldown() {
        val policy = MemoryDiagnosticPolicy()
        policy.observe(point(0, 260))
        policy.observe(point(15, 300))
        assertEquals("rapid_heap_growth", policy.observe(point(30, 350)))
        assertEquals("critical_heap", policy.observe(point(45, 480)))
        assertNull(policy.observe(point(60, 480)))
        assertEquals("critical_heap", policy.observe(point(105, 480)))
    }

    @Test fun sleepingDeviceDoesNotCountSparseReadingsAsContinuousHighUsage() {
        val policy = MemoryDiagnosticPolicy()
        assertNull(policy.observe(point(0, 420)))
        assertNull(policy.observe(point(15, 420)))
        assertNull(policy.observe(point(600, 420)))
        assertNull(policy.observe(point(615, 420)))
        assertEquals("sustained_high_heap", policy.observe(point(630, 420)))
    }

    @Test fun lowUsageAndInvalidCapacityDoNotAlert() {
        val policy = MemoryDiagnosticPolicy()
        assertNull(policy.observe(MemoryDiagnosticPolicy.Point(0, 100, 0)))
        repeat(50) { assertNull(policy.observe(point(it * 15L, 100))) }
    }
}

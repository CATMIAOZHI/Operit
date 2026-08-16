package com.ai.assistance.operit.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainProcessStartupGateTest {
    @Test
    fun `completed startup is claimed once per process`() {
        val gate = MainProcessStartupGate()
        val lease = gate.claim()

        assertNotNull(lease)
        assertNull(gate.claim())
        assertTrue(gate.complete(lease!!))
        assertNull(gate.claim())
    }

    @Test
    fun `cancelled activity releases only its own startup lease`() {
        val gate = MainProcessStartupGate()
        val firstLease = gate.claim()!!

        assertTrue(gate.release(firstLease))
        val secondLease = gate.claim()
        assertNotNull(secondLease)
        assertFalse(gate.release(firstLease))
        assertFalse(gate.complete(firstLease))
        assertTrue(gate.complete(secondLease!!))
        assertNull(gate.claim())
    }
}

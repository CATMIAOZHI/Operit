package com.ai.assistance.operit.ui.main

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainProcessStartupGateTest {
    @Test
    fun `activity checks distinguish fresh reopen recreation and process restoration`() {
        assertTrue(
            shouldRunActivityStartupChecks(
                isFreshActivityLaunch = true,
                hasProcessStartupLease = false,
            )
        )
        assertFalse(
            shouldRunActivityStartupChecks(
                isFreshActivityLaunch = false,
                hasProcessStartupLease = false,
            )
        )
        assertTrue(
            shouldRunActivityStartupChecks(
                isFreshActivityLaunch = false,
                hasProcessStartupLease = true,
            )
        )
    }

    @Test
    fun `process-owned initialization still requires a startup lease`() {
        assertTrue(
            shouldStartProcessOwnedInitialization(
                hasProcessStartupLease = true,
                showPermissionGuide = false,
                agreementAccepted = true,
            )
        )
        assertFalse(
            shouldStartProcessOwnedInitialization(
                hasProcessStartupLease = false,
                showPermissionGuide = false,
                agreementAccepted = true,
            )
        )
    }

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

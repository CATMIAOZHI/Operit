package com.ai.assistance.operit.core.application

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredCompatibilityInitializationTest {

    @Test
    fun `failure keeps consumers gated and exposes the original error`() {
        val expected = IllegalStateException("datastore unavailable")
        var observed: Exception? = null
        var consumersStarted = false

        val ready = runRequiredCompatibilityInitialization(
            initialize = { throw expected },
            onFailure = { observed = it },
        )
        if (ready) consumersStarted = true

        assertFalse(ready)
        assertFalse(consumersStarted)
        assertSame(expected, observed)
    }

    @Test
    fun `success permits consumer initialization`() {
        var initialized = false
        val ready = runRequiredCompatibilityInitialization(
            initialize = { initialized = true },
            onFailure = { error("unexpected failure") },
        )

        assertTrue(ready)
        assertTrue(initialized)
    }

    @Test
    fun `failed attempt keeps its message after a later successful result`() {
        val failed =
            OperitApplication.MainApplicationInitResult.CompatibilityInitializationFailed(
                "first attempt failed"
            )
        val later = OperitApplication.MainApplicationInitResult.Initialized

        assertEquals("first attempt failed", failed.message)
        assertSame(OperitApplication.MainApplicationInitResult.Initialized, later)
    }
}

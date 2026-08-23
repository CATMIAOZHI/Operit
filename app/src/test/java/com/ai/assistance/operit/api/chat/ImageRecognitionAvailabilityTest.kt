package com.ai.assistance.operit.api.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageRecognitionAvailabilityTest {
    @Test
    fun configuredBackendIsAvailableToRootChat() {
        assertTrue(
            resolveImageRecognitionAvailability(
                isSubTask = false,
                hasConfiguredBackend = true,
            )
        )
    }

    @Test
    fun configuredBackendIsAvailableToSubTask() {
        assertTrue(
            resolveImageRecognitionAvailability(
                isSubTask = true,
                hasConfiguredBackend = true,
            )
        )
    }

    @Test
    fun absentBackendRemainsUnavailable() {
        assertFalse(
            resolveImageRecognitionAvailability(
                isSubTask = true,
                hasConfiguredBackend = false,
            )
        )
    }
}

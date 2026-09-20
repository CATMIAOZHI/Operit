package com.ai.assistance.operit.pet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pet's own transparency: full while the task bubble is open, the configured value once it is
 * closed. The bubble is never drawn with it, so a translucent pet keeps a solid bubble.
 */
class PetTaskOpacityTest {
    @Test
    fun `an open task bubble draws the pet at full opacity`() {
        assertEquals(1f, petTaskOpacity(preview = false, bubbleOnScreen = true, configuredOpacity = 0.4f), 0f)
    }

    @Test
    fun `the pet returns to its configured transparency once the bubble is closed`() {
        assertEquals(0.4f, petTaskOpacity(preview = false, bubbleOnScreen = false, configuredOpacity = 0.4f), 0f)
        assertEquals(1f, petTaskOpacity(preview = false, bubbleOnScreen = false, configuredOpacity = 1f), 0f)
    }

    @Test
    fun `the settings preview keeps following the transparency slider`() {
        assertEquals(0.4f, petTaskOpacity(preview = true, bubbleOnScreen = true, configuredOpacity = 0.4f), 0f)
        assertEquals(0.4f, petTaskOpacity(preview = true, bubbleOnScreen = false, configuredOpacity = 0.4f), 0f)
    }
}

package com.ai.assistance.operit.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyStorageRestorePolicyTest {

    @Test
    fun `pre-feature restore reconstructs all missing switches`() {
        val repaired =
            repairMissingLegacyReadSwitches(
                current = LegacyReadSwitchValues(null, null, null),
                defaults = LegacyReadSwitchValues(true, false, true),
            )

        assertEquals(LegacyReadSwitchValues(true, false, true), repaired)
    }

    @Test
    fun `repair preserves explicit restored choices and fills only missing keys`() {
        val repaired =
            repairMissingLegacyReadSwitches(
                current = LegacyReadSwitchValues(false, true, null),
                defaults = LegacyReadSwitchValues(true, false, true),
            )

        assertEquals(LegacyReadSwitchValues(false, true, true), repaired)
    }
}

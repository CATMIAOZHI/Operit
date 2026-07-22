package com.ai.assistance.operit.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun devBuildNumberOrdersRapidIterations() {
        assertTrue(
            UpdateManager.compareVersions(
                "1.12.0+4-ry.1-dev.102",
                "1.12.0+4-ry.1-dev.101"
            ) > 0
        )
    }

    @Test
    fun devSuffixWithoutBuildNumberPreservesStableVersionOrdering() {
        assertEquals(
            0,
            UpdateManager.compareVersions("1.12.0+4-ry.1-dev", "1.12.0+4-ry.1")
        )
    }
}

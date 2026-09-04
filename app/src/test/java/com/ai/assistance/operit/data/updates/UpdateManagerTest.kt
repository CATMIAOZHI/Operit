package com.ai.assistance.operit.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun currentDevReleaseFormatOrdersBuildsWithoutPlusSuffix() {
        assertTrue(
            UpdateManager.compareVersions(
                "1.12.1-ry.3-dev.204",
                "1.12.1-ry.3-dev.203"
            ) > 0
        )
        assertTrue(
            UpdateManager.compareVersions(
                "1.12.1-ry.3-dev.203",
                "1.12.1-ry.3-dev.204"
            ) < 0
        )
        assertEquals(
            0,
            UpdateManager.compareVersions(
                "v1.12.1-ry.3-dev.204",
                "1.12.1-ry.3-dev.204"
            )
        )
    }

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

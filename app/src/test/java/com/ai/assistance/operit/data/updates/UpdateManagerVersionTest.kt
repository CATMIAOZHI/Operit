package com.ai.assistance.operit.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerVersionTest {
    @Test
    fun personalReleaseOnNewUpstreamBaseIsNewerThanPreviousRelease() {
        assertTrue(
            UpdateManager.compareVersions(
                "1.12.1-ry.1",
                "1.12.0+4-ry.1"
            ) > 0
        )
    }

    @Test
    fun matchingReleaseTagAndPackagedVersionDoNotRepeatUpdate() {
        assertEquals(
            0,
            UpdateManager.compareVersions(
                "v1.12.1-ry.1",
                "1.12.1-ry.1"
            )
        )
    }

    @Test
    fun personalRevisionOrdersReleasesOnSameUpstreamBase() {
        assertTrue(
            UpdateManager.compareVersions(
                "1.12.1-ry.2",
                "1.12.1-ry.1"
            ) > 0
        )
    }

    @Test
    fun legacyUpstreamBuildAndPersonalRevisionFormatRemainsSupported() {
        assertTrue(
            UpdateManager.compareVersions(
                "1.12.0+4-ry.1",
                "1.12.0+3-ry.9"
            ) > 0
        )
    }
}

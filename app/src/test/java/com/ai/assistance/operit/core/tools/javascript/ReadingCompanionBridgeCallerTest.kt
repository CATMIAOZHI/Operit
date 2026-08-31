package com.ai.assistance.operit.core.tools.javascript

import com.ai.assistance.operit.features.reading.ReadingCompanionService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingCompanionBridgeCallerTest {
    @Test
    fun basicAndAutoCommentarySubpackagesAreAllowed() {
        assertTrue(
            isReadingCompanionBridgeCaller(
                ReadingCompanionService.SUBPACKAGE_NAME,
                ReadingCompanionService.TOOLPKG_ID,
            ),
        )
        assertTrue(
            isReadingCompanionBridgeCaller(
                ReadingCompanionService.AUTO_COMMENTARY_SUBPACKAGE_NAME,
                ReadingCompanionService.TOOLPKG_ID,
            ),
        )
    }

    @Test
    fun unrelatedPackageOrContainerIsRejected() {
        assertFalse(
            isReadingCompanionBridgeCaller(
                "unrelated_package",
                ReadingCompanionService.TOOLPKG_ID,
            ),
        )
        assertFalse(
            isReadingCompanionBridgeCaller(
                ReadingCompanionService.SUBPACKAGE_NAME,
                "com.example.other",
            ),
        )
    }
}

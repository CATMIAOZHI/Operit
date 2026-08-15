package com.ai.assistance.operit.ui.features.packages.dialogs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageDetailsDialogPolicyTest {
    @Test
    fun `hides removal for built-in ToolPkg subpackages`() {
        assertFalse(
            shouldShowPackageRemovalAction(
                hasPackageMetadata = true,
                isBuiltIn = true,
                isToolPkgSubpackage = true,
            ),
        )
    }

    @Test
    fun `keeps removal for top-level packages`() {
        assertTrue(
            shouldShowPackageRemovalAction(
                hasPackageMetadata = true,
                isBuiltIn = true,
                isToolPkgSubpackage = false,
            ),
        )
    }
}

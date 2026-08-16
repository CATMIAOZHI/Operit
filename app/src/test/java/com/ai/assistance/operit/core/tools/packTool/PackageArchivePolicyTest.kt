package com.ai.assistance.operit.core.tools.packTool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageArchivePolicyTest {
    @Test
    fun `archived container owns its ToolPkg subpackages`() {
        assertEquals(
            "builtin-container",
            archivedBuiltInOwnerPackageName(
                normalizedPackageName = "builtin-child",
                owningContainerPackageName = "builtin-container",
                archivedPackageNames = setOf("builtin-container"),
            ),
        )
    }

    @Test
    fun `unarchived package has no archived owner`() {
        assertNull(
            archivedBuiltInOwnerPackageName(
                normalizedPackageName = "builtin-child",
                owningContainerPackageName = "builtin-container",
                archivedPackageNames = emptySet(),
            ),
        )
    }
}

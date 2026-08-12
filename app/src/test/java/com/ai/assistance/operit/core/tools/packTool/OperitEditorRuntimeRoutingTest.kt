package com.ai.assistance.operit.core.tools.packTool

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitEditorRuntimeRoutingTest {
    @Test
    fun debugOperationsResolveCurrentVariantFromRuntimePackagesPath() {
        val source = File("src/main/assets/packages/operit_editor.js").readText()

        assertTrue(source.contains("packageSnapshot?.externalPackagesPath"))
        assertTrue(source.contains("toolPkgDebugInstallComponent: `${appPackage}/"))
        assertTrue(source.contains("Android\\/data\\/([^/]+)\\/files\\/packages"))
        assertFalse(
            source.contains(
                "com.rainy.operitry/com.ai.assistance.operit.core.tools.packTool.ToolPkgDebugInstallReceiver",
            ),
        )
        assertFalse(source.contains("/sdcard/Android/data/com.rainy.operitry/files/packages"))
    }
}

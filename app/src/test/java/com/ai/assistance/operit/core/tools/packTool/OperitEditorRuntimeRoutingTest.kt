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
        assertTrue(source.contains("toolPkgDebugInstallComponent: `${'$'}{appPackage}/"))
        assertTrue(source.contains("Android\\/data\\/([^/]+)\\/files\\/packages"))
        assertTrue(source.contains("<inline-code:${'$'}{scriptLabel}>"))
        assertFalse(
            source.contains(
                "com.rainy.operitry/com.ai.assistance.operit.core.tools.packTool.ToolPkgDebugInstallReceiver",
            ),
        )
        assertFalse(source.contains("/sdcard/Android/data/com.rainy.operitry/files/packages"))
        assertFalse(source.contains("jsTempDir"))

        val scriptStart = "async function debug_run_sandbox_script"
        val scriptEnd = "async function read_environment_variable"
        assertTrue(source.contains(scriptStart))
        assertTrue(source.contains(scriptEnd))
        val scriptFunction = source.substringAfter(scriptStart).substringBefore(scriptEnd)
        assertFalse(scriptFunction.contains("resolve_operit_runtime_targets"))
    }
}

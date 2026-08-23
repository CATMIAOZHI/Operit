package com.ai.assistance.operit.core.tools

import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Test

class ToolRuntimeContextBridgeTest {
    @Test
    fun blockingIoBridgePreservesToolRuntimeContext() = runBlocking {
        val expected =
            ToolExecutionManager.ToolRuntimeContext(
                parentModelSupportsVision = true,
                imageRecognitionModelAvailable = true,
            )

        runBlocking(ToolExecutionManager.toolRuntimeContextElement(expected)) {
            val actual =
                runBlockingIoPreservingToolRuntimeContext {
                    ToolExecutionManager.currentToolRuntimeContext()
                }

            assertSame(expected, actual)
        }
    }
}

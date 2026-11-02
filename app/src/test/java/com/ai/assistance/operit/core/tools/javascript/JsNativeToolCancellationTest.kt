package com.ai.assistance.operit.core.tools.javascript

import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.ToolExecutor
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

class JsNativeToolCancellationTest {
    @Test
    fun theDefaultStreamingAdapterInterruptsBlockingTools() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val executor = object : ToolExecutor {
            override fun invoke(tool: AITool): ToolResult {
                started.complete(Unit)
                try {
                    Thread.sleep(Long.MAX_VALUE)
                    error("Blocking tool unexpectedly completed")
                } finally {
                    stopped.complete(Unit)
                }
            }
        }
        val request = launch {
            executor.invokeAndStream(AITool("sleep", emptyList())).collect {}
        }
        try {
            withTimeout(2000) { started.await() }
            withTimeout(2000) { request.cancelAndJoin(); stopped.await() }
        } finally {
            request.cancel()
        }
    }

    @Test
    fun aToolsOwnCancellationSettlesThePromiseWhileItsScriptStaysActive() = runBlocking {
        for (streaming in listOf(false, true)) {
            val handler = Mockito.mock(AIToolHandler::class.java)
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Unconfined)
            val result = CompletableDeferred<Boolean>()
            Mockito.`when`(handler.executeToolAndStream(any())).thenReturn(
                flow<ToolResult> { throw CancellationException("Tool timed out") }
            )
            Mockito.`when`(handler.executeTool(any())).thenThrow(CancellationException("Tool timed out"))
            try {
                if (streaming) {
                    JsNativeInterfaceDelegates.callToolAsyncStreaming(
                        toolHandler = handler, scope = scope,
                        callbackId = "done", intermediateCallbackId = "partial",
                        toolType = "default", toolName = "visit_web", paramsJson = "{}",
                        binaryDataRegistry = ConcurrentHashMap(), binaryHandlePrefix = "binary:",
                        binaryDataThreshold = 1024,
                        sendToolResult = { _, text, isError -> result.complete(isError && text.contains("Tool timed out")) },
                        sendIntermediateResult = { _, _, _ -> error("Unexpected intermediate result") },
                    )
                } else {
                    JsNativeInterfaceDelegates.callToolAsync(
                        toolHandler = handler, scope = scope,
                        callbackId = "done", toolType = "default",
                        toolName = "visit_web", paramsJson = "{}",
                        binaryDataRegistry = ConcurrentHashMap(), binaryHandlePrefix = "binary:",
                        binaryDataThreshold = 1024,
                        sendToolResult = { _, text, isError -> result.complete(isError && text.contains("Tool timed out")) },
                    )
                }
                assertTrue(withTimeout(2000) { result.await() })
                assertTrue(job.isActive)
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun endingScriptCancelsBothNativeToolPathsWithoutLateResults() = runBlocking {
        for (streaming in listOf(false, true)) {
            val handler = Mockito.mock(AIToolHandler::class.java)
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Unconfined)
            var delivered = false
            Mockito.`when`(handler.executeToolAndStream(any())).thenReturn(
                flow<ToolResult> {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            )
            Mockito.doAnswer {
                started.complete(Unit)
                try {
                    Thread.sleep(Long.MAX_VALUE)
                    error("Blocking tool unexpectedly completed")
                } finally {
                    stopped.complete(Unit)
                }
            }.`when`(handler).executeTool(any())
            try {
                if (streaming) {
                    JsNativeInterfaceDelegates.callToolAsyncStreaming(
                        toolHandler = handler, scope = scope,
                        callbackId = "done", intermediateCallbackId = "partial",
                        toolType = "default", toolName = "visit_web", paramsJson = "{}",
                        binaryDataRegistry = ConcurrentHashMap(), binaryHandlePrefix = "binary:",
                        binaryDataThreshold = 1024,
                        sendToolResult = { _, _, _ -> delivered = true },
                        sendIntermediateResult = { _, _, _ -> delivered = true },
                    )
                } else {
                    JsNativeInterfaceDelegates.callToolAsync(
                        toolHandler = handler, scope = scope,
                        callbackId = "done", toolType = "default",
                        toolName = "visit_web", paramsJson = "{}",
                        binaryDataRegistry = ConcurrentHashMap(), binaryHandlePrefix = "binary:",
                        binaryDataThreshold = 1024,
                        sendToolResult = { _, _, _ -> delivered = true },
                    )
                }
                withTimeout(2000) { started.await() }
                job.cancel()
                withTimeout(2000) { stopped.await(); job.join() }
                assertFalse(delivered)
            } finally {
                job.cancel()
            }
        }
    }
}

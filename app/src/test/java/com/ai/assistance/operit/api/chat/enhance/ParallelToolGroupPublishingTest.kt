package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolExecutionState
import com.ai.assistance.operit.data.model.ToolInvocation
import com.ai.assistance.operit.data.model.ToolResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelToolGroupPublishingTest {
    private fun invocation(name: String, index: Int) =
        ToolInvocation(
            tool = AITool(name = name),
            rawText = """<tool name="$name"></tool>""",
            responseLocation = 0..0,
            callId = "call-$index",
            invocationIndex = index,
        )

    private fun resultFor(invocation: ToolInvocation, text: String) =
        ToolResult(
            toolName = invocation.tool.name,
            success = true,
            result = StringResultData(text),
            callId = invocation.callId,
            invocationIndex = invocation.invocationIndex,
            executionState = ToolExecutionState.COMPLETED,
            isFinal = true,
        )

    @Test
    fun parallelGroupPublishesFinishedResultsInCallOrderBeforeRethrowingCancellation() =
        runBlocking {
            val first = invocation("agent-a", 0)
            val second = invocation("agent-b", 1)
            val third = invocation("agent-c", 2)
            val started = Channel<Int>(Channel.UNLIMITED)
            val results = ConcurrentHashMap<ToolInvocation, ToolResult>()
            // First two items record their results before signalling they started, so by the time
            // the test cancels the group both finished results are guaranteed to be available.
            val published = mutableListOf<Pair<String, String>>()
            val cancelled = CompletableDeferred<Unit>()
            val groupJob =
                launch {
                    try {
                        ToolExecutionManager.runParallelGroupWithOrderedPublishing(
                            items = listOf(first, second, third),
                            executionResults = results,
                            execute = { item ->
                                if (item.invocationIndex == 2) {
                                    started.send(item.invocationIndex)
                                    cancelled.await()
                                } else {
                                    results[item] = resultFor(item, "out-${item.tool.name}")
                                    started.send(item.invocationIndex)
                                }
                            },
                            publish = { item, result ->
                                published += item.tool.name to result.result.toString()
                            },
                        )
                    } catch (expected: CancellationException) {
                        cancelled.complete(Unit)
                        throw expected
                    }
                }
            List(3) { started.receive() }
            groupJob.cancel()
            runCatching { groupJob.join() }
            // The two finished results must be published in original call order even though the
            // group was cancelled and the third item never finished.
            assertEquals(
                listOf("agent-a" to "out-agent-a", "agent-b" to "out-agent-b"),
                published,
            )
        }

    @Test
    fun parallelGroupPublishesFinishedResultsInCallOrderBeforeRethrowingFailure() =
        runBlocking {
            val first = invocation("agent-a", 0)
            val second = invocation("agent-b", 1)
            val results = ConcurrentHashMap<ToolInvocation, ToolResult>()
            val published = mutableListOf<String>()
            val started = Channel<Int>(Channel.UNLIMITED)
            val release = CompletableDeferred<Unit>()
            val failureDeferred =
                async {
                    try {
                        ToolExecutionManager.runParallelGroupWithOrderedPublishing(
                            items = listOf(first, second),
                            executionResults = results,
                            execute = { item ->
                                started.send(item.invocationIndex)
                                results[item] = resultFor(item, "out-${item.tool.name}")
                                release.await()
                                if (item.invocationIndex == 1) {
                                    throw IllegalStateException("boom")
                                }
                            },
                            publish = { _, result -> published += result.result.toString() },
                        )
                        null
                    } catch (expected: IllegalStateException) {
                        expected.message
                    }
                }
            // Both items must have recorded their results before either is published.
            List(2) { started.receive() }
            assertEquals(emptyList<String>(), published)
            release.complete(Unit)
            assertEquals("boom", failureDeferred.await())
            assertEquals(listOf("out-agent-a", "out-agent-b"), published)
        }

    @Test
    fun parallelGroupPublishesAllResultsOnceInCallOrder() = runBlocking {
        val first = invocation("agent-a", 0)
        val second = invocation("agent-b", 1)
        val results = ConcurrentHashMap<ToolInvocation, ToolResult>()
        val published = mutableListOf<String>()
        val started = Channel<Int>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val finished =
            async {
                ToolExecutionManager.runParallelGroupWithOrderedPublishing(
                    items = listOf(first, second),
                    executionResults = results,
                    execute = { item ->
                        started.send(item.invocationIndex)
                        results[item] = resultFor(item, "out-${item.tool.name}")
                        release.await()
                    },
                    publish = { _, result -> published += result.result.toString() },
                )
            }
        // Both items must start before either result is published (whole group runs concurrently).
        List(2) { started.receive() }
        assertEquals(emptyList<String>(), published)
        release.complete(Unit)
        finished.await()
        assertEquals(listOf("out-agent-a", "out-agent-b"), published)
        assertTrue(results.values.isNotEmpty())
    }

    @Test
    fun cancellationDuringOrderedPublicationStillPublishesEveryFinishedResultExactlyOnce() =
        runBlocking {
            val first = invocation("agent-a", 0)
            val second = invocation("agent-b", 1)
            val third = invocation("agent-c", 2)
            val started = Channel<Int>(Channel.UNLIMITED)
            val results = ConcurrentHashMap<ToolInvocation, ToolResult>()
            val published = mutableListOf<String>()
            val recorded = mutableListOf<String>()
            val finished = CompletableDeferred<Unit>()
            val releasePublish = CompletableDeferred<Unit>()
            val publicationAttempted = Channel<String>(Channel.UNLIMITED)
            val job =
                launch {
                    try {
                        ToolExecutionManager.runParallelGroupWithOrderedPublishing(
                            items = listOf(first, second, third),
                            executionResults = results,
                            execute = { item ->
                                started.send(item.invocationIndex)
                                results[item] = resultFor(item, "out-${item.tool.name}")
                            },
                            publish = { _, result ->
                                publicationAttempted.send(result.result.toString())
                                releasePublish.await()
                                published += result.result.toString()
                            },
                            record = { result -> recorded += result.result.toString() },
                        )
                        finished.complete(Unit)
                    } catch (expected: CancellationException) {
                        finished.completeExceptionally(expected)
                    }
                }
            // Wait for all workers to finish and the first ordered publication to be in flight.
            List(3) { started.receive() }
            assertTrue(publicationAttempted.receive() == "out-agent-a")
            job.cancel()
            // Cancelling while the publication loop is suspended must not drop results: the whole
            // ordered publication runs in NonCancellable and completes exactly once, then the
            // cancellation is re-propagated to the caller.
            releasePublish.complete(Unit)
            runCatching { job.join() }
            assertTrue(finished.isCancelled)
            assertEquals(
                listOf("out-agent-a", "out-agent-b", "out-agent-c"),
                published,
            )
            assertEquals(
                listOf("out-agent-a", "out-agent-b", "out-agent-c"),
                recorded,
            )
        }
}

package com.ai.assistance.operit.ui.features.settings.screens

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2：导出失败/取消清理的调度缝测试——删除绝不在 Main；失败分支如实上报，取消分支在
 * NonCancellable 下完成有界清理后继续传播取消。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuarantineExportCleanupTest {
    private lateinit var mainExecutor: ExecutorService
    private val ioThreadNames = ConcurrentHashMap.newKeySet<String>()
    private lateinit var previousDispatcher: CoroutineDispatcher

    @Before
    fun setUp() {
        mainExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main-thread") }
        Dispatchers.setMain(mainExecutor.asCoroutineDispatcher())
        previousDispatcher = QuarantineExportCleanup.ioDispatcher
        QuarantineExportCleanup.ioDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                Dispatchers.IO.dispatch(context) {
                    ioThreadNames += Thread.currentThread().name
                    block.run()
                }
            }
        }
    }

    @After
    fun tearDown() {
        QuarantineExportCleanup.ioDispatcher = previousDispatcher
        QuarantineExportCleanup.deleteRecursivelyForTest = null
        ioThreadNames.clear()
        Dispatchers.resetMain()
        mainExecutor.shutdown()
    }

    private fun exportDir(): File =
        File.createTempFile("quarantine-export-cleanup", "").apply { delete(); mkdirs() }

    @Test
    fun `cleanup failure is reported and deletion never runs on the main thread`() = runBlocking {
        val destination = exportDir()
        var deleteCalls = 0
        var deleteThread = ""
        QuarantineExportCleanup.deleteRecursivelyForTest = {
            deleteCalls += 1
            deleteThread = Thread.currentThread().name
            false // 模拟删除失败
        }
        try {
            val cleaned = withContext(Dispatchers.Main) {
                QuarantineExportCleanup.deleteRecursively(destination)
            }
            assertFalse("cleanup failure must be reported to the caller", cleaned)
            assertEquals(1, deleteCalls)
            assertTrue("deletion must run on the injected IO dispatcher", ioThreadNames.isNotEmpty())
            assertFalse(
                "cleanup must never run on the main thread: $ioThreadNames",
                ioThreadNames.any { it == "test-main-thread" },
            )
            assertTrue("deletion thread must not be main", deleteThread != "test-main-thread")
        } finally {
            QuarantineExportCleanup.deleteRecursivelyForTest = null
        }
    }

    @Test
    fun `cleanup still runs when the caller coroutine is cancelled and cancellation propagates`() =
        runBlocking {
            val destination = exportDir()
            var cleanupCount = 0
            var cleanupResult: Boolean? = null
            var cleanupThread = ""
            QuarantineExportCleanup.deleteRecursivelyForTest = {
                cleanupCount += 1
                cleanupThread = Thread.currentThread().name
                true
            }
            try {
                // 门闩确保协程已进入 delay（否则 cancelAndJoin 会在块体启动前取消，catch 不执行）
                val enteredDelay = CompletableDeferred<Unit>()
                val job = launch(Dispatchers.Main) {
                    try {
                        enteredDelay.complete(Unit)
                        delay(Long.MAX_VALUE)
                    } catch (e: CancellationException) {
                        // 模拟 UI 取消分支：NonCancellable+IO 完成有界清理后再重抛取消
                        cleanupResult =
                            QuarantineExportCleanup.deleteRecursively(destination, nonCancellable = true)
                        throw e
                    }
                }
                enteredDelay.await()
                job.cancelAndJoin()
                assertEquals("cleanup must run exactly once despite cancellation", 1, cleanupCount)
                assertEquals("cleanup must report success under cancellation", true, cleanupResult)
                assertTrue(
                    "cleanup must run on IO even when the caller is cancelled",
                    cleanupThread != "test-main-thread",
                )
                assertFalse(
                    "cleanup must never run on the main thread: $ioThreadNames",
                    ioThreadNames.any { it == "test-main-thread" },
                )
                assertTrue("cancellation must keep propagating", job.isCancelled)
            } finally {
                QuarantineExportCleanup.deleteRecursivelyForTest = null
            }
        }
}

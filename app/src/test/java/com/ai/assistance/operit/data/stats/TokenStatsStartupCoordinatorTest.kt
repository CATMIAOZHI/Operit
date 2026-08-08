package com.ai.assistance.operit.data.stats

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * 启动统计 single-flight readiness 测试（P1 关键链路）：
 * - 并发调用 join 同一轮初始化（各步骤只执行一次）；
 * - 失败不缓存：下一轮重新执行并可成功。
 * 三个步骤全部注入（不触碰真实 Room/DataStore/spool）。
 */
class TokenStatsStartupCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        val root = kotlin.io.path.createTempDirectory("token-startup-coordinator").toFile()
        context = Mockito.mock(Context::class.java).also { ctx ->
            whenever(ctx.applicationContext).thenReturn(ctx)
            whenever(ctx.packageName).thenReturn("com.ai.assistance.operit")
            whenever(ctx.filesDir).thenReturn(root)
            whenever(ctx.getDatabasePath(any())).thenAnswer { File(root, it.getArgument<String>(0)) }
        }
    }

    @After
    fun tearDown() {
        TokenStatsStartupCoordinator.ensureMigratedStep = null
        TokenStatsStartupCoordinator.consumePendingRestoreStep = null
        TokenStatsStartupCoordinator.initialDrainStep = null
        TokenStatsStartupCoordinator.initializationTimeoutMsForTest = null
    }

    @Test
    fun `concurrent awaitInitialized joins a single initialization attempt`() = runBlocking {
        val enter = CountDownLatch(1)
        val release = CountDownLatch(1)
        val laterSteps = AtomicInteger(0)
        TokenStatsStartupCoordinator.ensureMigratedStep = {
            enter.countDown()
            // 初始化执行在协调器锁外：阻塞第一个步骤，验证并发调用 join 而非重入
            assertTrue(release.await(10, TimeUnit.SECONDS))
            true
        }
        TokenStatsStartupCoordinator.consumePendingRestoreStep = {
            laterSteps.incrementAndGet()
            true
        }
        TokenStatsStartupCoordinator.initialDrainStep = { _, _ ->
            laterSteps.incrementAndGet()
            true
        }
        try {
            // 显式分发到 IO：runBlocking 主线程随后会阻塞在 latch 上，默认分发（事件循环）
            // 的 async 在阻塞期间无法被调度。
            val a = async(Dispatchers.IO) { TokenStatsStartupCoordinator.awaitInitialized(context, 10_000) }
            val b = async(Dispatchers.IO) { TokenStatsStartupCoordinator.awaitInitialized(context, 10_000) }
            assertTrue("initialization must start", enter.await(5, TimeUnit.SECONDS))
            delay(100)
            assertEquals(
                "concurrent join must not start a second attempt",
                0,
                laterSteps.get(),
            )
            release.countDown()
            assertTrue(a.await())
            assertTrue(b.await())
            assertEquals("steps must run exactly once for the joined round", 2, laterSteps.get())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `failed initialization is not cached and a later call retries`() = runBlocking {
        val drainAttempts = AtomicInteger(0)
        TokenStatsStartupCoordinator.ensureMigratedStep = { true }
        TokenStatsStartupCoordinator.consumePendingRestoreStep = { true }
        TokenStatsStartupCoordinator.initialDrainStep = { _, _ ->
            drainAttempts.incrementAndGet()
            drainAttempts.get() == 2 // 第一次失败，第二次成功
        }
        assertFalse("first attempt failure must surface as not ready", TokenStatsStartupCoordinator.awaitInitialized(context, 10_000))
        assertTrue("failure must not be cached; retry succeeds", TokenStatsStartupCoordinator.awaitInitialized(context, 10_000))
        assertEquals(2, drainAttempts.get())
    }

    @Test
    fun `false prerequisite is not ready and is retried without running later steps`() = runBlocking {
        val migrationAttempts = AtomicInteger(0)
        val laterSteps = AtomicInteger(0)
        TokenStatsStartupCoordinator.ensureMigratedStep = {
            migrationAttempts.incrementAndGet() == 2
        }
        TokenStatsStartupCoordinator.consumePendingRestoreStep = {
            laterSteps.incrementAndGet()
            true
        }
        TokenStatsStartupCoordinator.initialDrainStep = { _, _ ->
            laterSteps.incrementAndGet()
            true
        }

        assertFalse(TokenStatsStartupCoordinator.awaitInitialized(context, 10_000))
        assertEquals("failed prerequisite must stop this round", 0, laterSteps.get())
        assertTrue(TokenStatsStartupCoordinator.awaitInitialized(context, 10_000))
        assertEquals(2, migrationAttempts.get())
        assertEquals(2, laterSteps.get())
    }

    @Test
    fun `end to end timeout completes false and a later call retries`() = runBlocking {
        val attempts = AtomicInteger(0)
        TokenStatsStartupCoordinator.initializationTimeoutMsForTest = 40L
        TokenStatsStartupCoordinator.ensureMigratedStep = {
            if (attempts.incrementAndGet() == 1) delay(100)
            true
        }
        TokenStatsStartupCoordinator.consumePendingRestoreStep = { true }
        TokenStatsStartupCoordinator.initialDrainStep = { _, _ -> true }

        assertFalse(TokenStatsStartupCoordinator.awaitInitialized(context, 1_000))
        TokenStatsStartupCoordinator.initializationTimeoutMsForTest = 500L
        assertTrue(TokenStatsStartupCoordinator.awaitInitialized(context, 1_000))
        assertEquals(2, attempts.get())
    }

    @Test
    fun `drain receives only the budget remaining after prerequisites`() = runBlocking {
        TokenStatsStartupCoordinator.initializationTimeoutMsForTest = 500L
        TokenStatsStartupCoordinator.ensureMigratedStep = {
            delay(100)
            true
        }
        TokenStatsStartupCoordinator.consumePendingRestoreStep = { true }
        var drainBudgetMs = 0L
        TokenStatsStartupCoordinator.initialDrainStep = { _, remainingMs ->
            drainBudgetMs = remainingMs
            true
        }

        assertTrue(TokenStatsStartupCoordinator.awaitInitialized(context, 1_000))
        assertTrue("drain budget must be positive", drainBudgetMs > 0L)
        assertTrue("prerequisite time must be deducted", drainBudgetMs < 500L)
    }
}

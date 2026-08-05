package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.stats.TokenStatsResetCoordinator
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 重置失败语义：旧 DataStore 计数清零后，新账本（事件 + baseline）清理失败时
 * 必须返回失败（false）并记录日志，不能假装成功；成功时返回 true。
 *
 * 隔离说明：模块级 `Context.apiDataStore` 委托在单个 JVM 内只创建一个
 * DataStore 实例（绑定首个访问它的 Context），后续测试共享同一文件，Windows
 * 上对同一文件重复写入会失败。因此每个测试在 [Before] 中通过反射清空该单例，
 * 使每次测试都绑定到自己的临时目录，每个测试最多一次真实写入。
 */
class ApiPreferencesResetFailureTest {

    @Before
    fun isolateDataStoreSingleton() {
        clearApiDataStoreSingleton()
    }

    private fun contextWithFiles(tempDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(tempDir)
        return context
    }

    private fun constructApiPreferences(context: Context): ApiPreferences {
        val constructor =
            ApiPreferences::class.java
                .getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }
        return constructor.newInstance(context)
    }

    /**
     * 清空 `Context.apiDataStore` 委托缓存的数据存储单例，使下一个测试能绑定
     * 到自己的临时目录。委托与单例字段均为 Kotlin 生成物，字段名随编译固定；
     * 若未来布局变化导致失败，测试类会直接报错而非静默跳过。
     */
    private fun clearApiDataStoreSingleton() {
        val facade = Class.forName("com.ai.assistance.operit.data.preferences.ApiPreferencesKt")
        val delegateField = facade.getDeclaredField("apiDataStore\$delegate")
        delegateField.isAccessible = true
        val delegate = delegateField.get(null)
        val instanceField =
            delegate.javaClass.getDeclaredField("INSTANCE").apply { isAccessible = true }
        instanceField.set(delegate, null)
    }

    @Test
    fun `reset all returns false when new ledger cleanup fails`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            runBlocking {
                val tempDir = kotlin.io.path.createTempDirectory("apiprefs-reset").toFile()
                val prefs = constructApiPreferences(contextWithFiles(tempDir))

                TokenStatsResetCoordinator.daoProvider =
                    { throw RuntimeException("db unavailable") }
                try {
                    assertFalse(prefs.resetAllProviderModelTokenCounts())
                } finally {
                    TokenStatsResetCoordinator.daoProvider = null
                }
            }
        }
    }

    @Test
    fun `reset model returns false when new ledger cleanup fails`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            runBlocking {
                val tempDir = kotlin.io.path.createTempDirectory("apiprefs-reset").toFile()
                val prefs = constructApiPreferences(contextWithFiles(tempDir))

                TokenStatsResetCoordinator.daoProvider =
                    { throw RuntimeException("db unavailable") }
                try {
                    assertFalse(prefs.resetProviderModelTokenCounts("DEEPSEEK:deepseek-chat"))
                } finally {
                    TokenStatsResetCoordinator.daoProvider = null
                }
            }
        }
    }

    @Test
    fun `reset all returns true and clears ledger when cleanup succeeds`() = runBlocking {
        val tempDir = kotlin.io.path.createTempDirectory("apiprefs-reset").toFile()
        val prefs = constructApiPreferences(contextWithFiles(tempDir))
        val dao = mock<TokenStatsDao>()

        TokenStatsResetCoordinator.daoProvider = { dao }
        try {
            assertTrue(prefs.resetAllProviderModelTokenCounts())
            verify(dao).deleteAllEvents()
            verify(dao).deleteAllBaselines()
            Unit
        } finally {
            TokenStatsResetCoordinator.daoProvider = null
        }
    }

    @Test
    fun `reset model returns true and clears ledger when cleanup succeeds`() = runBlocking {
        val tempDir = kotlin.io.path.createTempDirectory("apiprefs-reset").toFile()
        val prefs = constructApiPreferences(contextWithFiles(tempDir))
        val dao = mock<TokenStatsDao>()

        TokenStatsResetCoordinator.daoProvider = { dao }
        try {
            assertTrue(prefs.resetProviderModelTokenCounts("DEEPSEEK:deepseek-chat"))
            verify(dao).deleteEventsByProviderModel("DEEPSEEK", "deepseek-chat")
            verify(dao).deleteBaselinesByProviderModel("DEEPSEEK", "deepseek-chat")
            Unit
        } finally {
            TokenStatsResetCoordinator.daoProvider = null
        }
    }

    @Test
    fun `reset all propagates cancellation instead of swallowing it`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            runBlocking {
                val tempDir = kotlin.io.path.createTempDirectory("apiprefs-reset").toFile()
                val prefs = constructApiPreferences(contextWithFiles(tempDir))

                TokenStatsResetCoordinator.daoProvider =
                    { throw CancellationException("reset all cancelled") }
                try {
                    prefs.resetAllProviderModelTokenCounts()
                    fail("expected CancellationException to propagate")
                } catch (e: CancellationException) {
                    assertEquals("reset all cancelled", e.message)
                } finally {
                    TokenStatsResetCoordinator.daoProvider = null
                }
            }
        }
    }

    @Test
    fun `reset model propagates cancellation instead of swallowing it`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            runBlocking {
                val tempDir = kotlin.io.path.createTempDirectory("apiprefs-reset").toFile()
                val prefs = constructApiPreferences(contextWithFiles(tempDir))

                TokenStatsResetCoordinator.daoProvider =
                    { throw CancellationException("reset model cancelled") }
                try {
                    prefs.resetProviderModelTokenCounts("DEEPSEEK:deepseek-chat")
                    fail("expected CancellationException to propagate")
                } catch (e: CancellationException) {
                    assertEquals("reset model cancelled", e.message)
                } finally {
                    TokenStatsResetCoordinator.daoProvider = null
                }
            }
        }
    }
}

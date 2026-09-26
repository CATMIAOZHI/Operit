package com.ai.assistance.operit.util

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillZipPoolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun reuseAndByteBudget() = runBlocking {
        val dir = temporary.newFolder()
        val pool = SkillZipPool(dir, maxEntries = 6, maxBytes = 10)
        var downloads = 0
        suspend fun load(key: String) = pool.withZip(key, {
            downloads++
            it.writeText("123456")
            true
        }) { it.readText() }
        assertEquals("123456", load("a"))
        assertEquals("123456", load("a"))
        assertEquals(1, downloads)
        load("b")
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 10L)
    }

    @Test fun oversizedZipLivesUntilImportCompletes() = runBlocking {
        val dir = temporary.newFolder()
        val pool = SkillZipPool(dir, maxEntries = 1, maxBytes = 2)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val import = async {
            pool.withZip("large", { it.writeText("123456"); true }) { file ->
                entered.complete(Unit)
                release.await()
                assertEquals("123456", file.readText())
            }
        }
        entered.await()
        val other = async { pool.withZip("other", { it.writeText("x"); true }) { it.readText() } }
        assertTrue(dir.listFiles()!!.any { it.length() == 6L })
        release.complete(Unit)
        import.await()
        assertEquals("x", other.await())
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 2L)
    }

    @Test fun failedAndCancelledDownloadsLeaveNoPartialFiles() = runBlocking {
        val dir = temporary.newFolder()
        val pool = SkillZipPool(dir)
        assertNull(pool.withZip("failed", { it.writeText("partial"); false }) { fail() })
        try {
            pool.withZip("cancelled", { it.writeText("partial"); throw CancellationException() }) { fail() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun cleansOldDownloadsButDoesNotDeleteUnmanagedFiles() = runBlocking {
        val dir = temporary.newFolder()
        File(dir, "repo_0123456789abcdef.download").writeText("old")
        File(dir, "unrelated.download").writeText("keep")
        val pool = SkillZipPool(dir, maxEntries = 1)
        repeat(3) { index ->
            pool.withZip("$index", { it.writeText("$index"); true }) { assertTrue(it.exists()) }
        }
        assertEquals(1, dir.listFiles()!!.count { it.extension == "zip" })
        assertFalse(File(dir, "repo_0123456789abcdef.download").exists())
        assertTrue(File(dir, "unrelated.download").exists())
    }
}

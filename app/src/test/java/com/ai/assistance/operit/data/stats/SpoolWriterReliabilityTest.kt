package com.ai.assistance.operit.data.stats

import android.content.Context
import androidx.room.Room
import com.ai.assistance.operit.api.chat.llmprovider.TokenStatsPersistenceException
import com.ai.assistance.operit.api.chat.llmprovider.TokenTrackingAIService
import com.ai.assistance.operit.data.dao.TokenStatsDao
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.model.BillingMode
import com.ai.assistance.operit.ui.features.settings.screens.QuarantineExportCleanup
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** File + Room tests for the stage-2 durability linearization points. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class SpoolWriterReliabilityTest : TokenStatReliabilityTestBase() {
    @Test
    fun `price override read timeout is durable unknown not default`() = runBlocking {
        database.tokenStatsDao().upsertPriceOverride(
            scope = TokenPriceResolver.SCOPE_CONFIG,
            provider = "DEEPSEEK",
            model = "deepseek-chat",
            configId = "cfg",
            billingMode = BillingMode.TOKEN.name,
            pricingCurrency = "USD",
            inputPricePerMillion = 99.0,
            cachedInputPricePerMillion = 99.0,
            outputPricePerMillion = 99.0,
        )
        TokenStatsLedger.legacyPriceProvider = { _, _ -> delay(Long.MAX_VALUE); null }
        TokenStatSpool.prepareTimeoutMs = 50L

        TokenTrackingAIService.recordSafely(context, request("price-timeout"))
        awaitEvent("price-timeout")
        val event = database.tokenStatsDao().getEvent("price-timeout")!!
        assertEquals(PricingSource.UNKNOWN.name, event.pricingSource)
        assertNull(event.inputPricePerMillion)
        assertNull(event.costInPricingCurrency)
        assertTrue(event.diagnosticsJson!!.contains("pricing_read_timeout"))
    }

    @Test
    fun `more than two thousand append failures never return durable`() = runBlocking {
        File(root, TokenStatSpool.SPOOL_DIR_NAME).writeText("not a directory")
        Mockito.mockStatic(AppLogger::class.java).use {
            repeat(2_001) { index ->
                try {
                    TokenTrackingAIService.recordSafely(context, request("disk-failure-$index"))
                    fail("append failure must throw")
                } catch (_: TokenStatsPersistenceException) {
                }
            }
        }
        assertEquals(0, TokenStatSpool.emergencyQueueSizeForTest())
        assertEquals(0, database.tokenStatsDao().countEvents())
    }

    @Test
    fun `crash half line never splices the next healthy event`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            // 崩溃残留：active 尾部半行 JSON，无换行
            File(spool, "active.jsonl").writeText("{\"v\":2,\"eventId\":\"evt-crash-half\"")
            TokenTrackingAIService.recordSafely(context, request("evt-healthy-after-crash"))
            TokenStatSpool.replay(context)
            awaitEvent("evt-healthy-after-crash")
            // 健康事件恰好一次进入 Room，残缺证据完整保留在 quarantine
            assertEquals(1, database.tokenStatsDao().countEvents())
            assertEquals("evt-healthy-after-crash", database.tokenStatsDao().getAllEvents().single().eventId)
            val evidence = TokenStatSpool.quarantineEvidence(context)
            assertEquals(1, evidence.size)
            assertTrue("partial evidence must be preserved", evidence.single().readText().contains("evt-crash-half"))
        }
    }

    @Test
    fun `same-name same-size same-mtime replacement is never skipped isolated or acked away`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val healthyLine = line(request("replacement-healthy"))
                val healthyBytes = (healthyLine + "\n").toByteArray(Charsets.UTF_8).size
                // 旧损坏段与健康新段字节数完全一致（P1-1：仅凭 length+mtime 的缓存才会被骗）
                val oldBody = "{old-corrupt-" + "x".repeat(healthyBytes - "{old-corrupt-".length - 1) + "\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val fixedMtime = 1_700_000_000_000L

                val file = File(spool, "sealed_1.jsonl")
                file.writeText(oldBody)
                file.setLastModified(fixedMtime)
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(
                    "{\"ts\":1,\"file\":\"sealed_1.jsonl\",\"bytes\":$healthyBytes," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n",
                )
                // 先建立旧身份（旧实现中身份哈希缓存在此记住 length+mtime+sha）
                TokenStatSpool.quarantineEvidence(context)

                // 同名同长同 mtime 替换为不同内容（健康行）
                assertTrue(file.delete())
                file.writeText(healthyLine + "\n")
                file.setLastModified(fixedMtime)

                // ack：陈旧记录被移除，健康文件绝不删除
                TokenStatSpool.acknowledgeAndDeleteQuarantine(context, setOf("sealed_1.jsonl"))
                assertTrue("ack must never delete a replaced same-name healthy segment", file.exists())
                assertFalse(
                    "stale tombstone must be removed by ack",
                    manifest.isFile && manifest.readText().contains("sealed_1.jsonl"),
                )

                // 重建陈旧记录，让维护入口与扫描器都看到它
                manifest.writeText(
                    "{\"ts\":2,\"file\":\"sealed_1.jsonl\",\"bytes\":$healthyBytes," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n",
                )
                // replay：维护清理不删不隔离、扫描器不跳过，健康事件恰一次
                TokenStatSpool.replay(context)
                awaitEvent("replacement-healthy")
                awaitSegmentGone(spool, "sealed_1.jsonl")
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertEquals(
                    "replacement-healthy",
                    database.tokenStatsDao().getAllEvents().single().eventId,
                )
                assertFalse(
                    "stale tombstone must be removed after replay",
                    manifest.isFile && manifest.readText().contains("sealed_1.jsonl"),
                )
                assertTrue(
                    "healthy segment must never be isolated as evidence",
                    TokenStatSpool.quarantineEvidence(context).none { it.name == "sealed_1.jsonl" },
                )
            }
        }

    @Test
    fun `total spool cap stops appends while dao permanently fails and recovers after drain`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                // 行先于失败 DAO 生成（行生成需要真实价格读取），DAO 只负责排空失败
                val lines = (0 until 400).map { index ->
                    line(request("cap-$index")) to "cap-$index"
                }
                TokenStatSpool.MAX_SEGMENT_BYTES = 8L * 1024
                // 总 cap：约 3 个小段 + 行余量；DAO 永久失败 → sealed 段只增不减
                TokenStatSpool.totalSpoolMaxBytesForTest = 24L * 1024
                val failingDao = mock<TokenStatsDao>()
                whenever(failingDao.insertIdentityIfAbsent(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.upsertDisplayModel(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.insertEventIfNotResetCovered(any())).thenThrow(RuntimeException("dao down"))
                val proxy = mock<AppDatabase>()
                whenever(proxy.tokenStatsDao()).thenReturn(failingDao)
                TokenStatsLedger.databaseProvider = { proxy }
                try {
                    var rejected = 0
                    for ((text, eventId) in lines) {
                        try {
                            TokenStatSpool.append(context, text, eventId)
                        } catch (e: TokenStatsPersistenceException) {
                            rejected++
                        }
                    }
                    assertTrue("append must be refused once the total cap is reached: $rejected", rejected > 0)
                    val totalAtRejection = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    assertTrue(
                        "total spool bytes must never exceed the cap: $totalAtRejection",
                        totalAtRejection <= (TokenStatSpool.totalSpoolMaxBytesForTest ?: 0),
                    )
                    // 固定 cap 前停止：拒绝后不再发布任何新字节（seal 只是改名不增字节）
                    val frozen = spool.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    repeat(20) {
                        try {
                            TokenStatSpool.append(context, lines[0].first, "refused-$it")
                            fail("append after cap must keep failing")
                        } catch (e: TokenStatsPersistenceException) {
                        }
                    }
                    assertEquals(
                        "no new spool bytes may be published after the cap",
                        frozen,
                        spool.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // drain 成功（DAO 恢复）后空间释放，append 继续
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.replay(context)
                    awaitNoSealedSegments(spool)
                    TokenTrackingAIService.recordSafely(context, request("after-total-cap-recovery"))
                    awaitEvent("after-total-cap-recovery")
                    assertEquals(
                        "after-total-cap-recovery",
                        database.tokenStatsDao().getEvent("after-total-cap-recovery")!!.eventId,
                    )
                } finally {
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                }
            }
        }

    @Test
    fun `single legal line exactly at the total cap is accepted and the next is refused`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val previousCap = TokenStatSpool.totalSpoolMaxBytesForTest
                // cap 与单行合法上限一致：恰好一行可写入，第二行必须明确拒绝
                TokenStatSpool.totalSpoolMaxBytesForTest = TokenStatSpool.MAX_LINE_BYTES.toLong()
                // 行必须先于失败 DAO 生成（行生成需要真实价格读取）；排空失败段才留在
                // spool，第二次 append 才会在总容量检查处触顶
                val padded =
                    padLineTo(
                        line(request("single-line-at-cap")),
                        TokenStatSpool.MAX_LINE_BYTES,
                    )
                val secondLine = line(request("refused-after-single"))
                val failingDao = mock<TokenStatsDao>()
                whenever(failingDao.insertIdentityIfAbsent(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.upsertDisplayModel(any())).thenThrow(RuntimeException("dao down"))
                whenever(failingDao.insertEventIfNotResetCovered(any())).thenThrow(RuntimeException("dao down"))
                val proxy = mock<AppDatabase>()
                whenever(proxy.tokenStatsDao()).thenReturn(failingDao)
                TokenStatsLedger.databaseProvider = { proxy }
                try {
                    assertEquals(
                        TokenStatSpool.MAX_LINE_BYTES,
                        (padded + "\n").toByteArray(Charsets.UTF_8).size,
                    )
                    assertTrue(TokenStatSpool.append(context, padded, "single-line-at-cap"))
                    try {
                        TokenStatSpool.append(context, secondLine, "refused-after-single")
                        fail("append beyond the total cap must throw TokenStatsPersistenceException")
                    } catch (e: TokenStatsPersistenceException) {
                    }
                    // DAO 恢复后排空成功：单行事件恰一次，被拒绝的行从未发布
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.replay(context)
                    awaitEvent("single-line-at-cap")
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertEquals(
                        "single-line-at-cap",
                        database.tokenStatsDao().getAllEvents().single().eventId,
                    )
                } finally {
                    TokenStatsLedger.databaseProvider = { database }
                    TokenStatSpool.totalSpoolMaxBytesForTest = previousCap
                }
            }
        }

    @Test
    fun `drain stays bounded and healthy appends stay durable while enumeration fails then recovers`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                // 健康段：根枚举失败期间 drain fail-closed 退避——段保留、不入 Room
                File(spool, "sealed_1.jsonl").writeText(line(request("enum-drain-1")) + "\n")
                // 陈旧候选：消失原件身份仍在 manifest
                val oldBody = "{enum-drain-stale\n"
                val oldSha = sha256Hex(oldBody.toByteArray(Charsets.UTF_8))
                val manifestLine =
                    "{\"ts\":1,\"file\":\"sealed_2.jsonl\",\"bytes\":${oldBody.toByteArray(Charsets.UTF_8).size}," +
                        "\"sha256\":\"$oldSha\",\"overCap\":false}\n"
                val manifest = File(spool, "quarantine_skip_manifest.jsonl")
                manifest.writeText(manifestLine)
                // UNCOMMITTED trash 真实持有 sealed_2（valid mapping）
                val trash =
                    File(spool, "quarantine_ack_trash_${UUID.randomUUID().toString().replace("-", "")}")
                trash.mkdirs()
                File(trash, "sealed_2.jsonl").writeText(oldBody)
                File(trash, TokenStatSpool.ACK_TRASH_STATE_FILE_NAME).writeText(
                    TokenStatSpool.ACK_STATE_UNCOMMITTED + "\n" +
                        "{\"o\":\"sealed_2.jsonl\",\"t\":\"sealed_2.jsonl\",\"b\":${oldBody.toByteArray(Charsets.UTF_8).size},\"s\":\"$oldSha\"}\n",
                )
                TokenStatSpool.directoryListingForTest = { dir ->
                    if (dir == spool) null else dir.listFiles()
                }
                try {
                    TokenStatSpool.replay(context)
                    delay(700)
                    assertNull(
                        "no segment may drain while the root enumeration fails",
                        database.tokenStatsDao().getEvent("enum-drain-1"),
                    )
                    assertTrue("healthy segment must be preserved", File(spool, "sealed_1.jsonl").exists())
                    // 有界：trash 不处置、manifest 不重写
                    assertTrue(trash.exists())
                    assertEquals(manifestLine, safeManifestText(manifest))
                    // 健康 append 在枚举失败期间仍然 durable（事件留在 active，不排空）
                    assertTrue(
                        TokenStatSpool.append(
                            context,
                            line(request("enum-append-2")),
                            "enum-append-2",
                        ),
                    )
                    delay(700)
                    assertNull(
                        "appended event must stay durable but not drain while the root enumeration fails",
                        database.tokenStatsDao().getEvent("enum-append-2"),
                    )
                    assertTrue("appended event must stay in active.jsonl", File(spool, "active.jsonl").exists())
                    assertTrue(trash.exists())
                    assertEquals(manifestLine, safeManifestText(manifest))
                } finally {
                    TokenStatSpool.directoryListingForTest = null
                }
                // 枚举恢复后处理：健康段与 active 排空；stale 清理与回滚完成
                TokenStatSpool.replay(context)
                awaitEvent("enum-drain-1")
                awaitEvent("enum-append-2")
                awaitManifestWithout(spool, "sealed_2.jsonl")
                val restoreDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < restoreDeadline && trash.exists()) delay(20)
                assertFalse("trash must be rolled back after enumeration recovers", trash.exists())
                val evidenceDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var restored = false
                while (System.nanoTime() < evidenceDeadline && !restored) {
                    restored = TokenStatSpool.quarantineEvidence(context).any { it.readText() == oldBody }
                    if (!restored) delay(20)
                }
                assertTrue("held identity evidence must be restored after recovery", restored)
            }
        }

    @Test
    fun `mid-copy partial target is isolated never drained or overwritten and active is retained`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("partial-a"))
                val lineB = line(request("partial-b"))
                val partial = lineA + "\n" // copy 中途只写入了完整行的前缀内容
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    TokenStatSpool.sealCopyForTest = { _, target ->
                        target.writeText(partial)
                        false
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        // copy 中途失败留下部分目标：append 必须明确失败且 active 保留
                        assertFalse(
                            "append must fail when the seal copy fails mid-way",
                            TokenStatSpool.append(context, line(request("partial-c")), "partial-c"),
                        )
                    } finally {
                        TokenStatSpool.sealCopyForTest = null
                        TokenStatSpool.sealHardLinkForTest = null
                    }
                    assertEquals(
                        "active must be retained with the full content",
                        lineA + "\n" + lineB + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertFalse(
                        "partial target must not remain as a normal sealed segment",
                        spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                    )
                    val isolated = spool.listFiles().orEmpty().single { it.name.startsWith("seal_failed_") }
                    assertEquals("partial bytes must be preserved as isolated evidence", partial, isolated.readText())
                    // 恢复：隔离副本由维护清理；部分内容绝不入 Room（完整内容只排空一次）
                    TokenStatSpool.replay(context)
                    awaitEvent("partial-a")
                    awaitEvent("partial-b")
                    awaitSegmentGone(spool, isolated.name)
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.sealCopyForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `pending-delete reverse rename not durable keeps retryable record and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body = "{reverse-rename-bad\n"
                File(spool, "sealed_1.jsonl").writeText(body)
                File(spool, "sealed_2.jsonl").writeText(line(request("reverse-rename-healthy")) + "\n")
                // 阶段 1：证据区 rename 失败 → pending-delete 有界证据；健康段照常排空
                TokenStatSpool.segmentRenameForTest = { _, to ->
                    if (to.name.startsWith("quarantine_") && !to.name.startsWith("quarantine_pending_delete_")) {
                        false
                    } else {
                        null
                    }
                }
                TokenStatSpool.replay(context)
                awaitEvent("reverse-rename-healthy")
                val pending = spool.listFiles().orEmpty().single {
                    it.isFile && it.name.startsWith("quarantine_pending_delete_")
                }
                TokenStatSpool.segmentRenameForTest = null
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 阶段 2：维护恢复 rename 可见但目录项 sync 失败 → 尽力反向 rename 回
                // pending-delete 名（重建明确可重试记录）；反向 rename 的目录项同样必须严格
                // sync（P2 终审），未确认持久绝不视为已重建 → 本轮退避，记录保留
                var failSyncs = true
                TokenStatSpool.dirSyncForTest = {
                    if (failSyncs) TokenStatSpool.DirSyncResult.FAILED
                    else TokenStatSpool.DirSyncResult.OK
                }
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                delay(900)
                assertTrue(
                    "pending-delete record must be rebuilt when the restore rename is not durable",
                    pending.exists(),
                )
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, evidence.size)
                assertTrue(
                    "evidence must still be the pending-delete record",
                    evidence.single().name.startsWith("quarantine_pending_delete_"),
                )
                // 退避期间任何 append 都不发布新字节（gate 已失效，bootstrap 重新确认前拒绝）
                assertFalse(
                    TokenStatSpool.append(context, line(request("reverse-rename-blocked")), "reverse-rename-blocked"),
                )
                assertFalse(
                    "no event may be written while dir entries are unconfirmed",
                    File(spool, "active.jsonl").exists(),
                )
                assertEquals(1, database.tokenStatsDao().countEvents())
                // 阶段 3：恢复——记录移回完整证据区，事件仍恰一次，后续 append 正常
                failSyncs = false
                TokenStatSpool.shutdownWriterForTest()
                TokenStatSpool.replay(context)
                awaitNoPendingEvidence(spool)
                val restored = TokenStatSpool.quarantineEvidence(context)
                assertEquals(1, restored.size)
                assertTrue("full evidence must be restored exactly once", restored.single().readText() == body)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertTrue(TokenStatSpool.append(context, line(request("reverse-rename-post")), "reverse-rename-post"))
                TokenStatSpool.replay(context)
                awaitEvent("reverse-rename-post")
                assertEquals(2, database.tokenStatsDao().countEvents())
            }
        }

}

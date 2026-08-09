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
internal class SpoolRecoveryTest : TokenStatReliabilityTestBase() {
    @Test
    fun `spool root enumeration failure aborts snapshot and seal without touching segments then recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val body1 = line(request("enum-null-sealed")) + "\n"
                val body2 = line(request("enum-null-active")) + "\n"
                val sealed1 = File(spool, "sealed_1.jsonl")
                sealed1.writeText(body1)
                val active = File(spool, "active.jsonl")
                active.writeText(body2)
                val activeText = active.readText()
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                try {
                    // active 已有内容：下一次 append 必走 seal 路径（P1-7 场景）
                    TokenStatSpool.MAX_SEGMENT_BYTES = active.length() + 1
                    TokenStatSpool.directoryListingForTest = { dir ->
                        if (dir == spool) null else dir.listFiles()
                    }
                    try {
                        // 1) 快照的 drain 阶段 fail-closed → block 绝不执行、文件原字节不变
                        var blockRan = false
                        try {
                            TokenStatSpool.withExclusiveSnapshotAccess(context, drainBefore = true) {
                                blockRan = true
                            }
                            fail("snapshot must fail while the spool root enumeration fails")
                        } catch (e: IOException) {
                            assertTrue(
                                "failure must come from the snapshot drain barrier",
                                e.message!!.contains("drain"),
                            )
                        }
                        assertFalse("snapshot block must not run", blockRan)
                        assertEquals("sealed_1 must stay byte-identical", body1, sealed1.readText())
                        assertEquals("active must stay byte-identical", activeText, active.readText())

                        // 2) seal 绝不覆盖：需要 seal 的 append 明确失败，sealed_1/active 原样
                        assertFalse(
                            "append requiring a seal must fail while enumeration fails",
                            TokenStatSpool.append(
                                context,
                                line(request("enum-null-extra")),
                                "enum-null-extra",
                            ),
                        )
                        assertEquals("sealed_1 must never be overwritten", body1, sealed1.readText())
                        assertEquals("active must not be sealed or truncated", activeText, active.readText())

                        // 3) drain 保留：后台 drain 轮退避，文件与事件原样
                        TokenStatSpool.replay(context)
                        delay(700)
                        assertEquals("sealed_1 must be preserved by the failing drain", body1, sealed1.readText())
                        assertEquals("active must be preserved by the failing drain", activeText, active.readText())
                        assertNull(
                            "sealed event must not reach Room while enumeration fails",
                            database.tokenStatsDao().getEvent("enum-null-sealed"),
                        )
                        assertNull(
                            "active event must not reach Room while enumeration fails",
                            database.tokenStatsDao().getEvent("enum-null-active"),
                        )
                    } finally {
                        TokenStatSpool.directoryListingForTest = null
                    }
                    // 4) 恢复 seam → 两事件各恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("enum-null-sealed")
                    awaitEvent("enum-null-active")
                    assertEquals(
                        "each preserved event must be recorded exactly once",
                        2,
                        database.tokenStatsDao().countEvents(),
                    )
                } finally {
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `seal never overwrites an occupied target and picks the next safe index`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val bodyA = line(request("seal-collide-a")) + "\n"
                val bodyB = line(request("seal-collide-b")) + "\n"
                File(spool, "sealed_1.jsonl").writeText(bodyA)
                val sealed2 = File(spool, "sealed_2.jsonl")
                sealed2.writeText(bodyB)
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                try {
                    // active 已含一条完整行；枚举缝隐藏 sealed_2 → 计算出的 next=2 已被
                    // 占用，seal 必须跳到 3，绝不覆盖 sealed_2
                    val bodyC = line(request("seal-collide-c")) + "\n"
                    File(spool, "active.jsonl").writeText(bodyC)
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.directoryListingForTest = { dir ->
                        if (dir == spool) {
                            dir.listFiles()?.filter { it.name != "sealed_2.jsonl" }?.toTypedArray()
                        } else {
                            dir.listFiles()
                        }
                    }
                    try {
                        assertTrue(
                            "append must seal active to a free index and succeed",
                            TokenStatSpool.append(
                                context,
                                line(request("seal-collide-d")),
                                "seal-collide-d",
                            ),
                        )
                        // seam 仍生效：并发 drain 与 seal 都看不见 sealed_2 → 占用目标不可能被覆盖
                        assertEquals(
                            "occupied sealed target must never be overwritten",
                            bodyB,
                            sealed2.readText(),
                        )
                    } finally {
                        TokenStatSpool.directoryListingForTest = null
                    }
                    // 恢复枚举后全部事件各恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("seal-collide-a")
                    awaitEvent("seal-collide-b")
                    awaitEvent("seal-collide-c")
                    awaitEvent("seal-collide-d")
                    assertEquals(
                        "each event must be recorded exactly once",
                        4,
                        database.tokenStatsDao().countEvents(),
                    )
                } finally {
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `seal publish conflict keeps target bytes and seals active at a higher index`() = runBlocking {
        Mockito.mockStatic(AppLogger::class.java).use {
            val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
            val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
            val seamBody = "{pre-existing-conflict\n"
            try {
                // active 已含两条完整行；下一次 append 必触发 seal（候选编号 1）
                val lineA = line(request("seal-seam-a"))
                val lineB = line(request("seal-seam-b"))
                val lineC = line(request("seal-seam-c"))
                File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                var hooks = 0
                TokenStatSpool.beforeSealPublishForTest = { target ->
                    hooks += 1
                    if (hooks == 1) {
                        // 候选选定后、实际 publish 前出现同名不同内容的目标（模拟异常残留）
                        target.writeText(seamBody)
                        true
                    } else {
                        null
                    }
                }
                // 停掉后台 writer：seal 仍在 append 内同步完成，但断言阶段不会被并发 drain
                // 改写/隔离文件（确定性）
                TokenStatSpool.shutdownWriterForTest()
                try {
                    assertTrue(
                        "append must seal active to a free index and succeed",
                        TokenStatSpool.append(context, lineC, "seal-seam-c"),
                    )
                } finally {
                    TokenStatSpool.beforeSealPublishForTest = null
                }
                // 冲突目标原字节不变；active 数据安全落到更高编号 sealed_2；新事件在 active
                assertEquals(
                    "conflict target must keep its original bytes",
                    seamBody,
                    File(spool, "sealed_1.jsonl").readText(),
                )
                assertEquals(
                    "active data must be sealed to a higher index",
                    lineA + "\n" + lineB + "\n",
                    File(spool, "sealed_2.jsonl").readText(),
                )
                assertEquals(
                    "new event must be durable in active",
                    lineC + "\n",
                    File(spool, "active.jsonl").readText(),
                )
                // 全部事件各恰一次入 Room；冲突残留被隔离为完整证据、字节不变
                TokenStatSpool.replay(context)
                awaitEvent("seal-seam-a")
                awaitEvent("seal-seam-b")
                awaitEvent("seal-seam-c")
                awaitNoSealedSegments(spool)
                assertEquals(3, database.tokenStatsDao().countEvents())
                val evidence = TokenStatSpool.quarantineEvidence(context)
                assertTrue(
                    "conflict residue must be preserved byte-identical as evidence",
                    evidence.any { it.isFile && it.readText() == seamBody },
                )
            } finally {
                TokenStatSpool.beforeSealPublishForTest = null
                TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
            }
        }
    }

    @Test
    fun `hardlink seal crash window recovers before append and each event drains exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("hardlink-window-a"))
                val lineB = line(request("hardlink-window-b"))
                val active = File(spool, "active.jsonl")
                active.writeText(lineA + "\n")
                // 模拟崩溃窗口：createLink(sealed_1, active) 成功但 active 删除前崩溃 → 同 inode
                Files.createLink(File(spool, "sealed_1.jsonl").toPath(), active.toPath())
                // 停掉后台 writer：恢复发生在 append 内同步完成，断言不被并发 drain 干扰
                TokenStatSpool.shutdownWriterForTest()
                // append 必须先恢复重复（删除 sealed 副本）再写入，绝不能把新内容写进已 seal 段
                assertTrue(TokenStatSpool.append(context, lineB, "hardlink-window-b"))
                assertFalse(
                    "sealed duplicate must be removed before append, never polluted",
                    File(spool, "sealed_1.jsonl").exists(),
                )
                TokenStatSpool.replay(context)
                awaitEvent("hardlink-window-a")
                awaitEvent("hardlink-window-b")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    "each event must be recorded exactly once",
                    setOf("hardlink-window-a", "hardlink-window-b"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            }
        }

    @Test
    fun `hardlink seal post-active-delete dir sync failure keeps durable link and drains once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("link-post-sync-a"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        // 前两次是 P1-1 bootstrap gate（filesDir + spool 目录）；第三次（链接
                        // 创建）成功，第四次（active 删除）失败
                        if (dirSyncCalls != 4) TokenStatSpool.DirSyncResult.OK else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the post-delete dir sync fails",
                            TokenStatSpool.append(context, line(request("link-post-sync-b")), "link-post-sync-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    assertEquals(4, dirSyncCalls)
                    assertTrue(
                        "durable link must be kept",
                        File(spool, "sealed_1.jsonl").exists(),
                    )
                    assertFalse("active must have been removed in-process", File(spool, "active.jsonl").exists())
                    // 恢复：link 是唯一内容持有者（同 inode），正常排空，事件恰一次入 Room
                    TokenStatSpool.replay(context)
                    awaitEvent("link-post-sync-a")
                    awaitNoSealedSegments(spool)
                    assertEquals(1, database.tokenStatsDao().countEvents())
                    assertTrue(TokenStatSpool.append(context, line(request("link-post-sync-c")), "link-post-sync-c"))
                    awaitEvent("link-post-sync-c")
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `hardlink seal dir sync UNSUPPORTED never deletes active and never publishes`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("unsupported-link-a"))
                val lineB = line(request("unsupported-link-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    // 平台明确不支持目录 fsync：发布路径必须 fail-closed（UNSUPPORTED ≠ 成功），
                    // 硬链接已建立但目录项未持久 → 回滚链接、保留唯一 fsynced active、绝不 PUBLISHED。
                    // 前两次 sync 是 P1-1 bootstrap gate（filesDir + spool 目录，已确认），
                    // 第三次是链接创建的目录项，第四次是回滚删除链接的目录项（P2 终审：
                    // 回滚删除同样是目录项变更，必须确认持久，非 OK 同时失效 gate）。
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.UNSUPPORTED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the platform does not support dir fsync",
                            TokenStatSpool.append(context, lineB, "unsupported-link-b"),
                        )
                    } finally {
                        TokenStatSpool.dirSyncForTest = null
                    }
                    assertEquals(4, dirSyncCalls)
                    assertEquals(
                        "active must be retained byte-identical",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertFalse(
                        "UNSUPPORTED must never publish a sealed segment",
                        spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `copy seal dir sync UNSUPPORTED never deletes active and never publishes`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("unsupported-copy-a"))
                val lineB = line(request("unsupported-copy-b"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    TokenStatSpool.sealHardLinkForTest = { _, _ -> false }
                    // copy 回退的目录同步同样 fail-closed：目标已处置、active 保留、绝不 PUBLISHED。
                    // 前两次 sync 是 P1-1 bootstrap gate（filesDir + spool 目录，已确认），
                    // 第三次是 copy 目标创建的目录项。
                    var dirSyncCalls = 0
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.UNSUPPORTED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail when the platform does not support dir fsync",
                            TokenStatSpool.append(context, lineB, "unsupported-copy-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）——
                        // 后续 quarantineEvidence 读取需要恢复 tombstone 写入残留的 `.new`
                        // sidecar（P2 受管证据），strict 读取要求目录 sync OK 才能返回。
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                        TokenStatSpool.sealHardLinkForTest = null
                    }
                    // 5 次目录 sync = bootstrap gate(2) + copy 目标创建(1) + 失败目标隔离
                    // rename(1) + tombstone 暂存(1，P2：隔离后目录项未确认 → 按候选 sealed
                    // 身份写 tombstone 受管证据，绝不只留日志)
                    assertEquals(5, dirSyncCalls)
                    assertEquals(
                        "active must be retained byte-identical",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertFalse(
                        "UNSUPPORTED must never publish a sealed segment",
                        spool.listFiles().orEmpty().any { it.isFile && it.name.startsWith("sealed_") },
                    )
                    // 部分目标被隔离为 seal_failed_*（受管失败发布证据，立即可见）
                    val isolated =
                        spool.listFiles().orEmpty().single { it.isFile && it.name.startsWith("seal_failed_") }
                    assertEquals("isolated target must keep the copied bytes", lineA + "\n", isolated.readText())
                    assertTrue(
                        "isolated target must be visible as quarantine evidence",
                        TokenStatSpool.quarantineEvidence(context).any { it.name == isolated.name },
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.sealHardLinkForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `unsupported dir sync fails closed never clears active and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("failclosed-a"))
                val lineB = line(request("failclosed-b"))
                val lineC = line(request("failclosed-c"))
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n" + lineB + "\n")
                    TokenStatSpool.MAX_SEGMENT_BYTES = File(spool, "active.jsonl").length() + 1
                    // 平台明确不支持目录 fsync：封段发布必须 fail-closed——绝不原地清空/删除
                    // 唯一 fsynced active，也绝不返回 durable
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        assertFalse(
                            "append must fail closed when the platform does not support dir fsync",
                            TokenStatSpool.append(context, lineC, "failclosed-c"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    // active 原字节保留；无 sealed/seal_failed 发布残留；无事件入 Room
                    assertEquals(
                        "active must be retained byte-identical",
                        lineA + "\n" + lineB + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertTrue(
                        "fail-closed mode must never publish sealed segments",
                        spool.listFiles().orEmpty().none { it.isFile && it.name.startsWith("sealed_") },
                    )
                    assertTrue(
                        "fail-closed mode must never create seal_failed targets",
                        spool.listFiles().orEmpty().none { it.isFile && it.name.startsWith("seal_failed_") },
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：目录 fsync 恢复 OK 后 append 成功，全部事件各恰一次入 Room
                    assertTrue(TokenStatSpool.append(context, lineC, "failclosed-c"))
                    TokenStatSpool.replay(context)
                    awaitEvent("failclosed-a")
                    awaitEvent("failclosed-b")
                    awaitEvent("failclosed-c")
                    awaitNoSealedSegments(spool)
                    assertEquals(3, database.tokenStatsDao().countEvents())
                    assertEquals(
                        "each event must be recorded exactly once",
                        setOf("failclosed-a", "failclosed-b", "failclosed-c"),
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `first spool directory creation with unsupported dir sync returns false and retries after recovery`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                assertFalse("spool must not pre-exist", spool.exists())
                TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                TokenStatSpool.shutdownWriterForTest()
                try {
                    assertFalse(
                        "append must not return durable when the first spool dir creation cannot be confirmed",
                        TokenStatSpool.append(context, line(request("first-dir-a")), "first-dir-a"),
                    )
                } finally {
                    // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                }
                // 已创建的目录可保留供重试，但从未声明 durable；active 尚未写入
                assertTrue("created spool dir may be retained for retry", spool.isDirectory)
                assertFalse("active must not be written before the dir entry is durable", File(spool, "active.jsonl").exists())
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复能力 OK：重试成功，事件恰一次入 Room
                assertTrue(
                    TokenStatSpool.append(context, line(request("first-dir-a")), "first-dir-a"),
                )
                TokenStatSpool.replay(context)
                awaitEvent("first-dir-a")
                awaitNoSealedSegments(spool)
                assertEquals(1, database.tokenStatsDao().countEvents())
                assertEquals(
                    setOf("first-dir-a"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            }
        }

    @Test
    fun `first active file creation with unsupported dir sync returns false retains line and recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                var dirSyncCalls = 0
                TokenStatSpool.dirSyncForTest = {
                    dirSyncCalls += 1
                    // 前两次是 P1-1 bootstrap gate（filesDir + spool 目录，已确认）；第三次
                    // （首建 active 的目录项）平台不支持——内容已写+fsync 但目录项未确认
                    if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                    else TokenStatSpool.DirSyncResult.UNSUPPORTED
                }
                TokenStatSpool.shutdownWriterForTest()
                val lineA = line(request("first-active-a"))
                try {
                    assertFalse(
                        "append must not return durable when the first active creation dir sync is unsupported",
                        TokenStatSpool.append(context, lineA, "first-active-a"),
                    )
                } finally {
                    // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                }
                // 写+fd.sync 已发生但目录项未确认：本次不 durable，源 line 内容保留在 active
                assertEquals(3, dirSyncCalls)
                assertEquals(
                    "source line must be retained on disk",
                    lineA + "\n",
                    File(spool, "active.jsonl").readText(),
                )
                assertEquals(0, database.tokenStatsDao().countEvents())
                // 恢复能力 OK：下一次 append 先经 bootstrap gate 重新确认目录项再写新事件，
                // 两者各恰一次
                assertTrue(
                    TokenStatSpool.append(context, line(request("first-active-b")), "first-active-b"),
                )
                TokenStatSpool.replay(context)
                awaitEvent("first-active-a")
                awaitEvent("first-active-b")
                awaitNoSealedSegments(spool)
                assertEquals(2, database.tokenStatsDao().countEvents())
                assertEquals(
                    setOf("first-active-a", "first-active-b"),
                    database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                )
            }
        }

    @Test
    fun `unsupported dir sync fails closed for every append until recovery then drains exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val count = 24
                try {
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                    TokenStatSpool.MAX_SEGMENT_BYTES = 700
                    TokenStatSpool.shutdownWriterForTest()
                    // 目录 fsync 不支持：首次 spool/active 创建无法确认目录项——每次 append
                    // 都 fail-closed（绝不返回 durable、绝不永久挂起、绝不清空已写入内容）
                    repeat(count) { index ->
                        assertFalse(
                            "append must fail closed under unsupported dir sync without stalling: $index",
                            TokenStatSpool.append(context, line(request("win-failclosed-$index")), "win-failclosed-$index"),
                        )
                    }
                    assertTrue("created spool dir may be retained for retry", spool.isDirectory)
                    assertFalse(
                        "active must not be written before any directory entry is durable",
                        File(spool, "active.jsonl").exists(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：能力恢复 OK 后重试/replay，全部事件各恰一次入 Room
                    // （P1 终审：Windows JVM 真实探测恒为 UNSUPPORTED，必须回到注入的 OK）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    repeat(count) { index ->
                        assertTrue(
                            TokenStatSpool.append(context, line(request("win-failclosed-$index")), "win-failclosed-$index"),
                        )
                    }
                    TokenStatSpool.replay(context)
                    repeat(count) { index -> awaitEvent("win-failclosed-$index") }
                    awaitNoSealedSegments(spool)
                    assertEquals(count, database.tokenStatsDao().countEvents())
                    assertEquals(
                        count,
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet().size,
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `corrupt active tail with unsupported dir sync fails closed retaining original bytes then recovers exactly once`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val previousSegmentBytes = TokenStatSpool.MAX_SEGMENT_BYTES
                val lineA = line(request("corrupt-failclosed-a"))
                val lineB = line(request("corrupt-failclosed-b"))
                // 崩溃残留：active 尾部半行 JSON，无换行
                val original = lineA + "\n" + "{\"v\":2,\"eventId\":\"corrupt-failclosed-tail\""
                try {
                    File(spool, "active.jsonl").writeText(original)
                    TokenStatSpool.MAX_SEGMENT_BYTES = original.length.toLong() + 1
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.UNSUPPORTED }
                    TokenStatSpool.shutdownWriterForTest()
                    try {
                        // 损坏尾行需要封段处置：目录项未确认持久前绝不隔离证据、绝不插入
                        // 健康行、绝不截断/清空 active（copy+file sync 之后必须 dir sync OK
                        // 才允许继续）
                        assertFalse(
                            "append must fail closed when sealing a corrupt tail needs dir fsync",
                            TokenStatSpool.append(context, lineB, "corrupt-failclosed-b"),
                        )
                    } finally {
                        // P1 终审：恢复平台正常态（Windows JVM 真实探测恒为 UNSUPPORTED）
                        TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    }
                    // active 原字节不动（含损坏尾行）；尚无证据被切走；无事件入 Room
                    assertEquals(
                        "active must retain the original bytes including the corrupt tail",
                        original,
                        File(spool, "active.jsonl").readText(),
                    )
                    assertTrue(
                        "no evidence may be cut before its directory entry is durable",
                        TokenStatSpool.quarantineEvidence(context).isEmpty(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 恢复：目录 fsync OK 后损坏尾行作为完整证据隔离（至少一个 durable 位置），
                    // 健康事件各恰一次入 Room
                    assertTrue(TokenStatSpool.append(context, lineB, "corrupt-failclosed-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("corrupt-failclosed-a")
                    awaitEvent("corrupt-failclosed-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    val evidence = TokenStatSpool.quarantineEvidence(context)
                    assertTrue(
                        "corrupt tail evidence must be preserved with the original bytes",
                        evidence.any { it.readText() == original },
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                    TokenStatSpool.MAX_SEGMENT_BYTES = previousSegmentBytes
                }
            }
        }

    @Test
    fun `bootstrap gate re-confirms unconfirmed spool dir entry after simulated restart and never merges events`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME)
                val lineA = line(request("bootstrap-dir-a"))
                val lineB = line(request("bootstrap-dir-b"))
                try {
                    // 上一进程：首次创建 spool 目录，父目录/新目录的目录项 sync 失败（磁盘
                    // 可见但未确认持久）——append 明确失败，active 未写入
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    TokenStatSpool.shutdownWriterForTest()
                    assertFalse(TokenStatSpool.append(context, lineA, "bootstrap-dir-a"))
                    assertTrue("created spool dir is visible on disk", spool.isDirectory)
                    assertFalse("no line may be written before dir entries are durable", File(spool, "active.jsonl").exists())
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 模拟进程重启：清空全部内存状态（含 bootstrap gate 标记），磁盘状态保留
                    TokenStatSpool.clearPendingStateForTest()
                    // 目录项仍无法确认：本次 append 必须失败，绝不写新行（第二事件此前从未写入）
                    assertFalse(TokenStatSpool.append(context, lineB, "bootstrap-dir-b"))
                    assertFalse(
                        "no line may be written while the spool dir entry is unconfirmed",
                        File(spool, "active.jsonl").exists(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 恢复：bootstrap gate 重新确认目录项后，两个事件各恰一次入 Room
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    assertTrue(TokenStatSpool.append(context, lineA, "bootstrap-dir-a"))
                    assertTrue(TokenStatSpool.append(context, lineB, "bootstrap-dir-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("bootstrap-dir-a")
                    awaitEvent("bootstrap-dir-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    assertEquals(
                        setOf("bootstrap-dir-a", "bootstrap-dir-b"),
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    @Test
    fun `bootstrap gate re-confirms unconfirmed active entry after simulated restart and keeps bytes until confirmed`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("bootstrap-active-a"))
                val lineB = line(request("bootstrap-active-b"))
                var dirSyncCalls = 0
                try {
                    // 上一进程：bootstrap gate 两次确认通过，但首建 active 的目录项 sync 失败
                    // （内容已写+fsync、磁盘可见、未确认）
                    TokenStatSpool.dirSyncForTest = {
                        dirSyncCalls += 1
                        if (dirSyncCalls <= 2) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    assertFalse(TokenStatSpool.append(context, lineA, "bootstrap-active-a"))
                    assertEquals(3, dirSyncCalls)
                    assertEquals(
                        "unconfirmed line must stay visible on disk",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 模拟进程重启：清空全部内存状态（含 bootstrap gate 标记），active 字节保留
                    TokenStatSpool.clearPendingStateForTest()
                    // 目录项仍无法确认：本次 append 失败且 active 字节不变（绝不再追加新行）
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    assertFalse(TokenStatSpool.append(context, lineB, "bootstrap-active-b"))
                    assertEquals(
                        "active bytes must be unchanged while the dir entry is unconfirmed",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())

                    // 恢复：gate 重新确认后追加第二事件（此前从未写入），两事件各恰一次
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    assertTrue(TokenStatSpool.append(context, lineB, "bootstrap-active-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("bootstrap-active-a")
                    awaitEvent("bootstrap-active-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                    assertEquals(
                        setOf("bootstrap-active-a", "bootstrap-active-b"),
                        database.tokenStatsDao().getAllEvents().map { it.eventId }.toSet(),
                    )
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    @Test
    fun `maintenance seal dir sync failure after gate true forces the next append to re-bootstrap before writing`() =
        runBlocking {
            Mockito.mockStatic(AppLogger::class.java).use {
                val spool = File(root, TokenStatSpool.SPOOL_DIR_NAME).apply { mkdirs() }
                val lineA = line(request("gate-maint-a"))
                val lineB = line(request("gate-maint-b"))
                var calls = 0
                try {
                    File(spool, "active.jsonl").writeText(lineA + "\n")
                    // 阶段 1：bootstrap gate 两次确认 OK（gate=true），随后维护 drain 的封段
                    // 发布目录项 sync（第 3 次）失败 → 维护失败；gate 必须同步失效（P1-1）
                    TokenStatSpool.dirSyncForTest = {
                        calls += 1
                        if (calls != 3) TokenStatSpool.DirSyncResult.OK
                        else TokenStatSpool.DirSyncResult.FAILED
                    }
                    TokenStatSpool.shutdownWriterForTest()
                    TokenStatSpool.replay(context)
                    val sealDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (calls < 3 && System.nanoTime() < sealDeadline) delay(10)
                    TokenStatSpool.shutdownWriterForTest()
                    assertTrue("seal must have been attempted", calls >= 3)
                    // 阶段 2：gate 已失效且目录 sync 持续失败——下一次 append 必须重新
                    // bootstrap；bootstrap 失败 → append 明确失败且 active 字节不变
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.FAILED }
                    assertFalse(TokenStatSpool.append(context, lineB, "gate-maint-b"))
                    assertEquals(
                        "active must stay byte-identical",
                        lineA + "\n",
                        File(spool, "active.jsonl").readText(),
                    )
                    assertEquals(0, database.tokenStatsDao().countEvents())
                    // 阶段 3：恢复——bootstrap 重新确认后 append 成功，事件各恰一次
                    TokenStatSpool.dirSyncForTest = { TokenStatSpool.DirSyncResult.OK }
                    assertTrue(TokenStatSpool.append(context, lineB, "gate-maint-b"))
                    TokenStatSpool.replay(context)
                    awaitEvent("gate-maint-a")
                    awaitEvent("gate-maint-b")
                    awaitNoSealedSegments(spool)
                    assertEquals(2, database.tokenStatsDao().countEvents())
                } finally {
                    TokenStatSpool.dirSyncForTest = null
                }
            }
        }

    /**
     * P1-2/P1-3 终审通用 runner：以 [result]（FAILED/UNSUPPORTED）运行一次完整场景，保证
     * 前后内存/磁盘/数据库状态隔离（spool 目录重建 + 内存标记复位 + 事件表清空），并在
     * finally 还原全部注入缝。场景开始前恢复“目录 fsync 支持且成功”的平台常态
     * （Windows JVM 真实探测恒为 UNSUPPORTED），使场景内部的 phase-1 正常协议可用。
     */
}

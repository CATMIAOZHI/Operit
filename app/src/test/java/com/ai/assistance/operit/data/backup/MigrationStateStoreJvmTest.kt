package com.ai.assistance.operit.data.backup

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * MigrationStateStore 状态机测试（纯 JVM，真实文件后端）。
 *
 * 生产实现用 android.util.AtomicFile（JVM 不可用），因此注入 [PlainFileStateIo]
 * 作为真实文件 IO 边界：状态序列化/解析、RESTORE_REPLACED + finalState、
 * NEEDS_RECOVERY 的 fail-closed 语义全部按生产逻辑真实执行。
 */
class MigrationStateStoreJvmTest {

    private lateinit var tempDir: File
    private lateinit var context: Context

    @Before
    fun installRealFileIo() {
        tempDir = kotlin.io.path.createTempDirectory("migration-state").toFile()
        context = mockContext(tempDir)
        MigrationStateStore.fileIoProvider = { PlainFileStateIo(File(it.noBackupFilesDir, "official_operit_migration_state.txt")) }
    }

    @After
    fun clearSeam() {
        MigrationStateStore.fileIoProvider = null
    }

    private fun mockContext(tempDir: File): Context {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.noBackupFilesDir).thenReturn(File(tempDir, "no_backup"))
        return context
    }

    private fun stateFile(): File = File(File(tempDir, "no_backup"), "official_operit_migration_state.txt")

    @Test
    fun `missing state file reads as IDLE`() {
        assertEquals(MigrationStateStore.State.IDLE, MigrationStateStore.read(context).state)
    }

    @Test
    fun `write and read roundtrip keeps state uri safety path and finalState`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            assertTrue(MigrationStateStore.write(context, MigrationStateStore.State.REPLACING))
            var snapshot = MigrationStateStore.read(context)
            assertEquals(MigrationStateStore.State.REPLACING, snapshot.state)
            assertNull(snapshot.finalState)

            assertTrue(
                MigrationStateStore.write(
                    context,
                    MigrationStateStore.State.RESTORE_REPLACED,
                    finalState = MigrationStateStore.State.COMPLETED
                )
            )
            snapshot = MigrationStateStore.read(context)
            assertEquals(MigrationStateStore.State.RESTORE_REPLACED, snapshot.state)
            assertEquals(MigrationStateStore.State.COMPLETED, snapshot.finalState)

            // finalState 只在 RESTORE_REPLACED 下有意义；落回 COMPLETED 后不再携带
            assertTrue(MigrationStateStore.write(context, MigrationStateStore.State.COMPLETED))
            assertEquals(MigrationStateStore.State.COMPLETED, MigrationStateStore.read(context).state)
            assertNull(MigrationStateStore.read(context).finalState)
        }
    }

    @Test
    fun `write rejects invalid finalState combinations`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            try {
                MigrationStateStore.write(
                    context,
                    MigrationStateStore.State.IDLE,
                    finalState = MigrationStateStore.State.COMPLETED
                )
                fail("finalState outside RESTORE_REPLACED must be rejected")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("RESTORE_REPLACED"))
            }
            try {
                MigrationStateStore.write(
                    context,
                    MigrationStateStore.State.RESTORE_REPLACED,
                    finalState = MigrationStateStore.State.REPLACING
                )
                fail("finalState must be IDLE or COMPLETED")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("IDLE or COMPLETED"))
            }
            try {
                MigrationStateStore.write(context, MigrationStateStore.State.NEEDS_RECOVERY)
                fail("virtual state must not be persisted")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("NEEDS_RECOVERY"))
            }
        }
    }

    @Test
    fun `unknown state name fails closed to NEEDS_RECOVERY`() {
        stateFile().parentFile.mkdirs()
        stateFile().writeText("FROM_THE_FUTURE\n\n\n")
        Mockito.mockStatic(AppLogger::class.java).use {
            assertEquals(MigrationStateStore.State.NEEDS_RECOVERY, MigrationStateStore.read(context).state)
        }
    }

    @Test
    fun `corrupt state file fails closed to NEEDS_RECOVERY`() {
        stateFile().parentFile.mkdirs()
        stateFile().writeText("\u0000\u0001binary garbage")
        Mockito.mockStatic(AppLogger::class.java).use {
            assertEquals(MigrationStateStore.State.NEEDS_RECOVERY, MigrationStateStore.read(context).state)
        }
    }

    @Test
    fun `unknown finalState line is tolerated as null`() {
        Mockito.mockStatic(AppLogger::class.java).use {
            MigrationStateStore.write(context, MigrationStateStore.State.RESTORE_REPLACED)
        }
        // 人为追加未知第 4 行：读取容忍，finalState 回退为 null（登记时落回 IDLE）
        stateFile().writeText("RESTORE_REPLACED\n\n\nFROM_THE_FUTURE\n")
        val snapshot = MigrationStateStore.read(context)
        assertEquals(MigrationStateStore.State.RESTORE_REPLACED, snapshot.state)
        assertNull(snapshot.finalState)
    }

    @Test
    fun `write failure returns false instead of throwing`() {
        // 用抛异常的 IO 实现验证 write 的 catch 语义：失败返回 false 而非抛异常
        MigrationStateStore.fileIoProvider = {
            object : MigrationStateFileIo {
                override fun read(): String? = null
                override fun write(payload: String) {
                    throw java.io.IOException("disk full")
                }
            }
        }
        Mockito.mockStatic(AppLogger::class.java).use {
            assertEquals(false, MigrationStateStore.write(context, MigrationStateStore.State.IDLE))
        }
    }
}

/** JVM 测试用的真实文件实现（原子写：同目录临时文件 + 改名）。 */
internal class PlainFileStateIo(private val file: File) : MigrationStateFileIo {
    override fun read(): String? = if (file.isFile) file.readText() else null

    override fun write(payload: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}

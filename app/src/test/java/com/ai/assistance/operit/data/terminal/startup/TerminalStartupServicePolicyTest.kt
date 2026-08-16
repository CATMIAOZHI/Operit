package com.ai.assistance.operit.data.terminal.startup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalStartupServicePolicyTest {
    @Test
    fun `startup logs stop reaching a detached UI listener`() {
        val received = mutableListOf<String>()
        val forwarder = DetachableLogForwarder(received::add)

        forwarder.emit("before detach")
        forwarder.detach()
        forwarder.emit("after detach")

        assertEquals(listOf("before detach"), received)
    }

    @Test
    fun `executable startup configuration lives under no-backup storage`() {
        val noBackupRoot = File("private-no-backup")

        assertEquals(
            File(noBackupRoot, "operit/terminal-startup-services"),
            terminalStartupServiceDirectory(noBackupRoot),
        )
    }

    @Test
    fun `completion snapshots are not appended as incremental service logs`() {
        assertEquals("line", incrementalStartupLogChunk("line", isCompleted = false))
        assertEquals(null, incrementalStartupLogChunk("line", isCompleted = true))
        assertEquals(null, incrementalStartupLogChunk("  ", isCompleted = false))
    }
}

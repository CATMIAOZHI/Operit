package com.ai.assistance.operit.util

import com.ai.assistance.operit.api.chat.enhance.ConversationRoundManager
import com.ai.assistance.operit.util.stream.MutableSharedStreamImpl
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class MemoryDiagnosticCountersTest {
    @Test fun replayCounterReadsNumbersWithoutMaterializingPayloads() {
        val stream = MutableSharedStreamImpl<Any>(replay = Int.MAX_VALUE)
        val payload = object { override fun toString(): String = error("must not inspect content") }
        repeat(1000) { stream.tryEmit(payload) }
        assertEquals(1000L, stream.memoryCounters().replayEvents)
        assertEquals(0L, stream.memoryCounters().maxBacklog)
        stream.resetReplayCache()
        assertEquals(0L, stream.memoryCounters().replayEvents)
    }

    @Test fun displayCounterTracksReplacementAndClearing() {
        Mockito.mockStatic(AppLogger::class.java).use {
            val rounds = ConversationRoundManager()
            rounds.appendChunk("first")
            rounds.startNewRound()
            rounds.appendChunk("second")
            assertEquals(11L, rounds.memoryCounters().displayChars)
            rounds.updateContent("end")
            assertEquals(8L, rounds.memoryCounters().displayChars)
            rounds.clearContent()
            assertEquals(0L, rounds.memoryCounters().displayChars)
        }
    }
}

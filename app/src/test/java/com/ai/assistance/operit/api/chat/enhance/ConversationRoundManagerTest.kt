package com.ai.assistance.operit.api.chat.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The displayed turn is assembled from its rounds on every tool batch, so the buffer it is assembled
 * into is sized from those rounds instead of growing into them. The assembled text has to stay the
 * same however the rounds were filled, including when the result is large enough for the size to
 * matter.
 */
class ConversationRoundManagerTest {

    @Test
    fun roundsAreJoinedOldestFirstWithASeparatorBetweenThem() {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val manager = ConversationRoundManager()
            manager.appendChunk("first round")
            assertEquals("\n", manager.startNewRound())
            manager.appendChunk("second round")
            assertEquals("first round\nsecond round", manager.getDisplayContent())
        }
    }

    @Test
    fun aRoundThatIsStillEmptyDoesNotAddASeparatorOfItsOwn() {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val manager = ConversationRoundManager()
            manager.appendChunk("first round")
            assertEquals("\n", manager.startNewRound())
            assertEquals("first round\n", manager.getDisplayContent())
            assertEquals("", manager.getCurrentRoundContent())
        }
    }

    @Test
    fun updatingAndClearingReplaceWhatWasThereBefore() {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val manager = ConversationRoundManager()
            manager.appendChunk("streamed ")
            manager.appendChunk("chunks")
            assertEquals("streamed chunks", manager.getDisplayContent())
            assertEquals("replacement", manager.updateContent("replacement"))
            manager.initializeNewConversation()
            assertEquals("", manager.getDisplayContent())
        }
    }

    @Test
    fun aTurnThatGrewToMegabytesIsAssembledWhole() {
        org.mockito.Mockito.mockStatic(com.ai.assistance.operit.util.AppLogger::class.java).use {
            val manager = ConversationRoundManager()
            val expected = StringBuilder()
            repeat(80) { round ->
                if (round > 0) {
                    manager.startNewRound()
                    expected.append('\n')
                }
                repeat(500) { chunk ->
                    val text = "round $round chunk $chunk\n"
                    manager.appendChunk(text)
                    expected.append(text)
                }
            }
            val displayed = manager.getDisplayContent()
            assertTrue("turn is long enough to matter", displayed.length > 500_000)
            assertEquals(expected.toString(), displayed)
        }
    }
}

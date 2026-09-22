package com.ai.assistance.operit.api.chat.library

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatRecallPart
import com.ai.assistance.operit.data.preferences.MemoryReviewChange
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class MemoryLearningBatchTest {
    @get:Rule val folder=TemporaryFolder()
    private fun context(): Context=mock<Context>().also {
        whenever(it.filesDir).thenReturn(folder.root)
        whenever(it.cacheDir).thenReturn(folder.newFolder())
        whenever(it.applicationContext).thenReturn(it)
        val prefs=mock<SharedPreferences>()
        whenever(it.getSharedPreferences(any(),any())).thenReturn(prefs)
        whenever(prefs.getBoolean(any(),any())).thenReturn(false)
    }
    @Test fun journalPersistsSeparateCoverageAndReplaysProposalIdsOnce() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,true,10)
        val change=MemoryReviewChange("stable-id","notes","memory.md","proposal",sourceChatId="chat")
        journal.complete(listOf("notes"),LearningCursor(7),true,listOf(change))
        // Simulate process death after durable completion, before review history export.
        val restored=MemoryLearningJournal(context,"space","chat")
        assertEquals(7,restored.cursor("notes").messageId)
        assertEquals(0,restored.cursor("skills").messageId)
        restored.export()
        val repo=MemoryReviewRepository(context,"space")
        assertEquals(listOf("stable-id"),repo.list().map { it.id })
        // Replaying the same completed journal does not duplicate a proposal.
        journal.export()
        assertEquals(1,repo.list().size)
    }
    @Test fun cancelledStagingDoesNotCreateReviewHistory() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val change=repo.propose(MemoryReviewChange("","notes","memory.md","proposal"))
        assertEquals("staged",repo.applyAutomaticDecision(context,change).status)
        assertTrue(MemoryReviewRepository(context,"space").list().isEmpty())
        assertEquals(1,staged.size)
    }
    @Test fun continuousBatchesCoverLongUnicodeMessageWithoutOmittingItsTail() = runBlocking {
        val context=context()
        val dao=mock<ChatContentDao>()
        val source="🙂中文".repeat(15000)+"最后的验证结果"
        val bytes=source.toByteArray()
        whenever(dao.nextLearningSource(eq("chat"),any(),eq(1L))).thenAnswer {
            if (it.getArgument<Long>(1)<=1) ChatRecallPart(1,"chat","ai",1,"",0) else null
        }
        whenever(dao.readLearningMessageBytes(eq(1L),any())).thenAnswer {
            val offset=it.getArgument<Long>(1).toInt()
            bytes.copyOfRange(offset,minOf(offset+32768,bytes.size))
        }
        val rebuilt=StringBuilder()
        MemoryLearningSource(context,dao,"chat",1,true).use { reader ->
            var cursor=LearningCursor()
            do {
                val batch=reader.next(cursor,2000)
                assertTrue(batch.text.toByteArray().size<=2000)
                rebuilt.append(batch.text.substringAfter("]\n").removeSuffix("\n"))
                cursor=batch.next
            } while (batch.more)
        }
        assertEquals(source,rebuilt.toString())
    }
}

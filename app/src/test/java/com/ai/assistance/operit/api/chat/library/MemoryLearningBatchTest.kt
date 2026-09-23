package com.ai.assistance.operit.api.chat.library

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.data.dao.ChatContentDao
import com.ai.assistance.operit.data.dao.ChatRecallPart
import com.ai.assistance.operit.data.preferences.LearnedSkillRepository
import com.ai.assistance.operit.data.preferences.MemoryNotesRepository
import com.ai.assistance.operit.data.preferences.MemoryReviewChange
import com.ai.assistance.operit.data.preferences.MemoryReviewRepository
import com.ai.assistance.operit.data.preferences.SkillDraft
import java.util.Locale
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
        val editor=mock<SharedPreferences.Editor>()
        whenever(it.getSharedPreferences(any(),any())).thenReturn(prefs)
        whenever(prefs.getBoolean(any(),any())).thenReturn(false)
        whenever(prefs.edit()).thenReturn(editor)
        whenever(editor.putString(any(),any())).thenReturn(editor)
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
    @Test fun stagedBatchKeepsOnlyTheLastChangeForATarget() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val first=repo.propose(MemoryReviewChange("","notes","memory.md","first",
            before="disk",baseVersion="v1",addition="first"))
        val second=repo.propose(MemoryReviewChange("","notes","memory.md","second",
            before="disk2",baseVersion="v2",addition="second"))
        assertEquals(1,staged.size)
        assertEquals(first.id,second.id)
        assertEquals("second",staged.single().body)
        // The replacement already carries the whole text, so the accumulated addition is dropped.
        assertEquals("",staged.single().addition)
        // The batch's first baseline wins, so the applied change still matches the disk it read.
        assertEquals("disk",staged.single().before)
        assertEquals("v1",staged.single().baseVersion)
    }
    @Test fun replacedStagedNotesLandOnceWithoutRepeatingTheEarlierAddition() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        repo.proposeNotes(notes.load(),editText(notes.load().markdown,"add","first note",""),"first note")
        val replaced=repo.proposeNotes(notes.load(),
            editText(staged.single().body,"add","second note",""),"second note")
        assertEquals(1,staged.size)
        assertEquals("",staged.single().addition)
        val live=MemoryReviewRepository(context,"space")
        live.importCompletedBatch(staged)
        live.decide(context,replaced.id,true,"user","Reviewed")
        // The final text lands exactly once: no duplicate of the first addition and no extra append.
        assertEquals("first note\n\nsecond note",notes.load().markdown)
    }
    @Test fun stagedBatchKeepsDistinctTargetsSeparate() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val existing=LearnedSkillRepository.Snapshot("old","v1",true)
        repo.proposeSkillFile("demo-skill","SKILL.md",existing,"rewritten skill")
        repo.proposeSkillFile("demo-skill","references/notes.md",existing,"reference notes")
        repo.proposeSkillFile("other-skill","SKILL.md",existing,"another skill")
        assertEquals(3,staged.size)
        assertEquals(1,staged.count { it.title=="demo-skill" && it.path=="SKILL.md" })
        assertEquals(1,staged.count { it.title=="demo-skill" && it.path=="references/notes.md" })
    }
    @Test fun stagedSkillCannotSwitchKindMidBatchButCanBeResubmitted() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val body="A repeatable procedure with prerequisites, steps and checks.".repeat(3)
        val created=repo.proposeSkill(SkillDraft("","demo-skill","Demo skill",body,"chat",1L))
        try {
            repo.proposeSkillFile("demo-skill","SKILL.md",LearnedSkillRepository.Snapshot("","v0",false),"rewrite")
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("demo-skill"))
        }
        val again=repo.proposeSkill(SkillDraft("","demo-skill","Demo skill v2",body+"\n- extra step","chat",2L))
        assertEquals(1,staged.size)
        assertEquals(created.id,again.id)
        assertEquals("Demo skill v2",staged.single().description)
        assertTrue(staged.single().body.endsWith("- extra step"))
    }
    @Test fun stagedRevisionReadsBackAndPatchesTheStagedText() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        val repo=MemoryReviewRepository(context,"space",staged)
        val disk=LearnedSkillRepository.Snapshot("# Skill\n\nalpha beta","v1",true)
        repo.proposeSkillFile("demo-skill","SKILL.md",disk,
            editText(disk.text,"replace","alpha gamma","alpha beta"))
        // The batch reads its own version back, not the untouched file, and patches that version.
        assertEquals("# Skill\n\nalpha gamma",stagedSkillFile(staged,"demo-skill","SKILL.md")!!.body)
        repo.proposeSkillFile("demo-skill","SKILL.md",LearnedSkillRepository.Snapshot("# Skill\n\nalpha beta","v2",true),
            editText(staged.single().body,"replace","done","gamma"))
        assertEquals(1,staged.size)
        assertEquals("# Skill\n\nalpha done",staged.single().body)
        // The staged file is still measured against the baseline the batch first read.
        assertEquals("v1",staged.single().baseVersion)
    }
    @Test fun skillFileActionsRejectABatchThatOnlyDraftedTheSkill() = runBlocking {
        val context=context()
        val staged=mutableListOf<MemoryReviewChange>()
        staged.add(MemoryReviewChange("draft","skill","demo-skill","draft body",description="Demo skill"))
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=false,skillsEnabled=true,
            background=true,stagedChanges=staged)
        // A skill that only exists as a staged draft is not installed, so its files cannot be written,
        // read from disk or deleted; the draft itself is resubmitted whole instead.
        for(action in listOf("skill_write","skill_patch","skill_remove_file","skill_delete")) {
            try {
                actions.execute(action,mapOf("name" to "demo-skill","content" to "rewrite",
                    "old_text" to "draft","path" to "SKILL.md","version" to "v1"))
                fail("$action should be rejected while the skill is only a draft")
            } catch (e: IllegalStateException) {
                assertTrue("$action: ${e.message}",e.message.orEmpty().contains("not installed yet"))
            }
        }
        // The draft itself is readable, but it has no companion files to read.
        val draft=actions.execute("skill_read",mapOf("name" to "demo-skill"))
        assertEquals("draft body",draft.getString("content"))
        assertTrue(draft.getBoolean("staged"))
        try {
            actions.execute("skill_read",mapOf("name" to "demo-skill","path" to "references/x.md"))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("not installed yet"))
        }
        assertEquals(1,staged.size)
    }
    @Test fun deletingAConversationDropsItsPendingProgressOnly() = runBlocking {
        val context=context()
        MemoryLearningJournal(context,"space","chat-gone").enqueue(true,true,10)
        MemoryLearningJournal(context,"space","chat-kept").enqueue(true,true,10)
        MemoryLearningJournal(context,"other","chat-gone").enqueue(true,false,10)
        // A deleted conversation can never be reviewed again, so its progress must not survive to be
        // retried on the next launch, while other conversations and spaces stay untouched.
        MemoryLearningJournal.deleteChat(context,"chat-gone")
        assertEquals(setOf("space" to "chat-kept"),MemoryLearningJournal.pending(context).toSet())
    }
    @Test fun abandoningARangeStopsRetriesAndKeepsTheCursor() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,false,10)
        journal.complete(listOf("notes"),LearningCursor(7),true,emptyList())
        journal.export()
        journal.abandon(listOf("notes"))
        // Cleared so nothing reschedules it, and the cursor stays put so a later trigger re-reads it.
        val restored=MemoryLearningJournal(context,"space","chat")
        assertFalse(restored.pending("notes"))
        assertEquals(7,restored.cursor("notes").messageId)
        assertTrue(MemoryLearningJournal.pending(context).isEmpty())
    }
    @Test fun abandoningAFinishedBatchDoesNotStrandItsExportBarrier() = runBlocking {
        val context=context()
        val journal=MemoryLearningJournal(context,"space","chat")
        journal.enqueue(true,false,10)
        journal.complete(listOf("notes"),LearningCursor(7),true,emptyList())
        journal.abandon(listOf("notes"))
        assertEquals(7,journal.cursor("notes").messageId)
        assertEquals(0,journal.export().size)
        // The range is abandoned, but the next complete/export cycle must still work normally.
        journal.complete(listOf("notes"),LearningCursor(9),false,emptyList())
        journal.export()
        val restored=MemoryLearningJournal(context,"space","chat")
        assertFalse(restored.pending("notes"))
        assertEquals(9,restored.cursor("notes").messageId)
    }
    @Test fun notesOverCapacityTellTheReviewerToFreeSpaceFirst() = runBlocking {
        val context=context()
        // A document one add away from its cap: the capacity is checked on the staged result, so an
        // add-first attempt is rejected and the message has to say what to do instead.
        val lines=(1..272).map { "note-%03d-%s".format(Locale.ROOT,it,"a".repeat(12)) }
        val notes=MemoryNotesRepository(context,"space")
        notes.save(lines.joinToString("\n"),notes.load().version)
        val staged=mutableListOf<MemoryReviewChange>()
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=true,skillsEnabled=false,
            background=true,stagedChanges=staged)
        actions.execute("memory_read",mapOf("target" to "memory"))
        try {
            actions.execute("memory_change",mapOf("target" to "memory","operation" to "add",
                "content" to "y".repeat(50)))
            fail("an add past the capacity must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("remove or replace existing text in this batch before adding"))
        }
        // A rejected change must not be staged, or the reviewer would believe it landed.
        assertTrue(staged.isEmpty())
        // Freeing space in the same batch is what the message asks for, and it must succeed.
        for(line in listOf("note-007-aaaaaaaaaaaa","note-008-aaaaaaaaaaaa")) {
            assertEquals("staged",actions.execute("memory_change",mapOf("target" to "memory",
                "operation" to "remove","old_text" to line)).optString("status"))
        }
        assertEquals("staged",actions.execute("memory_change",mapOf("target" to "memory",
            "operation" to "add","content" to "y".repeat(50))).optString("status"))
    }

    /** Notes edits go through the repository, so its failure reasons are what the reviewer reads. */
    @Test fun notesEditFailuresExplainThemselvesToTheReviewer() = runBlocking {
        val context=context()
        val notes=MemoryNotesRepository(context,"space")
        notes.save("Project A uses Kotlin. Project B uses Kotlin.",notes.load().version)
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=true,skillsEnabled=false,
            background=true,stagedChanges=mutableListOf())
        actions.execute("memory_read",mapOf("target" to "memory"))
        suspend fun change(vararg args: Pair<String,String>): String = try {
            actions.execute("memory_change",mapOf("target" to "memory")+args)
            "accepted"
        } catch (e: IllegalArgumentException) {
            e.message.orEmpty()
        }
        // Ambiguous and missing old_text are refused with the same wording the direct writer uses.
        assertEquals("old_text must match exactly once",
            change("operation" to "remove","old_text" to "Kotlin"))
        assertEquals("old_text must match exactly once",
            change("operation" to "replace","old_text" to "missing","content" to "x"))
        assertEquals("old_text and content are required",
            change("operation" to "remove","old_text" to " "))
        assertEquals("content is required",
            change("operation" to "add","content" to "   "))
        assertEquals("Use add/replace/remove",
            change("operation" to "append","content" to "x"))
    }

    @Test fun skillFilesCannotRemoveSkillMarkdown() = runBlocking {
        val context=context()
        val actions=MemoryLearningActions(context,"space","chat",notesEnabled=false,skillsEnabled=true,
            background=true,stagedChanges=mutableListOf())
        try {
            actions.execute("skill_remove_file",mapOf("name" to "demo-skill","path" to "SKILL.md"))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("skill_delete"))
        }
        // Omitting path means SKILL.md, so the message must point at the companion-file form.
        try {
            actions.execute("skill_remove_file",mapOf("name" to "demo-skill"))
            fail()
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("Pass path"))
        }
    }
    @Test fun skillActionBlockOnlyRejectsCallsThatCannotSucceed() {
        assertNull(skillActionBlock("skill_write","SKILL.md",false))
        assertNull(skillActionBlock("skill_patch","references/notes.md",false))
        assertNull(skillActionBlock("skill_remove_file","references/notes.md",false))
        assertNull(skillActionBlock("skill_delete","SKILL.md",false))
        assertTrue(skillActionBlock("skill_remove_file","SKILL.md",false)!!.contains("skill_delete"))
        assertTrue(skillActionBlock("skill_patch","references/notes.md",true)!!.contains("skill_create"))
    }
    @Test fun stagedLookupsIgnoreUnrelatedKindsAndPaths() {
        val changes=listOf(
            MemoryReviewChange("1","skill","demo-skill","draft"),
            MemoryReviewChange("2","skill_file","demo-skill","main",path="SKILL.md"),
            MemoryReviewChange("3","skill_file","demo-skill","notes",path="references/notes.md"),
            MemoryReviewChange("4","notes","memory.md","notes body"))
        assertEquals("main",stagedSkillFile(changes,"demo-skill","SKILL.md")!!.body)
        assertEquals("notes",stagedSkillFile(changes,"demo-skill","references/notes.md")!!.body)
        assertNull(stagedSkillFile(changes,"demo-skill","references/other.md"))
        assertEquals("draft",stagedSkillCreate(changes,"demo-skill")!!.body)
        assertNull(stagedSkillCreate(changes,"other-skill"))
        assertEquals("notes body",stagedDocument(changes,false)!!.body)
        assertNull(stagedDocument(changes,true))
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

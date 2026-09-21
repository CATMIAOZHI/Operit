package com.ai.assistance.operit.data.preferences

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import android.content.Context
import android.content.SharedPreferences
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LearningPromptSnapshotRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private fun repo(chat: String) = LearningPromptSnapshotRepository(folder.root, chat)

    @Test fun `old chats retain complete prefixes after restart while new chats use latest`() = runBlocking {
        repo("old").beginEpoch("")
        assertEquals("v1", repo("old").bindSystem("v1", mapOf("user" to "one")))
        assertEquals("v1", repo("old").bindSystem("v2", mapOf("user" to "two")))
        repo("new").beginEpoch("")
        assertEquals("v2", repo("new").bindSystem("v2", mapOf("user" to "two")))
        assertEquals("v1", repo("old").bindSystem("v3", emptyMap()))
    }

    @Test fun `confirmed rebuild affects only selected chat and reloads learnable inputs`() = runBlocking {
        listOf("a", "b").forEach {
            repo(it).beginEpoch("")
            repo(it).getOrPut("notes") { "old notes" }
            repo(it).bindSystem("old system", emptyMap())
        }
        repo("a").delete()
        repo("a").beginEpoch("")
        assertEquals("new notes", repo("a").getOrPut("notes") { "new notes" })
        assertEquals("new system", repo("a").bindSystem("new system", emptyMap()))
        assertEquals("old notes", repo("b").getOrPut("notes") { "new notes" })
        assertEquals("old system", repo("b").bindSystem("new system", emptyMap()))
    }

    @Test fun `successful compression refreshes once and other chats remain unchanged`() = runBlocking {
        listOf("a", "b").forEach {
            repo(it).beginEpoch("")
            repo(it).getOrPut("skill_catalog") { "v1" }
            repo(it).bindSystem("v1", emptyMap())
        }
        repo("a").beginEpoch("persisted summary")
        assertEquals("v2", repo("a").getOrPut("skill_catalog") { "v2" })
        assertEquals("v2", repo("a").bindSystem("v2", emptyMap()))
        repo("a").beginEpoch("persisted summary")
        assertEquals("v2", repo("a").bindSystem("v3", emptyMap()))
        assertEquals("v1", repo("b").bindSystem("v3", emptyMap()))
    }

    @Test fun `group members have separate identities in one chat and protocol changes require rebuild`() = runBlocking {
        repo("group").beginEpoch("")
        repo("group").bindSystem("Alice", emptyMap(), "native", "alice")
        assertEquals("Bob", repo("group").bindSystem("Bob", emptyMap(), "native", "bob"))
        assertEquals("Alice", repo("group").bindSystem("new Alice", emptyMap(), "native", "alice"))
        try {
            repo("group").bindSystem("Alice XML", emptyMap(), "xml", "alice")
            fail("Incompatible tool instructions must not be sent")
        } catch (_: LearningPromptSnapshotRepository.IncompatiblePrefixException) { }
        repo("group").delete()
        assertEquals("Alice XML", repo("group").bindSystem("Alice XML", emptyMap(), "xml", "alice"))
    }

    @Test fun `manual changes mark existing chats pending without changing their prefixes`() = runBlocking {
        val context = mock<Context>()
        val prefs = mock<SharedPreferences>()
        val revisions = mutableMapOf("user" to "one")
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        whenever(prefs.getString(any(), any())).thenAnswer { revisions[it.getArgument<String>(0)].orEmpty() }
        repo("old").bindSystem("original", revisions.toMap())
        assertFalse(repo("old").status(context).needsRebuild)
        revisions["user"]="two"
        assertTrue(repo("old").status(context).needsRebuild)
        assertEquals("original", repo("old").bindSystem("updated", revisions.toMap()))
        repo("new").bindSystem("updated", revisions.toMap())
        assertFalse(repo("new").status(context).needsRebuild)
        assertTrue(repo("old").status(context).needsRebuild)
    }
}

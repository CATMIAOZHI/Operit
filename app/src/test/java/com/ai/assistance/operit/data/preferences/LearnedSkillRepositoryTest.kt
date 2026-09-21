package com.ai.assistance.operit.data.preferences

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import com.ai.assistance.operit.core.tools.skill.SkillPackage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.*

class LearnedSkillRepositoryTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var repo: LearnedSkillRepository
    private lateinit var skillDir: File
    private lateinit var skillFile: File
    private val singleton = SkillManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible=true }
    private var previousManager: Any? = null

    @Before fun setup() {
        val context=mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        whenever(context.filesDir).thenReturn(folder.root)
        val root=folder.newFolder("skills")
        skillDir=File(root,"learned").apply { mkdirs() }
        skillFile=File(skillDir,"SKILL.md").apply { writeText("---\nname: learned\ndescription: Example\n---\n\nVerified steps") }
        val manager=mock<SkillManager>()
        whenever(manager.getSkillsDirectoryPath()).thenReturn(root.absolutePath)
        whenever(manager.getAvailableSkills()).thenReturn(mapOf("learned" to
            SkillPackage("learned","Example",skillDir,skillFile)))
        whenever(manager.parseSkillMetadataText(any())).thenCallRealMethod()
        previousManager=singleton.get(null)
        singleton.set(null,manager)
        repo=LearnedSkillRepository(context)
    }

    @After fun restore() { singleton.set(null,previousManager) }

    @Test fun `only an approved skill in its owning space can receive automatic edits`() = runBlocking {
        val before=repo.read("learned")
        fails { repo.apply("work","learned","SKILL.md",before.text+"\nUpdated",before.version,automatic=true) }
        assertTrue(repo.owned().isEmpty())
        repo.register("learned","work")
        fails { repo.apply("home","learned","SKILL.md",before.text+"\nUpdated",before.version,automatic=true) }
        repo.apply("work","learned","SKILL.md",before.text+"\nUpdated",before.version,automatic=true)
        assertTrue(skillFile.readText().endsWith("Updated"))
        assertEquals(setOf("learned"),repo.owned("work"))
        repo.forget("learned")
        assertTrue(repo.owned().isEmpty())
    }

    @Test fun `supporting files preserve skill body and stale edits cannot overwrite user changes`() = runBlocking {
        repo.register("learned","work")
        val original=skillFile.readText()
        val absent=repo.read("learned","references/checks.md")
        repo.apply("work","learned","references/checks.md","checks",absent.version,automatic=true)
        assertEquals(original,skillFile.readText())
        val before=repo.read("learned","references/checks.md")
        File(skillDir,"references/checks.md").writeText("manual correction")
        fails { repo.apply("work","learned","references/checks.md","stale",before.version,automatic=true) }
        assertEquals("manual correction",repo.read("learned","references/checks.md").text)
    }

    @Test fun `path traversal and renamed skill identity are rejected`() = runBlocking {
        repo.register("learned","work")
        fails { repo.read("learned","references/../../outside") }
        val before=repo.read("learned")
        fails { repo.apply("work","learned","SKILL.md",before.text.replace("name: learned","name: other"),before.version,automatic=true) }
        assertEquals(before.text,skillFile.readText())
        fails { repo.apply("work","learned","SKILL.md","",before.version,remove=true) }
        assertTrue(skillFile.exists())
    }

    private suspend fun fails(block: suspend () -> Unit) {
        try { block(); fail("Expected operation to be rejected") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
}

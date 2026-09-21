package com.ai.assistance.operit.api.chat.library

import android.content.Context
import com.ai.assistance.operit.core.tools.skill.SkillManager
import com.ai.assistance.operit.data.preferences.*
import com.ai.assistance.operit.data.repository.ChatRecallRepository
import org.json.JSONArray
import org.json.JSONObject

/** Scoped capabilities, shared by the isolated reviewer and foreground package tools. */
class MemoryLearningActions(
    private val context: Context, val profileId: String, private val sourceChatId: String,
    private val notesEnabled: Boolean, private val skillsEnabled: Boolean,
    private val background: Boolean, private val onCreated: () -> Unit = {}
) {
    private val skills = LearnedSkillRepository(context)
    private val reviews = MemoryReviewRepository(context,profileId)
    private val readVersions = mutableMapOf<String,String>()
    suspend fun execute(action: String, args: Map<String,String>): JSONObject {
        fun arg(name:String) = args[name].orEmpty()
        val name = arg("name")
        val path = arg("path").ifBlank { "SKILL.md" }
        return when(action) {
            "history" -> ChatRecallRepository(context).execute(args)
            "memory_read" -> {
                check(notesEnabled)
                val user = arg("target")=="user"
                val content = if(user) UserProfileDocumentRepository.getInstance(context).load()
                    else MemoryNotesRepository(context,profileId).load().markdown
                val version = LearnedSkillRepository.version(content)
                readVersions[if(user) "user" else "memory"] = version
                JSONObject().put("content",content).put("version",version)
            }
            "memory_change" -> {
                check(notesEnabled)
                val user = arg("target")=="user"
                val current = if(user) UserProfileDocumentRepository.getInstance(context).load()
                    else MemoryNotesRepository(context,profileId).load().markdown
                val key = if(user) "user" else "memory"
                val expected = if(background) readVersions[key] else arg("version")
                check(expected==LearnedSkillRepository.version(current)) { "Read the current document before changing it" }
                val operation = arg("operation")
                val after = editText(current,operation,arg("content"),arg("old_text"))
                val change = if(user) {
                    require(after.length<=12_000)
                    reviews.proposeUser(current,after,sourceChatId,onCreated)
                } else {
                    val repo = MemoryNotesRepository(context,profileId)
                    val before = repo.load()
                    check(before.markdown==current)
                    require(after.length<=MemoryNotesRepository.MAX_CHARS)
                    reviews.proposeNotes(before,after,if(operation=="add") arg("content") else "",sourceChatId,onCreated)
                }
                reviews.toJson(change)
            }
            "skill_list" -> {
                check(skillsEnabled)
                val owned = skills.owned(profileId)
                JSONObject().put("skills",JSONArray().apply {
                    SkillManager.getInstance(context).getAvailableSkills().values.forEach {
                        put(JSONObject().put("name",it.name).put("description",it.description)
                            .put("learned_in_this_space",it.name in owned))
                    }
                })
            }
            "skill_read" -> {
                check(skillsEnabled)
                val snapshot = skills.read(name,path)
                readVersions["$name/$path"] = snapshot.version
                JSONObject().put("content",snapshot.text).put("version",snapshot.version)
                    .put("exists",snapshot.exists).put("files",JSONArray(skills.files(name)))
                    .put("directory_version",skills.readDirectory(name).also { readVersions["$name/"] = it.version }.version)
            }
            "skill_create" -> {
                check(skillsEnabled)
                val parsed = parseSkillDrafts(JSONArray().put(JSONObject().put("name",name)
                    .put("description",arg("description")).put("body",arg("content"))),sourceChatId)
                require(parsed.size==1) { "Invalid skill draft" }
                check(SkillManager.getInstance(context).getAvailableSkills()[name]==null) { "Update the existing skill instead" }
                reviews.toJson(reviews.proposeSkill(parsed.single(),onCreated))
            }
            "skill_delete" -> {
                check(skillsEnabled)
                val before=skills.readDirectory(name)
                check((if(background) readVersions["$name/"] else arg("version"))==before.version) {
                    "Read the skill and use its directory_version before proposing deletion"
                }
                reviews.toJson(reviews.proposeSkillDeletion(name,before,sourceChatId,onCreated))
            }
            "skill_write","skill_patch","skill_remove_file" -> {
                check(skillsEnabled)
                val before = skills.read(name,path)
                val expected = if(background) readVersions["$name/$path"] else arg("version")
                check(expected==before.version) { "Read this file before editing it" }
                val remove = action=="skill_remove_file"
                val content = if(action=="skill_patch") editText(before.text,"replace",arg("content"),arg("old_text"))
                    else arg("content")
                val automatic = background && !remove && name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()
                val change = reviews.proposeSkillFile(name,path,before,content,remove,automatic,sourceChatId,onCreated)
                val applied = if(automatic) reviews.decide(context,change.id,true,"background",arg("reason")) else change
                readVersions.remove("$name/$path")
                reviews.toJson(applied)
            }
            else -> error("Unknown learning action")
        }
    }
}

internal fun editText(current: String, operation: String, content: String, old: String): String = when(operation) {
    "add" -> {
        require(content.isNotBlank())
        if (("\n\n$current\n\n").contains("\n\n${content.trim()}\n\n")) current
        else listOf(current.trimEnd(),content.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
    }
    "replace","remove" -> {
        require(old.isNotBlank())
        val at = current.indexOf(old)
        require(at>=0 && current.indexOf(old,at+1)<0) { "old_text must match exactly once" }
        current.replaceRange(at,at+old.length,if(operation=="remove") "" else content)
    }
    else -> error("Use add/replace/remove")
}

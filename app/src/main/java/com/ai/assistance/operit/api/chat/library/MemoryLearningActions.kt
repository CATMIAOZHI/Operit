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
    private val background: Boolean,
    private val stagedChanges: MutableList<MemoryReviewChange>? = null,
    private val onCreated: () -> Unit = {}
) {
    private val skills = LearnedSkillRepository(context)
    private val reviews = MemoryReviewRepository(context,profileId,stagedChanges)
    private val readVersions = mutableMapOf<String,String>()
    suspend fun execute(action: String, args: Map<String,String>): JSONObject {
        fun arg(name:String) = args[name].orEmpty()
        val name = arg("name")
        val path = arg("path").ifBlank { "SKILL.md" }
        return when(action) {
            "history" -> ChatRecallRepository(context).execute(args, filterAssistantThinking = true,
                includeThinking = MemorySearchSettingsPreferences(context, profileId).shouldIncludeThinking())
            "memory_read" -> {
                check(notesEnabled) { "Note extraction is not scheduled for this run. Do not retry memory operations; continue skill work or finish." }
                val user = arg("target")=="user"
                val disk = if(user) UserProfileDocumentRepository.getInstance(context).load()
                    else MemoryNotesRepository(context,profileId).load().markdown
                val staged = stagedDocument(stagedChanges, user)
                val content = staged?.body ?: disk
                // The version stays the on-disk one so a revision inside the batch keeps passing its check.
                val version = LearnedSkillRepository.version(disk)
                readVersions[if(user) "user" else "memory"] = version
                JSONObject().put("content",content).put("version",version).put("staged",staged!=null).apply {
                    if (user) {
                        val sections = UserProfileSections.parse(content)
                        put("sections", JSONObject().put("profile", sections.profile)
                            .put("preferences", sections.preferences)
                            .put("interaction_rules", sections.interactionRules))
                    }
                }
            }
            "memory_change" -> {
                check(notesEnabled) { "Note extraction is not scheduled for this run. Do not retry memory operations; continue skill work or finish." }
                val user = arg("target")=="user"
                val disk = if(user) UserProfileDocumentRepository.getInstance(context).load()
                    else MemoryNotesRepository(context,profileId).load().markdown
                val key = if(user) "user" else "memory"
                val expected = if(background) readVersions[key] else arg("version")
                check(expected==LearnedSkillRepository.version(disk)) { "Read the current document before changing it" }
                val operation = arg("operation")
                val section = arg("section")
                val base = stagedDocument(stagedChanges, user)?.body ?: disk
                val after = if (user && section.isNotBlank()) {
                    val sections = UserProfileSections.parse(base)
                    sections.with(section, editText(sections.get(section), operation,
                        arg("content"), arg("old_text"))).markdown()
                } else if (user) editText(base,operation,arg("content"),arg("old_text"))
                else notesEdit(base,operation,arg("content"),arg("old_text"))
                val change = if(user) {
                    require(after.length<=UserProfileDocumentRepository.MAX_CONTENT_CHARS) {
                        "user.md would exceed its character limit; remove or replace existing text in this batch before adding"
                    }
                    reviews.proposeUser(disk,after,sourceChatId,onCreated)
                } else {
                    val repo = MemoryNotesRepository(context,profileId)
                    val before = repo.load()
                    check(before.markdown==disk)
                    require(after.length<=MemoryNotesRepository.MAX_CHARS) {
                        MemoryNotesRepository.OVERFLOW_MESSAGE
                    }
                    reviews.proposeNotes(before,after,if(operation=="add") arg("content") else "",sourceChatId,onCreated)
                }
                val applied = reviews.applyAutomaticDecision(context, change)
                if (stagedChanges==null) readVersions.remove(key)
                reviews.toJson(applied)
            }
            "skill_list" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                val owned = skills.owned(profileId)
                JSONObject().put("skills",JSONArray().apply {
                    SkillManager.getInstance(context).getAvailableSkills().values.forEach {
                        put(JSONObject().put("name",it.name).put("description",it.description)
                            .put("learned_in_this_space",it.name in owned))
                    }
                })
            }
            "skill_read" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                val created = stagedSkillCreate(stagedChanges, name)
                if (created != null) {
                    // The batch's own draft is not installed yet, so it has no version and no files.
                    check(path=="SKILL.md") {
                        "This skill was created in this batch and is not installed yet, so it has no $path."
                    }
                    return JSONObject().put("content", created.body).put("version","")
                        .put("exists",false).put("staged",true).put("files",JSONArray().put("SKILL.md"))
                        .put("directory_version","")
                }
                val snapshot = skills.read(name,path)
                readVersions["$name/$path"] = snapshot.version
                val staged = stagedSkillFile(stagedChanges,name,path)
                JSONObject().put("content",staged?.body ?: snapshot.text).put("version",snapshot.version)
                    .put("exists",snapshot.exists).put("files",JSONArray(skills.files(name)))
                    .put("staged",staged!=null)
                    .put("directory_version",skills.readDirectory(name).also { readVersions["$name/"] = it.version }.version)
            }
            "skill_create" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                require(Regex("[a-z][a-z0-9-]{2,63}").matches(name)) {
                    "Skill name must be 3-64 lowercase ASCII letters, digits or hyphens, start with a letter; underscores are not allowed"
                }
                require(arg("description").trim().length in 1..LearnedSkillRepository.MAX_SKILL_DESCRIPTION_CHARS &&
                    !arg("description").contains('\n')) {
                    "Skill description must be one line, 1-${LearnedSkillRepository.MAX_SKILL_DESCRIPTION_CHARS} characters"
                }
                // Frontmatter is composed for you, so a supplied header is stripped before the
                // length check; otherwise this would accept a draft the installer rejects.
                val body = stripSkillFrontmatter(arg("content").trim())
                require(body.length in LearnedSkillRepository.MIN_SKILL_BODY_CHARS..
                    LearnedSkillRepository.MAX_SKILL_BODY_CHARS) {
                    "Skill content must be ${LearnedSkillRepository.MIN_SKILL_BODY_CHARS}-" +
                        "${LearnedSkillRepository.MAX_SKILL_BODY_CHARS} characters, without YAML frontmatter"
                }
                val parsed = parseSkillDrafts(JSONArray().put(JSONObject().put("name",name)
                    .put("description",arg("description")).put("body",body)),sourceChatId)
                require(parsed.size==1) { "Invalid skill draft" }
                check(SkillManager.getInstance(context).getAvailableSkills()[name]==null) { "Update the existing skill instead" }
                reviews.toJson(reviews.applyAutomaticDecision(context, reviews.proposeSkill(parsed.single(),onCreated)))
            }
            "skill_delete" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                skillActionBlock(action,path,stagedSkillCreate(stagedChanges,name)!=null)?.let { error(it) }
                if (background) check(name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()) {
                    "Background deletion is limited to enabled learned skills in this space"
                }
                val before=skills.readDirectory(name)
                check((if(background) readVersions["$name/"] else arg("version"))==before.version) {
                    "Read the skill and use its directory_version before proposing deletion"
                }
                reviews.toJson(reviews.applyAutomaticDecision(context,
                    reviews.proposeSkillDeletion(name,before,sourceChatId,onCreated)))
            }
            "skill_write","skill_patch","skill_remove_file" -> {
                check(skillsEnabled) { "Skill extraction is not scheduled for this run. Do not retry skill operations; continue note work or finish." }
                skillActionBlock(action,path,stagedSkillCreate(stagedChanges,name)!=null)?.let { error(it) }
                val before = skills.read(name,path)
                val expected = if(background) readVersions["$name/$path"] else arg("version")
                check(expected==before.version) { "Read this file before editing it" }
                val remove = action=="skill_remove_file"
                // A revision patches the version this batch staged, not the untouched file on disk.
                val content = if(action=="skill_patch") editText(stagedSkillFile(stagedChanges,name,path)?.body ?: before.text,"replace",arg("content"),arg("old_text"))
                    else arg("content")
                val automatic = background && !remove && name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()
                if (background) check(name in skills.owned(profileId) &&
                    MemorySearchSettingsPreferences(context,profileId).mayReviseLearnedSkills()) {
                    "Background revision is limited to enabled learned skills in this space"
                }
                val change = reviews.proposeSkillFile(name,path,before,content,remove,automatic,sourceChatId,onCreated)
                val applied = reviews.applyAutomaticDecision(context,change)
                if (stagedChanges==null) readVersions.remove("$name/$path")
                reviews.toJson(applied)
            }
            else -> error("Unknown learning action")
        }
    }
}

/**
 * Note edits use the repository's own rules, so the extraction tool and the direct writer cannot
 * disagree about duplicates, ambiguity or empty text. Only the failure wording is local.
 */
private fun notesEdit(base: String, operation: String, content: String, oldText: String): String =
    try {
        MemoryNotesRepository.applyEdit(base, operation, content, oldText)
    } catch (e: MemoryNotesRepository.NotesException) {
        // Kept as IllegalArgumentException: that is the type the notes path raised before this was
        // single-sourced, and the surrounding tool layers only ever catch the message.
        throw IllegalArgumentException(when (e.reason) {
            MemoryNotesRepository.Failure.EMPTY ->
                if (operation == "add") "content is required" else "old_text and content are required"
            MemoryNotesRepository.Failure.NOT_UNIQUE -> "old_text must match exactly once"
            MemoryNotesRepository.Failure.CONFLICT -> "memory.md changed while this batch ran; read it again"
            MemoryNotesRepository.Failure.FULL -> MemoryNotesRepository.OVERFLOW_MESSAGE
            MemoryNotesRepository.Failure.INVALID -> "Use add/replace/remove"
        })
    }

/** What this batch staged for a skill file, so reads and revisions see it instead of the disk copy. */
internal fun stagedSkillFile(changes: List<MemoryReviewChange>?, name: String, path: String) =
    changes?.lastOrNull { it.kind == "skill_file" && it.title == name && it.path == path }
/** A skill created earlier in this batch; it is not on disk yet, so it has no version or file list. */
internal fun stagedSkillCreate(changes: List<MemoryReviewChange>?, name: String) =
    changes?.lastOrNull { it.kind == "skill" && it.title == name }
internal fun stagedDocument(changes: List<MemoryReviewChange>?, user: Boolean) =
    changes?.lastOrNull { it.kind == if (user) "user" else "notes" }
/**
 * Null when a skill-file action may proceed, otherwise the message that tells the reviewer what to do
 * instead. A skill that exists only as this batch's draft has no files on disk to read or change, and
 * SKILL.md is never removable.
 */
internal fun skillActionBlock(action: String, path: String, drafted: Boolean): String? = when {
    drafted -> "This skill was created in this batch and is not installed yet, so it has no files here; " +
        "resubmit its full content with skill_create to revise it."
    action=="skill_remove_file" && path=="SKILL.md" ->
        "Pass path to remove one companion file under references/, scripts/, templates/ or assets/, " +
            "or delete the whole skill with skill_delete."
    else -> null
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

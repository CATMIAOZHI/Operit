package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolParameterSchema
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolResult
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitSettings

/** Tools that only an Orbit can use, in its own chat. */
internal object OrbitTools {
    const val SET_NAME = "orbit_set_name"

    val categoryEn =
        SystemToolPromptCategory(
            categoryName = "Orbit",
            tools =
                OrbitProductTools.prompts + OrbitBoardTools.prompts + OrbitExtraTools.prompts + listOf(OrbitSkillTools.prompt, OrbitSoulTools.prompt, OrbitTaskTools.prompt,
                    ToolPrompt(
                        name = SET_NAME,
                        description = "Change your own name as the user's Orbit. The new name shows at the top of this chat.",
                        parametersStructured =
                            listOf(
                                ToolParameterSchema(
                                    name = "name",
                                    type = "string",
                                    description = "your new name, at most ${OrbitSettings.MAX_NAME_LENGTH} characters",
                                    required = true,
                                )
                            ),
                        details = """
  - Use it when the user asks you to take a new name, or when you two agree on one.
  - The name is yours: the user cannot edit it directly, so only you can change it.
""",
                    )
                ),
        )

    val categoryCn =
        SystemToolPromptCategory(
            categoryName = "Orbit",
            tools =
                OrbitProductTools.prompts + OrbitBoardTools.prompts + OrbitExtraTools.prompts + listOf(OrbitSkillTools.prompt, OrbitSoulTools.prompt, OrbitTaskTools.prompt,
                    ToolPrompt(
                        name = SET_NAME,
                        description = "修改你作为用户 Orbit 的名字，新名字会显示在这个聊天顶部。",
                        parametersStructured =
                            listOf(
                                ToolParameterSchema(
                                    name = "name",
                                    type = "string",
                                    description = "新名字，最多 ${OrbitSettings.MAX_NAME_LENGTH} 个字符",
                                    required = true,
                                )
                            ),
                        details = """
  - 用户请你换名字，或你们商量好一个名字时使用。
  - 名字属于你自己：用户不能直接修改，只能由你调用这个工具改。
""",
                    )
                ),
        )

    /**
     * Hides Orbit tools everywhere except in a chat that belongs to an Orbit. Suspends: it reads
     * settings and the database, and is reached from the main thread when a chat is opened.
     */
    suspend fun visibility(context: Context, chatId: String?): Map<String, Boolean> {
        val isOrbitChat = OrbitPreferences.getInstance(context).current().orbitForChat(chatId) != null
        val hasOrbitSkills = com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId) != null
        val isTaskRoot = chatId?.let {
            com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).orbitTaskDao().binding(it)
        }?.let { it.chatId == it.rootChatId } == true
        return mapOf(SET_NAME to isOrbitChat, OrbitSkillTools.NAME to hasOrbitSkills,
            OrbitTaskTools.NAME to (isOrbitChat || isTaskRoot), OrbitSoulTools.NAME to (isOrbitChat || isTaskRoot),
            // A background routine (goal alignment) reads goals in full; the tool keeps it read-only there.
            OrbitProductTools.GOAL to (isOrbitChat || isTaskRoot), OrbitProductTools.ARTIFACT to isOrbitChat,
            OrbitBoardTools.FEED to (isOrbitChat || isTaskRoot), OrbitBoardTools.IDEA to (isOrbitChat || isTaskRoot),
            OrbitBoardTools.TRACKING to (isOrbitChat || isTaskRoot),
            com.ai.assistance.operit.features.orbit.OrbitTaskRunner.FINISH to false) +
            OrbitExtraTools.visibility(isOrbitChat, isTaskRoot) +
            // Browsing is mostly left to subagents, and they are the ones who meet a password page.
            mapOf(OrbitBrowserHandoffTools.NAME to hasOrbitSkills, OrbitBrowserShowTools.NAME to hasOrbitSkills)
    }

    /**
     * True in a chat that belongs to an Orbit. Orbit always has its tools under the fixed fast
     * auto-review permission, so the global tools switch does not turn them off there.
     */
    suspend fun isOrbitChat(context: Context, chatId: String?): Boolean =
        com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId) != null

    /**
     * Orbit's parts of the system prompt, in its own chat; null in any other chat. They are placed
     * apart so the prompt runs from what rarely changes to what changes often: [handbook] (PROMPT.md)
     * comes before the app's system prompt; [runtime], [identity], [soul] and [alignment] come after
     * it, followed by memory.md and user.md. [alignment] is the alignment synthesis, read in full in
     * every chat (Muse injects it rather than leaving it to a search); empty while it is a template.
     */
    class PromptParts(val handbook: String, val runtime: String, val identity: String, val soul: String,
        val alignment: String = "") {
        /** All parts in prompt order, for a copy the user can read. */
        val all: String get() = listOf(handbook, runtime, identity, soul, alignment).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    suspend fun promptParts(context: Context, chatId: String?, useEnglish: Boolean, useToolCallApi: Boolean = false): PromptParts? {
        val orbit = com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId) ?: return null
        val name = orbit.name.replace("<", "").replace(">", "").trim()
        val body =
            if (useEnglish) {
                "This chat is your Orbit space: here you are the user's long-term companion, in " +
                    "one conversation that keeps going." +
                    (if (name.isNotEmpty()) " Your name is \"$name\"." else "") +
                    " Only you can change your name, with $SET_NAME; the user cannot edit it." +
                    " Your profile, notes, history and learned skills belong to this Orbit. By default," +
                    " read and update only your own memory; do not search other Orbits or global memory." +
                    " If the user explicitly asks you to read another memory source, you may use available" +
                    " command or file tools under the normal permission review. Read only the requested" +
                    " content; do not automatically import it into your own memory."
            } else {
                "这个聊天是你的 Orbit 空间：你在这里是用户的长期伙伴，这段对话会一直延续。" +
                    (if (name.isNotEmpty()) "你的名字是「$name」。" else "") +
                    "名字只能由你调用 $SET_NAME 修改，用户不能直接改。" +
                    "你的用户资料、笔记、历史和积累的技能属于当前 Orbit。默认只读取和更新自己的记忆，" +
                    "不要主动检索其他 Orbit 或全局记忆。用户明确要求读取其他记忆来源时，可以在正常权限" +
                    "审核下使用现有命令或文件工具，仅读取用户要求的内容，不要自动导入自己的记忆。"
            }
        val controls = com.ai.assistance.operit.features.orbit.OrbitTaskExecution.additionalPrompts(context, chatId)
        // Native definitions already include descriptions, details and parameters. Notes are the
        // only supplement not carried by the providers' tool schemas.
        val controlPrompt = if (useToolCallApi) {
            controls.mapNotNull { tool -> tool.notes.takeIf { it.isNotBlank() }?.let { "${tool.name}:\n$it" } }
                .joinToString("\n")
        } else controls.joinToString("\n")
        val files = com.ai.assistance.operit.features.orbit.OrbitPromptFiles
        // The handbook tells Orbit to read docs/ in its folder; put the built-in copy there first.
        com.ai.assistance.operit.features.orbit.OrbitDocs.ensure(context, orbit.id, useEnglish)
        // File text is data inside our tags, so escape it the way memory notes are escaped.
        fun escape(text: String) = text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val handbook = "<orbit_handbook source=\"PROMPT.md\">\n${escape(files.prompt(context, orbit.id))}\n</orbit_handbook>"
        val identity = "<orbit_identity source=\"IDENTITY.md\">\n" +
            escape(files.identity(context, orbit.id)) +
            "\n</orbit_identity>"
        val soul = OrbitPreferences.getInstance(context).readSoul(orbit.id).takeIf { it.isNotBlank() }
            ?.let { "<orbit_soul source=\"SOUL.md\">\n${escape(it)}\n</orbit_soul>" }.orEmpty()
        val alignmentFile = com.ai.assistance.operit.features.orbit.OrbitFiles
        val alignment = runCatching { alignmentFile.of(context, orbit.id).read(alignmentFile.ALIGNMENT) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()?.let { alignmentFile.alignmentForPrompt(it) }
            ?.let { "<orbit_alignment source=\"ALIGNMENT_SYNTHESIS.md\">\n${escape(it)}\n</orbit_alignment>" }.orEmpty()
        // A side chat is its own thread; work started here reports back here.
        val side = OrbitPreferences.getInstance(context).current().orbits.firstOrNull { it.id == orbit.id }
            ?.sideChats?.firstOrNull { it.chatId == chatId }
        val sideNote = side?.let {
            val title = escape(it.title)
            if (useEnglish) "\nThis is a side chat titled \"$title\", separate from the main chat. It keeps its own history; " +
                "reminders and tasks made here report back here unless the user asks for the main chat."
            else "\n这是一个侧聊，标题是「$title」，和主聊天分开，有自己的记录。在这里安排的提醒和任务，结果回到这里，除非用户要求发到主聊天。"
        }.orEmpty()
        val forkReference = side?.forkOrigin?.takeIf { it.chatId in orbit.talkChatIds }?.let {
            com.ai.assistance.operit.features.orbit.OrbitChatFork.prompt(context, it, useEnglish)
        }.orEmpty()
        val runtime = "<orbit>\n$body$sideNote\n${folderLine(context, orbit.id, useEnglish)}\n$controlPrompt\n</orbit>$forkReference"
        return PromptParts(handbook, runtime, identity, soul, alignment)
    }

    /**
     * Where this Orbit's folder is on the phone, so the app's file tools and the local terminal reach
     * its files. The same for every chat of the Orbit, so it never breaks the prompt cache.
     */
    private fun folderLine(context: Context, orbitId: String, useEnglish: Boolean): String {
        val root = OrbitPreferences.getInstance(context).orbitDir(orbitId).absolutePath
        return if (useEnglish) {
            "Your Orbit folder is at $root. A short path such as files/a.md is $root/files/a.md. Save and refer to " +
                "files by short path; use the full path with the app's file tools (environment=android, e.g. grep_code, " +
                "read_file) and the local terminal. orbit_file takes either."
        } else {
            "你的 Orbit 文件夹在 $root。短路径 files/a.md 就是 $root/files/a.md。保存和引用文件用短路径；" +
                "用应用的文件工具（environment=android，比如 grep_code、read_file）或本地终端时用完整路径。orbit_file 两种都接受。"
        }
    }

    /**
     * For a subagent working for an Orbit: only the folder line, wrapped in its own tag. Subagents do
     * not get the Orbit's private prompt, but they work on its files too. Null outside an Orbit.
     */
    suspend fun subagentFolderNote(context: Context, chatId: String?, useEnglish: Boolean): String? {
        val orbit = runCatching { com.ai.assistance.operit.features.orbit.OrbitMemoryScope.owner(context, chatId) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull() ?: return null
        return "<orbit_folder>\n${folderLine(context, orbit.id, useEnglish)}\n</orbit_folder>"
    }

    fun executeSetName(context: Context, tool: AITool): ToolResult {
        val chatId = ToolExecutionManager.currentToolRuntimeContext()?.callerChatId
        val name =
            tool.parameters.firstOrNull { it.name == "name" }?.value
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
        if (name.isBlank()) return failure(context.getString(R.string.orbit_tool_name_missing))
        if (name.length > OrbitSettings.MAX_NAME_LENGTH) {
            return failure(
                context.getString(R.string.orbit_tool_name_too_long, OrbitSettings.MAX_NAME_LENGTH)
            )
        }
        val preferences = OrbitPreferences.getInstance(context)
        return runBlockingIoPreservingToolRuntimeContext {
            val orbit =
                preferences.current().orbitForChat(chatId)
                    ?: return@runBlockingIoPreservingToolRuntimeContext failure(
                        context.getString(R.string.orbit_tool_not_orbit_chat)
                    )
            preferences.setName(orbit.id, name)
            runCatching { com.ai.assistance.operit.features.orbit.OrbitPromptFiles.syncIdentityName(context, orbit.id, name) }
            // Keep the chat list and notifications in step with the new name.
            runCatching {
                com.ai.assistance.operit.api.chat.ChatRuntimeHolder.getInstance(context.applicationContext)
                    .getCore(com.ai.assistance.operit.api.chat.ChatRuntimeSlot.MAIN)
                    .getChatHistoryDelegate()
                    .updateChatTitleAndWait(orbit.chatId, context.getString(R.string.orbit_chat_title, name))
            }
            ToolResult(
                toolName = SET_NAME,
                success = true,
                result = StringResultData(context.getString(R.string.orbit_tool_name_set, name)),
            )
        }
    }

    private fun failure(message: String): ToolResult =
        ToolResult(
            toolName = SET_NAME,
            success = false,
            result = StringResultData(message),
            error = message,
        )
}

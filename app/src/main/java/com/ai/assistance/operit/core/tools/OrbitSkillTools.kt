package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import com.ai.assistance.operit.data.skill.SkillRepository
import com.ai.assistance.operit.features.orbit.OrbitApprovalActions
import com.ai.assistance.operit.features.orbit.OrbitApprovals
import com.ai.assistance.operit.features.orbit.OrbitChatCards
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitSkillCatalog
import org.json.JSONArray
import org.json.JSONObject

internal object OrbitSkillTools {
    const val NAME = "orbit_skills"
    val prompt = ToolPrompt(name = NAME,
        description = "按需搜索当前 Orbit 可用技能的名称和简介；找到后用 use_package 加载正文。也能列出全部技能（含已隐藏的），并申请对你显示或隐藏某些技能（用户在批准卡片上同意后生效）。不会读取其他 Orbit 或全局私有技能，也不会提升权限。",
        parametersStructured = listOf(
            ToolParameterSchema("action", description = "search（默认，只搜对你显示的技能）/ list_all（全部技能及是否显示）/ request_visibility（申请显示或隐藏）", required = false),
            ToolParameterSchema("query", description = "search / list_all：名称或简介关键词，空字符串浏览目录，最多200字符", required = false),
            ToolParameterSchema("offset", type = "integer", description = "search / list_all：从0开始，继续浏览用返回的next_offset", required = false),
            ToolParameterSchema("limit", type = "integer", description = "search / list_all：每页1到20，默认20", required = false),
            ToolParameterSchema("show", description = "request_visibility：要对你显示的技能名，逗号分隔，名字照抄 list_all", required = false),
            ToolParameterSchema("hide", description = "request_visibility：要对你隐藏的技能名，逗号分隔，名字照抄 list_all", required = false),
            ToolParameterSchema("reason", description = "request_visibility：一句话告诉用户为什么", required = false)),
        details = """
  - 只有用户想开关技能时才用 request_visibility。它会在聊天里放一张批准卡片，放完用一句话结束这一轮；用户同意后 App 去改，你会收到一条带逐项结果的隐藏通知。
  - 隐藏的技能不会出现在 search 和技能目录里，也不能加载。
""")

    fun execute(context: Context, handler: AIToolHandler, tool: AITool): ToolResult =
        runBlockingIoPreservingToolRuntimeContext {
          try {
            val runtime = checkNotNull(ToolExecutionManager.currentToolRuntimeContext())
            val orbit = checkNotNull(OrbitMemoryScope.owner(context, runtime.callerChatId)) { "Not an Orbit conversation" }
            val access = CharacterCardToolAccessResolver.getInstance(context).resolve(
                runtime.callerCardId, handler.getOrCreatePackageManager(), chatId = runtime.callerChatId)
            check(access.canUsePackageSystem && (access.effectiveBuiltinToolVisibility["use_package"] ?: true)) {
                "Skill loading is disabled in this conversation"
            }
            val args = tool.parameters.associate { it.name to it.value }
            fun int(name: String, default: Int): Int = args[name]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull() ?: error("$name must be a whole number, got \"${it.take(20)}\"")
            } ?: default
            val query = args["query"].orEmpty()
            require(query.length <= 200) { "query has ${query.length} characters; at most 200" }
            val repository = SkillRepository.forChat(context, runtime.callerChatId)
            when (args["action"]?.trim().orEmpty().ifEmpty { "search" }) {
                "search" -> Unit
                "list_all" -> {
                    val all = repository.getAvailableSkillPackages()
                    val page = OrbitSkillCatalog.page(all.mapValues { it.value.description }, query, int("offset", 0), int("limit", 20))
                    val visibility = repository.skillVisibilityPreferences
                    val result = JSONObject().put("skills", JSONArray(page.entries.map {
                        JSONObject().put("name", it.name).put("description", it.description)
                            .put("shown_to_you", visibility.isSkillVisibleToAi(it.name))
                    })).put("query", query).put("returned", page.entries.size).put("total", page.total)
                        .put("next_offset", page.nextOffset ?: JSONObject.NULL)
                        .put("next", if (page.total == 0) "No skill matches."
                            else "To show or hide skills for yourself, use request_visibility with exact names from here.")
                    return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(result.toString()))
                }
                "request_visibility" -> {
                    check(com.ai.assistance.operit.data.preferences.OrbitPreferences.getInstance(context).current()
                        .orbitForChat(runtime.callerChatId)?.id == orbit.id) {
                        "Approval cards only work in a chat with the user; ask the user in chat"
                    }
                    fun names(key: String) = args[key].orEmpty().split(',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    val show = names("show")
                    val hide = names("hide")
                    require(show.isNotEmpty() || hide.isNotEmpty()) { "Give show or hide: skill names from list_all, comma-separated" }
                    require(show.size + hide.size <= OrbitApprovalActions.MAX_ITEMS) {
                        "At most ${OrbitApprovalActions.MAX_ITEMS} skills in one request; ask for fewer at a time"
                    }
                    require(show.intersect(hide.toSet()).isEmpty()) { "A skill cannot be in both show and hide" }
                    val all = repository.getAvailableSkillPackages().keys
                    val unknown = (show + hide).filter { it !in all }
                    require(unknown.isEmpty()) { "No skill named ${unknown.joinToString()}; copy names exactly from list_all" }
                    val visibility = repository.skillVisibilityPreferences
                    val same = show.filter { visibility.isSkillVisibleToAi(it) } + hide.filter { !visibility.isSkillVisibleToAi(it) }
                    require(same.isEmpty()) { "Already as asked, leave them out: ${same.joinToString()}" }
                    val detail = OrbitApprovalActions.skillsDetail(context, show, hide, args["reason"].orEmpty().take(300))
                    require(detail.length <= OrbitApprovals.MAX_DETAIL) { "Too long for one approval card; ask for fewer skills" }
                    val approval = OrbitApprovals.request(context, orbit.id, runtime.callerChatId.orEmpty(),
                        context.getString(com.ai.assistance.operit.R.string.orbit_approval_skills_title).take(OrbitApprovals.MAX_TITLE),
                        detail, OrbitApprovalActions.skillsAction(show, hide))
                    val result = JSONObject().put("approval_id", approval.id).put("state", approval.state)
                        .put("note", "An approval card is now in the chat. End this turn now with one short line. " +
                            "Once the user approves, the app switches the skills and you get a hidden note with the result.")
                        .put(OrbitChatCards.KEY, OrbitChatCards.approval(orbit.id, approval))
                    return@runBlockingIoPreservingToolRuntimeContext ToolResult(tool.name, true, StringResultData(result.toString()))
                }
                else -> error("action must be search, list_all or request_visibility")
            }
            val page = OrbitSkillCatalog.page(
                repository.getAiVisibleSkillPackages()
                    .filterKeys { it in access.allowedSkillNames }.mapValues { it.value.description },
                query, int("offset", 0), int("limit", 20))
            val result = JSONObject().put("skills", JSONArray(page.entries.map {
                JSONObject().put("name", it.name).put("description", it.description)
            })).put("query", query).put("returned", page.entries.size).put("total", page.total)
                .put("next_offset", page.nextOffset ?: JSONObject.NULL)
                .put("next", if (page.total == 0) "No skill matches; try other keywords or an empty query to browse."
                    else "Load a relevant skill with use_package(package_name=name). This is a live directory; restart pagination if it changes.")
            ToolResult(tool.name, true, StringResultData(result.toString()))
          } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
          } catch (e: Exception) {
            ToolResult(tool.name, false, StringResultData(""), error = e.message ?: e.toString())
          }
        }
}

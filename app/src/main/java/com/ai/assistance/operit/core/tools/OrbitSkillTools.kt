package com.ai.assistance.operit.core.tools

import android.content.Context
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.data.model.*
import com.ai.assistance.operit.data.preferences.CharacterCardToolAccessResolver
import com.ai.assistance.operit.data.skill.SkillRepository
import com.ai.assistance.operit.features.orbit.OrbitMemoryScope
import com.ai.assistance.operit.features.orbit.OrbitSkillCatalog
import org.json.JSONArray
import org.json.JSONObject

internal object OrbitSkillTools {
    const val NAME = "orbit_skills"
    val prompt = ToolPrompt(name = NAME,
        description = "按需搜索当前 Orbit 可用技能的名称和简介；找到后用 use_package 加载正文。不会读取其他 Orbit 或全局私有技能，也不会提升权限。",
        parametersStructured = listOf(
            ToolParameterSchema("query", description = "名称或简介关键词，空字符串浏览目录，最多200字符", required = false),
            ToolParameterSchema("offset", type = "integer", description = "从0开始，继续浏览用返回的next_offset", required = false),
            ToolParameterSchema("limit", type = "integer", description = "每页1到20，默认20", required = false)))

    fun execute(context: Context, handler: AIToolHandler, tool: AITool): ToolResult =
        runBlockingIoPreservingToolRuntimeContext {
            val runtime = checkNotNull(ToolExecutionManager.currentToolRuntimeContext())
            checkNotNull(OrbitMemoryScope.owner(context, runtime.callerChatId)) { "Not an Orbit conversation" }
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
            val page = OrbitSkillCatalog.page(
                SkillRepository.forChat(context, runtime.callerChatId).getAiVisibleSkillPackages()
                    .filterKeys { it in access.allowedSkillNames }.mapValues { it.value.description },
                query, int("offset", 0), int("limit", 20))
            val result = JSONObject().put("skills", JSONArray(page.entries.map {
                JSONObject().put("name", it.name).put("description", it.description)
            })).put("query", query).put("returned", page.entries.size).put("total", page.total)
                .put("next_offset", page.nextOffset ?: JSONObject.NULL)
                .put("next", if (page.total == 0) "No skill matches; try other keywords or an empty query to browse."
                    else "Load a relevant skill with use_package(package_name=name). This is a live directory; restart pagination if it changes.")
            ToolResult(tool.name, true, StringResultData(result.toString()))
        }
}

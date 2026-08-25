package com.ai.assistance.operit.features.reading

import android.content.Context
import com.ai.assistance.operit.api.chat.EnhancedAIService
import com.ai.assistance.operit.api.chat.enhance.ToolExecutionManager
import com.ai.assistance.operit.core.chat.hooks.PromptTurn
import com.ai.assistance.operit.core.chat.hooks.PromptTurnKind
import com.ai.assistance.operit.data.model.FunctionType
import com.ai.assistance.operit.data.preferences.ModelConfigManager
import com.ai.assistance.operit.data.stats.TokenStatCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class ReadingCompanionModelGateway(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    suspend fun expandQuery(
        query: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): List<String> {
        val prompt =
            """
            你正在为小说伴读功能生成本地全文检索词。只能根据用户问题本身扩展，不得联网，
            不得猜测或补充后续剧情。输出严格 JSON：
            {"keywords":["词1","词2"]}
            最多 10 个关键词，保留人名、称呼、地点、物品和事件描述的同义表达。

            用户问题：
            $query
            """.trimIndent()
        val json = JSONObject(extractJsonObject(callModel(prompt, runtime)))
        val keywords = json.optJSONArray("keywords") ?: return emptyList()
        return buildList {
            repeat(keywords.length()) { index ->
                keywords.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct().take(10)
    }

    suspend fun rerank(
        query: String,
        candidates: List<ReadingSearchHit>,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): List<Long> {
        if (candidates.isEmpty()) return emptyList()
        val candidateText = candidates.take(20).joinToString("\n") { candidate ->
            val excerpt = candidate.text.replace('\n', ' ').take(420)
            "#${candidate.id} 第${candidate.chapterIndex + 1}章 ${candidate.chapterTitle}: $excerpt"
        }
        val prompt =
            """
            你正在对小说已读正文的本地检索结果排序。不得使用候选之外的剧情知识，
            不得联网。选择最能回答用户问题的结果，输出严格 JSON：
            {"relevant":[结果ID]}
            最多返回 8 个 ID，按相关性从高到低排列。

            用户问题：
            $query

            候选：
            $candidateText
            """.trimIndent()
        val json = JSONObject(extractJsonObject(callModel(prompt, runtime)))
        val relevant = json.optJSONArray("relevant") ?: return emptyList()
        val allowed = candidates.mapTo(hashSetOf()) { it.id }
        return buildList {
            repeat(relevant.length()) { index ->
                val id = relevant.optLong(index, Long.MIN_VALUE)
                if (id in allowed && id !in this) add(id)
            }
        }.take(8)
    }

    private suspend fun callModel(
        prompt: String,
        runtime: ToolExecutionManager.ToolRuntimeContext?,
    ): String = mutex.withLock {
        val host = EnhancedAIService.getChatInstance(appContext, INTERNAL_CHAT_ID)
        val config = host.getModelConfigForFunction(
            functionType = FunctionType.CHAT,
            chatModelConfigIdOverride = runtime?.parentModelConfigId,
            chatModelIndexOverride = runtime?.parentModelIndex,
        )
        val parameters = ModelConfigManager(appContext).getModelParametersForConfig(config.id)
        val service = host.getAIServiceForFunction(
            functionType = FunctionType.CHAT,
            chatModelConfigIdOverride = runtime?.parentModelConfigId,
            chatModelIndexOverride = runtime?.parentModelIndex,
        )
        val output = StringBuilder()
        service.sendMessage(
            context = appContext,
            chatHistory = listOf(
                PromptTurn(
                    kind = PromptTurnKind.SYSTEM,
                    content = "你是本地小说检索的结构化辅助步骤，只输出请求的 JSON。",
                ),
                PromptTurn(kind = PromptTurnKind.USER, content = prompt),
            ),
            modelParameters = parameters,
            enableThinking = false,
            stream = false,
            availableTools = emptyList(),
            preserveThinkInHistory = false,
            enableRetry = false,
            statsCategory = TokenStatCategory.OTHER,
        ).collect { chunk -> output.append(chunk) }
        output.toString()
    }

    private fun extractJsonObject(value: String): String {
        val start = value.indexOf('{')
        val end = value.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型未返回 JSON 对象" }
        return value.substring(start, end + 1)
    }

    private companion object {
        const val INTERNAL_CHAT_ID = "__reading_companion_internal__"
    }
}

package com.ai.assistance.operit.features.search

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * The shape both account search packages return, so a model reads Codex and Grok results the same
 * way. Only what the service sent back is filled in: no made-up snippets, dates or links.
 */
internal object SearchResults {
    /** The queries from `queries` (an array) or `query` (one string). */
    fun queries(params: JSONObject, max: Int, maxChars: Int): List<String> {
        val list = params.optJSONArray("queries")?.let { array ->
            (0 until array.length()).map { index ->
                val value = array.opt(index)
                require(value is String && value.isNotBlank()) { "queries 里每一项都要是非空的搜索词" }
                value.trim()
            }
        } ?: params.optString("query").trim().takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
        require(list.isNotEmpty()) { "请提供搜索词 query，或用 queries 一次传多个" }
        require(list.size <= max) { "一次最多 $max 个搜索词" }
        list.forEach { require(it.length <= maxChars) { "搜索词太长（最多 $maxChars 字）" } }
        return list.distinct()
    }

    fun entry(query: String, summary: String, citations: Map<String, String?>, searched: List<String>): JSONObject {
        val results = JSONArray()
        citations.forEach { (url, title) ->
            results.put(JSONObject()
                .put("title", title ?: JSONObject.NULL)
                .put("url", url)
                .put("snippet", JSONObject.NULL)
                .put("published_at", JSONObject.NULL))
        }
        return JSONObject()
            .put("query", query)
            .put("summary", summary)
            .put("results", results)
            .put("citations", JSONArray(citations.keys.toList()))
            .put("searched_queries", JSONArray(searched.distinct()))
    }

    fun failed(query: String, message: String): JSONObject =
        JSONObject().put("query", query).put("error", message.take(500))

    fun wrap(provider: String, model: String, searches: List<JSONObject>): JSONObject {
        val ok = searches.count { !it.has("error") }
        return JSONObject()
            .put("provider", provider)
            .put("model", model)
            .put("searched_at", Instant.now().toString())
            .put("success", ok > 0)
            .put("searches", JSONArray(searches))
    }

    /** `url_citation` annotations into url -> title (null when the service gave none). */
    fun annotations(list: JSONArray?, into: MutableMap<String, String?>) {
        list ?: return
        for (index in 0 until list.length()) {
            val annotation = list.optJSONObject(index) ?: continue
            if (annotation.optString("type") != "url_citation") continue
            val url = annotation.optString("url").takeIf { it.startsWith("http") } ?: continue
            val title = annotation.optString("title").takeIf { it.isNotBlank() }
            if (into[url] == null) into[url] = title
        }
    }

    fun httpFailure(service: String, status: Int, detail: String?): String {
        val hint = when (status) {
            401 -> "$service 登录已失效，请重新登录"
            403 -> "这个 $service 账号没有搜索权限"
            404, 405 -> "$service 不支持这个搜索请求"
            429 -> "$service 搜索额度用完或请求太频繁，请稍后再试"
            400 -> "$service 拒绝了这次请求，可能是参数不对"
            in 500..599 -> "$service 服务器出错，请稍后再试"
            else -> "$service 搜索失败"
        }
        return "$hint（HTTP $status）${detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}"
    }

    /** The server's own message, short and with no token in it. */
    fun errorText(body: String): String {
        val message = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("error").takeIf { it.isNotBlank() }
                ?: json.optString("message")
        } catch (_: Exception) { body }
        return message.replace(Regex("""Bearer\s+\S+"""), "Bearer ***").take(300)
    }
}

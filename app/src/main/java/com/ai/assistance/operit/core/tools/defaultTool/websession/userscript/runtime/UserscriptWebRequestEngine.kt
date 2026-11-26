package com.ai.assistance.operit.core.tools.defaultTool.websession.userscript.runtime

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

internal data class UserscriptWebRequestAction(
    val cancel: Boolean = false,
    val redirectUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String? = null
) {
    fun requiresInterception(): Boolean =
        cancel || !redirectUrl.isNullOrBlank() || requestHeaders.isNotEmpty() || responseHeaders.isNotEmpty() || responseBody != null
}

internal data class UserscriptWebRequestRule(
    val matchers: List<String>,
    val types: Set<String>,
    val action: UserscriptWebRequestAction
)

internal data class UserscriptWebRequestRegistration(
    val registrationId: String,
    val scriptId: Long,
    val sessionId: String,
    val rules: List<UserscriptWebRequestRule>,
    val source: String,
    val order: Long,
    /**
     * The addresses the registering document may see: rules the page itself added act on these
     * only, and only these are told to it. Rules the script declared act on the whole tab.
     */
    val reaches: (String) -> Boolean = { true },
    /** The main-frame document told of matches; null when none is (a frame's rules). */
    val document: String? = null,
    /** The document that added the rules; they go with it. */
    val owner: String = "",
    /** Whether [owner] still holds the script as it was when the rules were added. */
    val alive: () -> Boolean = { true }
)

internal data class UserscriptWebRequestMatch(
    val registrationId: String,
    val scriptId: Long,
    val action: UserscriptWebRequestAction,
    val source: String,
    /** Where the match is told; null when it is not. */
    val document: String?,
    /**
     * Whether a request this match changes may go to an address, hop by hop: for a page's own
     * rules, while their document still holds the script and only where it could send it itself.
     */
    val permits: (String) -> Boolean = { true }
)

internal data class UserscriptWebRequestResolution(
    val matches: List<UserscriptWebRequestMatch> = emptyList(),
    val mergedAction: UserscriptWebRequestAction = UserscriptWebRequestAction()
)

internal class UserscriptWebRequestEngine {
    private val registrations = ConcurrentHashMap<String, UserscriptWebRequestRegistration>()
    private val registrationOrder = AtomicLong(0L)

    /** Gets the session whose rules were added or removed. Any thread. */
    @Volatile var onRulesChanged: ((sessionId: String) -> Unit)? = null

    /** Whether the tab's own page requests are rewritten: only rules scripts declared do that. */
    fun hasRules(sessionId: String): Boolean =
        registrations.values.any { it.sessionId == sessionId && it.source == "metadata" }

    fun register(
        scriptId: Long,
        sessionId: String,
        rulesJson: String,
        source: String,
        reaches: (String) -> Boolean = { true },
        document: String? = null,
        owner: String = "",
        alive: () -> Boolean = { true }
    ): String {
        val rules = parseRules(rulesJson)
        val registrationId = "wr_" + UUID.randomUUID().toString().replace("-", "")
        // A script's declared rules are registered once per document: a new registration replaces the last.
        if (source == "metadata") {
            registrations.entries.removeIf {
                it.value.sessionId == sessionId && it.value.scriptId == scriptId && it.value.source == "metadata" &&
                    it.value.owner == owner
            }
        }
        registrations[registrationId] =
            UserscriptWebRequestRegistration(
                registrationId = registrationId,
                scriptId = scriptId,
                sessionId = sessionId,
                rules = rules,
                source = source,
                order = registrationOrder.incrementAndGet(),
                reaches = reaches,
                document = document,
                owner = owner,
                alive = alive
            )
        onRulesChanged?.invoke(sessionId)
        return registrationId
    }

    fun unregister(registrationId: String): Boolean {
        val removed = registrations.remove(registrationId) ?: return false
        onRulesChanged?.invoke(removed.sessionId)
        return true
    }

    fun registration(registrationId: String): UserscriptWebRequestRegistration? = registrations[registrationId]

    /** Drops the rules of scripts no longer installed and enabled. */
    fun retainScripts(scriptIds: Set<Long>) {
        val changed = registrations.values.filter { it.scriptId !in scriptIds }.map { it.sessionId }.toSet()
        if (changed.isEmpty()) return
        registrations.entries.removeIf { it.value.scriptId !in scriptIds }
        changed.forEach { onRulesChanged?.invoke(it) }
    }

    fun clearSession(sessionId: String) {
        if (registrations.entries.removeIf { it.value.sessionId == sessionId }) onRulesChanged?.invoke(sessionId)
    }

    /** Drops the rules [owner] added in [sessionId], once it is gone. */
    fun clearDocument(sessionId: String, owner: String) {
        if (registrations.entries.removeIf { it.value.sessionId == sessionId && it.value.owner == owner }) {
            onRulesChanged?.invoke(sessionId)
        }
    }

    /**
     * The rules for a request of [sessionId]. Rules a script declared act on every request of the
     * tab. Rules a page added act only on requests of that very document, [requester], made
     * through the script (the browser does not say which frame a page request comes from).
     */
    fun resolve(
        sessionId: String,
        url: String,
        requestType: String,
        requester: String? = null
    ): UserscriptWebRequestResolution {
        val normalizedType = requestType.trim().lowercase(Locale.ROOT).ifBlank { "other" }
        val matches =
            registrations.values
                .filter { it.sessionId == sessionId && (it.source == "metadata" || (requester != null && it.owner == requester)) }
                .sortedBy { it.order }
                .flatMap { registration ->
                    val matched = registration.rules.filter { rule ->
                        matchesType(rule, normalizedType) && matchesUrl(rule, url)
                    }
                    // Checked only for rules that match: most requests match none.
                    if (matched.isEmpty() || !registration.alive()) return@flatMap emptyList()
                    val visible = registration.reaches(url)
                    if (registration.source != "metadata" && !visible) return@flatMap emptyList()
                    val permits: (String) -> Boolean =
                        if (registration.source == "metadata") { _ -> true } else { hop -> registration.alive() && registration.reaches(hop) }
                    matched.map { rule ->
                        UserscriptWebRequestMatch(
                            registrationId = registration.registrationId,
                            scriptId = registration.scriptId,
                            action = rule.action,
                            source = registration.source,
                            document = registration.document?.takeIf { visible },
                            permits = permits
                        )
                    }
                }

        if (matches.isEmpty()) {
            return UserscriptWebRequestResolution()
        }

        val mergedAction =
            matches.fold(UserscriptWebRequestAction()) { acc, match ->
                UserscriptWebRequestAction(
                    cancel = acc.cancel || match.action.cancel,
                    redirectUrl = match.action.redirectUrl ?: acc.redirectUrl,
                    requestHeaders = acc.requestHeaders + match.action.requestHeaders,
                    responseHeaders = acc.responseHeaders + match.action.responseHeaders,
                    responseBody = match.action.responseBody ?: acc.responseBody
                )
            }

        return UserscriptWebRequestResolution(matches = matches, mergedAction = mergedAction)
    }

    private fun parseRules(rulesJson: String): List<UserscriptWebRequestRule> {
        val trimmed = rulesJson.trim()
        if (trimmed.isBlank()) {
            return emptyList()
        }
        val array =
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONArray().put(JSONObject(trimmed))
                else -> JSONArray().put(JSONObject().put("selector", trimmed))
            }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.opt(index)
                when (item) {
                    is JSONObject -> parseRuleObject(item)?.let(::add)
                    is String -> add(UserscriptWebRequestRule(matchers = listOf(item), types = emptySet(), action = UserscriptWebRequestAction()))
                }
            }
        }
    }

    private fun parseRuleObject(raw: JSONObject): UserscriptWebRequestRule? {
        val selectors = mutableListOf<String>()
        listOf("selector", "match", "url").forEach { key ->
            raw.optString(key).trim().takeIf { it.isNotBlank() }?.let(selectors::add)
        }
        raw.optJSONArray("urls")?.let { array ->
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf { it.isNotBlank() }?.let(selectors::add)
            }
        }
        val actionJson = raw.optJSONObject("action") ?: raw
        val requestHeaders = actionJson.optJSONObject("requestHeaders").toStringMap()
        val responseHeaders = actionJson.optJSONObject("responseHeaders").toStringMap()
        val types =
            buildSet {
                raw.optString("type").trim().takeIf { it.isNotBlank() }?.let { add(it.lowercase(Locale.ROOT)) }
                raw.optJSONArray("types")?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotBlank() }?.let {
                            add(it.lowercase(Locale.ROOT))
                        }
                    }
                }
            }
        val action =
            UserscriptWebRequestAction(
                cancel = actionJson.optBoolean("cancel", false),
                redirectUrl =
                    actionJson.optString("redirectUrl").ifBlank {
                        actionJson.optString("redirect").ifBlank { null }
                    },
                requestHeaders = requestHeaders,
                responseHeaders = responseHeaders,
                responseBody =
                    actionJson.optString("responseBody").ifBlank {
                        actionJson.optString("body").ifBlank { null }
                    }
            )
        val finalSelectors = selectors.ifEmpty { listOf("*") }
        return UserscriptWebRequestRule(
            matchers = finalSelectors.distinct(),
            types = types,
            action = action
        )
    }

    private fun matchesType(
        rule: UserscriptWebRequestRule,
        requestType: String
    ): Boolean = rule.types.isEmpty() || requestType in rule.types

    private fun matchesUrl(
        rule: UserscriptWebRequestRule,
        url: String
    ): Boolean =
        rule.matchers.any { matcher ->
            val trimmed = matcher.trim()
            when {
                trimmed == "*" -> true
                trimmed.startsWith("/") && trimmed.endsWith("/") && trimmed.length > 2 ->
                    runCatching { Regex(trimmed.removePrefix("/").removeSuffix("/"), RegexOption.IGNORE_CASE).containsMatchIn(url) }
                        .getOrDefault(false)
                trimmed.contains("*") ->
                    globToRegex(trimmed).matches(url)
                else -> url.contains(trimmed, ignoreCase = true)
            }
        }

    private fun globToRegex(pattern: String): Regex {
        val builder = StringBuilder("^")
        pattern.forEach { ch ->
            when (ch) {
                '*' -> builder.append(".*")
                '.', '?', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|', '\\' ->
                    builder.append('\\').append(ch)
                else -> builder.append(ch)
            }
        }
        builder.append('$')
        return Regex(builder.toString(), RegexOption.IGNORE_CASE)
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) {
            return emptyMap()
        }
        return buildMap {
            keys().forEach { key ->
                put(key, optString(key))
            }
        }
    }
}

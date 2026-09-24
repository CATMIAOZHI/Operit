package com.ai.assistance.operit.data.api

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Claude Code's OAuth surface (Claude Pro/Max subscriptions).
 *
 * Subscription credentials are only issued to, and only accepted from, clients shaped like Claude
 * Code: the same public client id, the same scopes, an identity block that has to be the first
 * system block of every Messages request, and tool names namespaced behind [TOOL_PREFIX]. The
 * constants and request shapes mirror the working implementation in the opencodex reference repo
 * (`src/oauth/anthropic.ts`, `src/adapters/anthropic.ts`, `src/adapters/client-fingerprint.ts`).
 *
 * Everything here is pure: URLs, JSON bodies and name mapping are built without I/O so the wire
 * contract can be unit tested.
 */
object ClaudeOAuthProtocol {
    /** Claude Code's public OAuth client id (not a secret: it ships inside the CLI). */
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val AUTHORIZE_ENDPOINT = "https://claude.ai/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://api.anthropic.com/v1/oauth/token"
    const val USAGE_ENDPOINT = "https://api.anthropic.com/api/oauth/usage"
    const val CALLBACK_PORT = 54545

    /**
     * The registered redirect is `localhost`, not `127.0.0.1`: the authorization server matches the
     * redirect URI as a string, and only the `localhost` spelling is registered for this client.
     */
    const val CALLBACK_HOST = "localhost"

    const val SCOPE = "org:create_api_key user:profile user:inference"

    /** Betas every OAuth Messages request carries. */
    const val OAUTH_BETA = "claude-code-20250219,oauth-2025-04-20"

    /** The wider beta list the OAuth usage probe is known to accept. */
    const val OAUTH_USAGE_BETA =
        "$OAUTH_BETA,interleaved-thinking-2025-05-14,context-management-2025-06-27," +
            "prompt-caching-scope-2026-01-05"

    /** Claude rejects an OAuth request whose first system block is not this identity. */
    const val SYSTEM_INSTRUCTION = "You are a Claude agent, built on Anthropic's Claude Agent SDK."

    /**
     * OAuth requests only accept Claude Code's own tool names, so package/tool names go out
     * namespaced and come back stripped. Anthropic's built-in tools are already accepted as-is.
     */
    const val TOOL_PREFIX = "custom_"

    const val USER_AGENT = "@anthropic-ai/sdk/0.74.0"

    /** The usage probe is a Claude Code endpoint, and the CLI identifies itself there. */
    const val USAGE_USER_AGENT = "claude-cli/2.1.63 (external, cli)"

    /** Headers the reference implementation sends on the usage probe, beyond auth and beta. */
    val USAGE_EXTRA_HEADERS: Map<String, String> =
        linkedMapOf(
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "User-Agent" to USAGE_USER_AGENT,
        )

    /**
     * Claude Code ships its model list instead of discovering one, and a subscription account has no
     * key-scoped `/v1/models` guarantee. The live list is preferred; this is the offline fallback so
     * the picker never dead-ends. Newer models reach a signed-in account through the live fetch.
     */
    val SUBSCRIPTION_MODELS: List<String> =
        listOf(
            "claude-fable-5",
            "claude-sonnet-5",
            "claude-opus-5",
            "claude-opus-4-8",
            "claude-opus-4-7",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-haiku-4-5",
        )

    private val BUILT_IN_TOOL_NAMES = setOf("web_search", "code_execution", "text_editor", "computer")

    /**
     * First-party header fingerprint. A valid subscription token sent with a bare header set is not
     * the shape the token was minted for. Process-derived `X-Stainless-*` values (`arch`, `os`,
     * `runtime-version`) are deliberately left out: they vary per machine for the real CLI too.
     */
    val FINGERPRINT_HEADERS: Map<String, String> =
        linkedMapOf(
            "X-App" to "cli",
            "X-Stainless-Retry-Count" to "0",
            "X-Stainless-Lang" to "js",
            "X-Stainless-Runtime" to "node",
            "X-Stainless-Timeout" to "600",
            "X-Stainless-Package-Version" to "0.74.0",
        )

    fun buildAuthorizationUrl(redirectUri: String, challenge: String, state: String): String {
        val query =
            listOf(
                "code" to "true",
                "client_id" to CLIENT_ID,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to SCOPE,
                "code_challenge" to challenge,
                "code_challenge_method" to "S256",
                "state" to state,
            ).joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
        return "$AUTHORIZE_ENDPOINT?$query"
    }

    /**
     * `application/x-www-form-urlencoded` escaping, which is what the reference implementation's
     * `URLSearchParams` produces as well: spaces become `+` inside the query string.
     */
    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    /**
     * The token endpoint wants JSON, and it wants the same `state` the authorize step was given.
     * Claude's own callback can deliver the code as `code#state`, which the caller splits first.
     */
    fun authorizationCodeBody(
        code: String,
        state: String,
        redirectUri: String,
        verifier: String,
    ): JSONObject = JSONObject()
        .put("grant_type", "authorization_code")
        .put("client_id", CLIENT_ID)
        .put("code", code)
        .put("state", state)
        .put("redirect_uri", redirectUri)
        .put("code_verifier", verifier)

    fun refreshBody(refreshToken: String): JSONObject = JSONObject()
        .put("grant_type", "refresh_token")
        .put("client_id", CLIENT_ID)
        .put("refresh_token", refreshToken)

    /** A Claude OAuth callback may append the state to the code as `code#state`. */
    fun splitAuthorizationCode(rawCode: String, fallbackState: String): Pair<String, String> {
        val separator = rawCode.indexOf('#')
        if (separator < 0) return rawCode to fallbackState
        val code = rawCode.substring(0, separator)
        val state = rawCode.substring(separator + 1).takeIf { it.isNotBlank() } ?: fallbackState
        return code to state
    }

    /** Claude Code reports the subscribed account inside the token response. */
    fun accountEmail(tokenJson: JSONObject): String =
        tokenJson.optJSONObject("account")?.optString("email_address", "").orEmpty().trim()

    fun wireToolName(name: String): String {
        if (name.lowercase() in BUILT_IN_TOOL_NAMES) return name
        if (name.startsWith(TOOL_PREFIX)) return name
        return TOOL_PREFIX + name
    }

    fun modelToolName(name: String): String =
        if (name.startsWith(TOOL_PREFIX)) name.removePrefix(TOOL_PREFIX) else name

    /**
     * A stable, credential-derived session id in the shape Claude Code sends. The token itself
     * never leaves this function; only its digest does.
     */
    fun sessionId(accessToken: String): String {
        val seed = accessToken.ifBlank { "operit-anon" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("claude-code-session:$seed".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val variant = ((digest[16].digitToInt(16) and 0x3) or 0x8).toString(16)
        return buildString {
            append(digest, 0, 8).append('-')
            append(digest, 8, 12).append("-4")
            append(digest, 13, 16).append('-')
            append(variant)
            append(digest, 17, 20).append('-')
            append(digest, 20, 32)
        }
    }
}

package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A conversation identity, inherited by retries and tool continuations, not shared mutable state.
 *
 * [workspacePath] is the workspace the conversation is bound to, when it has one. Providers that
 * report workspace metadata upstream read it here; a conversation without a bound workspace reports
 * an empty working directory.
 */
internal class OpenCodeSessionContext(
    val sessionId: String,
    val workspacePath: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OpenCodeSessionContext>
}

/**
 * The conversation identity a stable scope stands for, in the shape every other identity has.
 *
 * A scope names a conversation that outlives the chat a single turn runs in. The automatic
 * permission review is why this exists: each review of one conversation may run in a reviewer chat
 * of its own, but every one of them is about the same conversation, and a provider only reuses a
 * cached prompt prefix while the identity it was asked under stays the same. The reference
 * implementation scopes its reviewer the same way, keying its cache by `guardian:<parent thread>`
 * instead of by the reviewer's own session.
 *
 * The value is hashed into a UUID so it stays the shape a conversation identity has everywhere else
 * and cannot be confused with a chat id.
 */
internal fun providerSessionIdForScope(scope: String): String =
    UUID.nameUUIDFromBytes("operit-provider-session:$scope".toByteArray(Charsets.UTF_8)).toString()

/** Used by direct provider calls (for example connection tests) without a chat context. */
internal class OpenCodeGoHeaders {
    private val fallbackSessionId = UUID.randomUUID().toString()

    suspend fun applyTo(builder: Request.Builder) {
        val request = builder.build()
        val url = request.url
        if (url.scheme != "https" || url.host != "opencode.ai" ||
            !(url.encodedPath == "/zen/go" || url.encodedPath.startsWith("/zen/go/"))) return

        if (request.header("x-opencode-session").isNullOrBlank()) {
            builder.header("x-opencode-session",
                currentCoroutineContext()[OpenCodeSessionContext]?.sessionId ?: fallbackSessionId)
        }
        if (request.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", "Operit/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; Android)")
        }
    }
}

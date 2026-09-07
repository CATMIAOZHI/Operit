package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.Request
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A conversation identity, inherited by retries and tool continuations, not shared mutable state. */
internal class OpenCodeSessionContext(val sessionId: String) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OpenCodeSessionContext>
}

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

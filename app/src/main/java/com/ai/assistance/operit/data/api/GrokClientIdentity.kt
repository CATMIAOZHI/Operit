package com.ai.assistance.operit.data.api

import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Grok Build CLI identity sent to xAI. cli-chat-proxy rejects clients below its version gate,
 * so the advertised version follows the official stable release pointer and never drops below
 * the pinned floor. The pointer is read in the background so a request never waits for it.
 */
object GrokClientIdentity {
    const val PINNED_VERSION = "1.0.70"
    private const val TAG = "GrokClientIdentity"
    private const val CLIENT_IDENTIFIER = "grok-pager"
    private const val CLIENT_MODE = "interactive"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private val POINTERS = listOf(
        "https://x.ai/cli/stable",
        "https://storage.googleapis.com/grok-build-public-artifacts/cli/stable",
    )
    private val VERSION = Regex("""\d+\.\d+\.\d+""")
    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = AtomicBoolean(false)
    @Volatile private var version = PINNED_VERSION
    @Volatile private var nextCheckAt = 0L

    /** Headers for a request to xAI; the token-auth pair is only for cli-chat-proxy. */
    fun headers(viaProxy: Boolean = true): Map<String, String> {
        refreshIfDue()
        return headersFor(version, viaProxy)
    }

    internal fun headersFor(version: String, viaProxy: Boolean = true): Map<String, String> = buildMap {
        put("x-grok-client-version", version)
        put("x-grok-client-identifier", CLIENT_IDENTIFIER)
        put("User-Agent", "grok-pager/$version grok-shell/$version (linux; ${arch()})")
        if (viaProxy) {
            put("x-xai-token-auth", "xai-grok-cli")
            put("x-authenticateresponse", "authenticate-response")
            put("x-grok-client-mode", CLIENT_MODE)
        }
    }

    /** The newer of two release versions; anything that is not a plain release keeps [current]. */
    internal fun newer(current: String, candidate: String?): String {
        val value = candidate?.trim().orEmpty()
        if (!VERSION.matches(value)) return current
        val a = current.split('.').map { it.toInt() }
        val b = value.split('.').map { it.toInt() }
        for (i in 0 until 3) if (a[i] != b[i]) return if (b[i] > a[i]) value else current
        return current
    }

    private fun refreshIfDue() {
        if (System.currentTimeMillis() < nextCheckAt) return
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                val latest = POINTERS.firstNotNullOfOrNull(::fetch)
                if (latest != null) version = newer(version, latest)
                nextCheckAt = System.currentTimeMillis() + if (latest != null) REFRESH_MS else RETRY_MS
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun fetch(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            response.body?.string()?.trim()?.takeIf { response.isSuccessful && VERSION.matches(it) }
        }
    } catch (error: Exception) {
        AppLogger.w(TAG, "Grok CLI version pointer unavailable: ${error.javaClass.simpleName}")
        null
    }

    private fun arch(): String = when (val arch = System.getProperty("os.arch").orEmpty().lowercase()) {
        "arm64", "aarch64" -> "aarch64"
        "amd64", "x86_64" -> "x86_64"
        "" -> "aarch64"
        else -> arch
    }
}

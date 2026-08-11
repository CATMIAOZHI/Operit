package com.ai.assistance.operit.core.workflow

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WorkflowAuthTokenManager(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )
    private val codec: WorkflowAuthTokenCodec by lazy {
        WorkflowAuthTokenCodec(loadOrCreateSigningSecret())
    }

    fun newAuthToken(): String = codec.newToken()

    fun isAuthenticAuthToken(token: String?): Boolean = codec.isAuthentic(token)

    private fun loadOrCreateSigningSecret(): ByteArray = synchronized(secretLock) {
        preferences.getString(SIGNING_SECRET_KEY, null)
            ?.let(::decodeSecret)
            ?.takeIf { it.size == SIGNING_SECRET_BYTES }
            ?.let { return@synchronized it }

        val secret = ByteArray(SIGNING_SECRET_BYTES).also(SecureRandom()::nextBytes)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
        check(preferences.edit().putString(SIGNING_SECRET_KEY, encoded).commit()) {
            "Unable to persist workflow trigger signing secret"
        }
        secret
    }

    private fun decodeSecret(encoded: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull()

    private companion object {
        const val PREFERENCES_NAME = "workflow_auth_tokens"
        const val SIGNING_SECRET_KEY = "installation_signing_secret_v1"
        const val SIGNING_SECRET_BYTES = 32
        val secretLock = Any()
    }
}

internal class WorkflowAuthTokenCodec(
    secret: ByteArray,
    private val randomBytes: (Int) -> ByteArray = { size ->
        ByteArray(size).also(SecureRandom()::nextBytes)
    }
) {
    private val signingKey = SecretKeySpec(secret.copyOf(), HMAC_ALGORITHM)

    fun newToken(): String {
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(NONCE_BYTES))
        check(nonce.length == ENCODED_NONCE_LENGTH) { "Unexpected workflow token nonce length" }
        val payload = "$TOKEN_PREFIX$nonce"
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(payload))
        return "${payload}_$signature"
    }

    fun isAuthentic(token: String?): Boolean {
        if (!WorkflowIntentSecurity.isValidAuthToken(token) || token == null) return false
        if (token.length != TOKEN_LENGTH || !token.startsWith(TOKEN_PREFIX)) return false
        if (token[SIGNATURE_SEPARATOR_INDEX] != '_') return false

        val payload = token.substring(0, SIGNATURE_SEPARATOR_INDEX)
        val suppliedSignature = runCatching {
            Base64.getUrlDecoder().decode(token.substring(SIGNATURE_SEPARATOR_INDEX + 1))
        }.getOrNull() ?: return false
        return MessageDigest.isEqual(sign(payload), suppliedSignature)
    }

    private fun sign(payload: String): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).apply { init(signingKey) }
            .doFinal(payload.toByteArray(Charsets.UTF_8))

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val TOKEN_PREFIX = "v1_"
        const val NONCE_BYTES = 18
        const val ENCODED_NONCE_LENGTH = 24
        const val ENCODED_SIGNATURE_LENGTH = 43
        const val SIGNATURE_SEPARATOR_INDEX = TOKEN_PREFIX.length + ENCODED_NONCE_LENGTH
        const val TOKEN_LENGTH = SIGNATURE_SEPARATOR_INDEX + 1 + ENCODED_SIGNATURE_LENGTH
    }
}

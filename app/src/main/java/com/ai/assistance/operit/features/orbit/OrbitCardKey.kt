package com.ai.assistance.operit.features.orbit

import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The key that signs cards whose fields decide where a secret goes (see OrbitChatCards.vault).
 * It lives in the Android key store, so it survives restarts and never leaves the device; where
 * there is no key store (unit tests) a key for this process stands in.
 */
internal object OrbitCardKey {
    private const val ALIAS = "orbit_card_sign"

    @Volatile private var cached: SecretKey? = null

    fun get(): SecretKey = cached ?: synchronized(this) {
        cached ?: (runCatching { fromKeyStore() }.getOrNull() ?: KeyGenerator.getInstance("HmacSHA256").generateKey())
            .also { cached = it }
    }

    private fun fromKeyStore(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(ALIAS, android.security.keystore.KeyProperties.PURPOSE_SIGN).build(),
        )
        return generator.generateKey()
    }
}

package com.ai.assistance.operit.features.orbit

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Host-only ciphertext; intentionally outside both Android and Operit's files/shared_prefs backups. */
internal class OrbitSecretFile(
    private val file: File,
    private val maxPlainBytes: Int = 1_900_000,
    private val key: () -> SecretKey
) {
    fun delete() { Files.deleteIfExists(file.toPath()) }
    fun read(): String? {
        if (!file.exists()) return null
        require(file.length() in 30L..(maxPlainBytes.toLong() + 29)) { "Invalid protected store" }
        val bytes = file.readBytes()
        require(bytes[0] == 1.toByte()) { "Invalid protected store" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(file.name.toByteArray())
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
    }

    fun write(text: String) {
        require(text.toByteArray().size <= maxPlainBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(file.name.toByteArray())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val temporary = File.createTempFile(".secret-", ".tmp", file.parentFile)
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object {
        private const val ALIAS = "operit.orbit.host.secrets.v1"
        @Synchronized private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        fun forBrowserDownloads(context: Context): OrbitSecretFile =
            // Existing download journals had no connector-sized limit; preserve their capacity.
            OrbitSecretFile(File(context.noBackupFilesDir, "browser-downloads-v1.bin"),
                maxPlainBytes = Int.MAX_VALUE - 29, key = ::androidKey)
    }
}

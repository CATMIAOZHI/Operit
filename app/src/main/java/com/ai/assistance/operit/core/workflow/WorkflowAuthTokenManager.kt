package com.ai.assistance.operit.core.workflow

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal fun loadOrCreateWorkflowSigningSecret(
    readExisting: () -> ByteArray?,
    writeNew: (ByteArray) -> Unit,
    randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
): ByteArray {
    readExisting()?.takeIf { it.size == WORKFLOW_SIGNING_SECRET_BYTES }?.let { return it }
    return randomBytes(WORKFLOW_SIGNING_SECRET_BYTES).also { secret ->
        require(secret.size == WORKFLOW_SIGNING_SECRET_BYTES)
        writeNew(secret)
    }
}

private const val WORKFLOW_SIGNING_SECRET_BYTES = 32
private const val WORKFLOW_SIGNING_SECRET_FILE_NAME = "workflow_auth_signing_secret_v1"
private const val WORKFLOW_AUTH_RESTORE_GENERATION_FILE_NAME = "workflow_auth_restore_generation_v1"
private val workflowAuthStateLock = Any()

internal fun workflowSigningSecretFile(noBackupFilesDir: File): File =
    File(noBackupFilesDir, WORKFLOW_SIGNING_SECRET_FILE_NAME)

internal fun workflowAuthRestoreGenerationFile(noBackupFilesDir: File): File =
    File(noBackupFilesDir, WORKFLOW_AUTH_RESTORE_GENERATION_FILE_NAME)

internal fun loadWorkflowAuthRestoreGeneration(readExisting: () -> ByteArray): Long {
    val encoded = try {
        readExisting()
    } catch (_: FileNotFoundException) {
        return 0L
    }
    return encoded.toString(Charsets.UTF_8).toLongOrNull()?.takeIf { it >= 0L }
        ?: throw IllegalStateException("Invalid workflow auth restore generation")
}

internal fun advanceWorkflowAuthRestoreGeneration(context: Context): Long =
    synchronized(workflowAuthStateLock) {
        val generationFile = AtomicFile(
            workflowAuthRestoreGenerationFile(context.applicationContext.noBackupFilesDir)
        )
        val current = loadWorkflowAuthRestoreGeneration {
            generationFile.readFully()
        }
        val next = Math.addExact(current, 1L)
        generationFile.baseFile.parentFile?.mkdirs()
        val output = generationFile.startWrite()
        try {
            output.write(next.toString().toByteArray(Charsets.UTF_8))
            generationFile.finishWrite(output)
        } catch (error: Throwable) {
            generationFile.failWrite(output)
            throw error
        }
        next
    }

class WorkflowAuthTokenManager(context: Context) {
    private val applicationContext = context.applicationContext
    private val secretFile = AtomicFile(
        workflowSigningSecretFile(applicationContext.noBackupFilesDir)
    )
    private val codec: WorkflowAuthTokenCodec by lazy {
        WorkflowAuthTokenCodec(
            secret = loadOrCreateSigningSecret(),
            restoreGeneration = loadRestoreGeneration(),
        )
    }

    fun newAuthToken(): String = codec.newToken()

    fun isAuthenticAuthToken(token: String?): Boolean {
        // Keep malformed external input on the allocation-free shape-check path. In particular,
        // do not initialize [codec], which may have to read/create the no-backup signing key.
        if (!WorkflowIntentSecurity.isValidAuthToken(token)) return false
        return codec.isAuthentic(token)
    }

    private fun loadOrCreateSigningSecret(): ByteArray = synchronized(workflowAuthStateLock) {
        cachedSigningSecret?.let { return@synchronized it.copyOf() }
        val secret = loadOrCreateWorkflowSigningSecret(
            readExisting = { runCatching { secretFile.readFully() }.getOrNull() },
            writeNew = { value ->
                secretFile.baseFile.parentFile?.mkdirs()
                val output = secretFile.startWrite()
                try {
                    output.write(value)
                    secretFile.finishWrite(output)
                } catch (error: Throwable) {
                    secretFile.failWrite(output)
                    throw error
                }
            },
        )

        // Never reuse a backup-eligible secret from an earlier implementation or a restored
        // snapshot. Internal workflow definitions are normalized against this no-backup key on
        // read, so restored external-trigger credentials rotate before they are shown or used.
        check(
            applicationContext.getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        ) {
            "Unable to remove legacy workflow trigger signing secret"
        }
        cachedSigningSecret = secret.copyOf()
        secret
    }

    private fun loadRestoreGeneration(): Long = synchronized(workflowAuthStateLock) {
        val generationFile = AtomicFile(
            workflowAuthRestoreGenerationFile(applicationContext.noBackupFilesDir)
        )
        loadWorkflowAuthRestoreGeneration {
            generationFile.readFully()
        }
    }

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "workflow_auth_tokens"
        @Volatile
        var cachedSigningSecret: ByteArray? = null
    }
}

internal class WorkflowAuthTokenCodec(
    secret: ByteArray,
    private val restoreGeneration: Long = 0L,
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

    private fun sign(payload: String): ByteArray {
        val generationBoundPayload =
            if (restoreGeneration == 0L) payload else "$restoreGeneration:$payload"
        return Mac.getInstance(HMAC_ALGORITHM).apply { init(signingKey) }
            .doFinal(generationBoundPayload.toByteArray(Charsets.UTF_8))
    }

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

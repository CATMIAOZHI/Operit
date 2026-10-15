package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.db.AppDatabase
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.model.OrbitActionEntity
import com.ai.assistance.operit.data.model.OrbitArtifactVersionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal class OrbitArtifactRepository(private val context: Context) {
    val dao = AppDatabase.getDatabase(context).orbitTaskDao()

    /** The host verifies bytes before reconciling a write interrupted before its index commit. */
    suspend fun verifySavedFile(orbitId: String, receipt: OrbitActionEntity) = withContext(Dispatchers.IO) {
        val task = checkNotNull(dao.task(receipt.taskId))
        check(task.orbitId == orbitId && receipt.state == "UNKNOWN")
        val versionId = java.util.UUID.nameUUIDFromBytes(receipt.id.toByteArray()).toString()
        val root = OrbitPreferences.getInstance(context).orbitDir(orbitId).canonicalFile
        val directory = File(root, "artifacts/${task.id}/$versionId").canonicalFile
        require(directory.toPath().startsWith(root.toPath()))
        val file = directory.listFiles().orEmpty().take(4).map { it.canonicalFile }.singleOrNull {
            it.parentFile == directory && it.isFile && it.length() <= 800_000 &&
                hash("${it.name}\u0000${hash(it.readBytes())}".toByteArray()) == receipt.operationHash
        } ?: error("Verified artifact not found. Confirm that it did not complete to permit a retry.")
        val bytes = file.readBytes()
        val digest = hash(bytes)
        check(hash("${file.name}\u0000$digest".toByteArray()) == receipt.operationHash) {
            "Saved content does not match this operation; it cannot be marked completed."
        }
        val original = task.sourceArtifactVersionId.takeIf { it.isNotEmpty() }?.let { dao.artifact(it) }
        val artifactId = original?.takeIf { it.name == file.name }?.artifactId
            ?: java.util.UUID.nameUUIDFromBytes("${task.id}:${file.name}".toByteArray()).toString()
        val now = System.currentTimeMillis()
        check(dao.verifyArtifact(OrbitArtifactVersionEntity(versionId, artifactId, orbitId, task.id,
            receipt.runId, receipt.id, file.name, "artifacts/${task.id}/$versionId/${file.name}",
            digest, bytes.size.toLong(), now), now))
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    suspend fun read(orbitId: String, versionId: String): String = withContext(Dispatchers.IO) {
        val version = checkNotNull(dao.artifact(versionId)) { "Artifact version not found" }
        check(version.orbitId == orbitId) { "Artifact belongs to another Orbit" }
        val root = OrbitPreferences.getInstance(context).orbitDir(orbitId).canonicalFile
        val file = File(root, version.relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "Artifact file is missing" }
        require(file.length() <= 800_000 && file.length() == version.byteCount) { "Artifact content changed outside Orbit" }
        val bytes = file.readBytes()
        check(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == version.sha256) {
            "Artifact content changed outside Orbit"
        }
        bytes.toString(Charsets.UTF_8)
    }
}

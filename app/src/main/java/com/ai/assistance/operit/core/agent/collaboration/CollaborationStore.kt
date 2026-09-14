package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Atomic private storage; decoding errors are surfaced rather than erasing existing agents. */
internal class CollaborationStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "subagent-v2.json"))
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun load(): CollaborationState {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            return CollaborationState()
        }
        return file.openRead().bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<CollaborationState>(it.readText()).also { state ->
                require(state.version == 1) { "Unsupported subagent v2 storage version" }
            }
        }
    }

    fun save(state: CollaborationState) {
        val bytes = json.encodeToString(state).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}

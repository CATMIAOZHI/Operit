package com.ai.assistance.operit.core.agent.collaboration

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal interface CollaborationFileAccess {
    fun exists(file: File): Boolean
    fun read(file: File): String
    fun write(file: File, text: String)
}

private object AndroidCollaborationFiles : CollaborationFileAccess {
    override fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
    override fun read(file: File) = AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    override fun write(file: File, text: String) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}

/** Immutable tree generations become visible together through one atomic manifest commit. */
internal class CollaborationStore(
    private val filesDir: File,
    private val access: CollaborationFileAccess = AndroidCollaborationFiles,
) {
    constructor(context: Context) : this(context.filesDir)

    companion object {
        const val DIRECTORY = "subagent-v2-store"
        const val LEGACY = "subagent-v2.json"
        private val locks = ConcurrentHashMap<String, Any>()

        /** Raw restore normally merges files. These alternative formats represent one dataset. */
        fun prepareRestore(payloadFiles: File, targetFiles: File) {
            val incomingStore = File(payloadFiles, "$DIRECTORY/manifest.json").isFile
            val incomingLegacy = File(payloadFiles, LEGACY).isFile ||
                File(payloadFiles, "$LEGACY.bak").isFile
            if (!incomingStore && !incomingLegacy) return // preserve merge semantics for older backups
            synchronized(locks.computeIfAbsent(File(targetFiles, DIRECTORY).absolutePath) { Any() }) {
                val oldStore = File(targetFiles, DIRECTORY)
                check(!oldStore.exists() || oldStore.deleteRecursively()) { "Cannot replace collaboration store" }
                listOf(LEGACY, "$LEGACY.bak", "$LEGACY.new").forEach { name ->
                    val file = File(targetFiles, name)
                    check(!file.exists() || file.delete()) { "Cannot replace legacy collaboration store" }
                }
            }
        }
    }

    /**
     * [trees] holds each tree's live agents, rewritten as they work. [archives] holds each tree's
     * archived agents in a file of their own, written only when that set changes, so a tree with a
     * long past does not rewrite it on every message.
     */
    @Serializable
    private data class Manifest(
        val version: Int = 1,
        val trees: Map<String, String> = emptyMap(),
        val archives: Map<String, String> = emptyMap(),
    ) {
        val files: Set<String> get() = trees.values.toSet() + archives.values
    }

    /** Version 2 has archives; an older app that only knows version 1 refuses it instead of dropping them. */
    private fun versionFor(archives: Map<String, String>) = if (archives.isEmpty()) 1 else 2

    private val directory = File(filesDir, DIRECTORY)
    private val manifestFile = File(directory, "manifest.json")
    private val legacy = File(filesDir, LEGACY)
    private val lock = locks.computeIfAbsent(directory.absolutePath) { Any() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private var committed = emptyMap<String, List<CollaborationAgent>>()
    private var committedArchives = emptyMap<String, List<CollaborationAgent>>()
    private var manifest = Manifest()
    private var loaded = false

    private fun treeFile(name: String): File {
        require(Regex("[a-f0-9-]{36}\\.json").matches(name)) { "Invalid collaboration tree filename" }
        return File(directory, name)
    }

    fun load(): CollaborationState = synchronized(lock) {
        val state = if (access.exists(manifestFile)) {
            manifest = json.decodeFromString<Manifest>(access.read(manifestFile))
            require(manifest.version in 1..2 && (manifest.version == 2 || manifest.archives.isEmpty())) {
                "Unsupported collaboration manifest version"
            }
            val live = manifest.trees.flatMap { (root, name) ->
                val tree = json.decodeFromString<CollaborationState>(access.read(treeFile(name)))
                require(tree.version == 1 && tree.agents.isNotEmpty() &&
                    tree.agents.all { it.rootChatId == root }) { "Invalid collaboration tree" }
                tree.agents
            }
            val archived = manifest.archives.flatMap { (root, name) ->
                val tree = json.decodeFromString<CollaborationState>(access.read(treeFile(name)))
                require(tree.version == 1 && tree.agents.all { it.rootChatId == root && it.archived }) {
                    "Invalid collaboration archive"
                }
                tree.agents
            }
            CollaborationState(agents = live + archived)
        } else if (access.exists(legacy)) {
            json.decodeFromString<CollaborationState>(access.read(legacy)).also {
                require(it.version == 1) { "Unsupported subagent v2 storage version" }
            }
        } else CollaborationState()
        committed = state.agents.filterNot { it.archived }.groupBy { it.rootChatId }
        committedArchives = state.agents.filter { it.archived }.groupBy { it.rootChatId }
        loaded = true
        state
    }

    fun save(state: CollaborationState) = synchronized(lock) {
        require(state.version == 1)
        if (!loaded) load()
        val trees = state.agents.filterNot { it.archived }.groupBy { it.rootChatId }
        val archives = state.agents.filter { it.archived }.groupBy { it.rootChatId }
        fun files(
            groups: Map<String, List<CollaborationAgent>>, previousFiles: Map<String, String>,
            previousAgents: Map<String, List<CollaborationAgent>>,
        ) = groups.mapValues { (root, agents) ->
            val previous = previousFiles[root]
            if (previous != null && agents == previousAgents[root]) previous else {
                val name = "${UUID.randomUUID()}.json"
                access.write(treeFile(name), json.encodeToString(CollaborationState(agents = agents)))
                name
            }
        }
        val nextArchives = files(archives, manifest.archives, committedArchives)
        val next = Manifest(
            version = versionFor(nextArchives),
            trees = files(trees, manifest.trees, committed),
            archives = nextArchives,
        )
        if (next != manifest || !access.exists(manifestFile)) {
            access.write(manifestFile, json.encodeToString(next))
        }
        // Nothing after the commit may turn a successful durable save into a reported failure.
        manifest = next
        committed = trees
        committedArchives = archives
        runCatching {
            val retained = next.files + "manifest.json"
            directory.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
            legacy.delete()
            File(legacy.path + ".bak").delete()
        }
        Unit
    }

    /** Copies a consistent snapshot before raw backup walks the rest of filesDir. */
    fun copyForBackup(destinationFiles: File) = synchronized(lock) {
        if (access.exists(manifestFile)) {
            val text = access.read(manifestFile)
            val current = json.decodeFromString<Manifest>(text)
            require(current.version in 1..2)
            val target = File(destinationFiles, DIRECTORY).apply { mkdirs() }
            current.files.forEach { name ->
                File(target, name).writeText(access.read(treeFile(name)))
            }
            File(target, "manifest.json").writeText(text)
        } else if (access.exists(legacy)) {
            destinationFiles.mkdirs()
            File(destinationFiles, LEGACY).writeText(access.read(legacy))
        }
    }
}

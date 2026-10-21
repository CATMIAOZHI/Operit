package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal fun orbitDescendants(roots: Collection<String>, parents: List<Pair<String, String?>>): Set<String> {
    val ids = roots.toMutableSet()
    val children = parents.groupBy({ it.second }, { it.first })
    val queue = java.util.ArrayDeque(ids)
    while (queue.isNotEmpty()) {
        children[queue.removeFirst()].orEmpty().forEach {
            if (ids.add(it)) queue.addLast(it)
        }
    }
    return ids
}

/** The host selects memory ownership. Model arguments and the active global space cannot override it. */
object OrbitMemoryScope {
    private val migrationMutex = Mutex()

    suspend fun owner(context: Context, chatId: String?): OrbitProfile? {
        val store = OrbitPreferences.getInstance(context)
        val settings = store.current()
        val dao = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context).chatDao()
        var cursor = chatId
        val visited = mutableSetOf<String>()
        while (cursor != null) {
            check(visited.add(cursor)) { "Invalid chat ancestry" }
            settings.ownerForChat(cursor)?.let { orbit ->
                check(!orbit.deleting) { "Orbit is being deleted" }
                // Persist inherited ownership so later parent removal cannot expose this chat.
                if (cursor != chatId && chatId != null) store.inheritChat(cursor, chatId)
                if (chatId != null) store.rememberChatOwner(chatId, orbit.id)
                return orbit
            }
            check(cursor !in settings.chatOwners) { "This Orbit has been removed" }
            cursor = dao.getChatById(cursor)?.parentChatId
        }
        return null
    }

    /**
     * Records which Orbit a newly made child chat (a subagent's) belongs to, from its parents, so
     * code that cannot look up the chat's ancestry still treats it as the Orbit's.
     */
    suspend fun registerDescendant(context: Context, chatId: String) {
        try {
            owner(context, chatId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            com.ai.assistance.operit.util.AppLogger.w("OrbitMemoryScope", "Could not record the Orbit of $chatId: ${error.message}")
        }
    }

    suspend fun resolve(context: Context, chatId: String?, requested: String?): String? {
        val orbit = owner(context, chatId)
        val preferences = UserPreferencesManager.getInstance(context)
        if (orbit == null) {
            requested?.takeIf { it.isNotBlank() }?.let {
                check(preferences.getMemorySpaceFlow(it).first().ownerOrbitId == null) {
                    "This memory belongs to another Orbit"
                }
            }
            return requested
        }
        val spaceId = ensureInitialized(context, orbit)
        val metadata = preferences.getMemorySpaceFlow(spaceId).first()
        check(metadata.ownerOrbitId == orbit.id) { "Orbit memory ownership is invalid" }
        return spaceId
    }

    /** Only legacy profiles without a binding are migrated. A missing bound space is an error. */
    suspend fun ensureInitialized(context: Context, orbit: OrbitProfile): String =
        migrationMutex.withLock {
            val store = OrbitPreferences.getInstance(context)
            val latest = store.current().orbits.firstOrNull { it.id == orbit.id }
                ?: error("Orbit no longer exists")
            check(!latest.deleting) { "Orbit is being deleted" }
            val spaceId = latest.memorySpaceId ?: run {
                val id = UserPreferencesManager.getInstance(context)
                    .createOrbitMemorySpace(latest.id, latest.name)
                store.bindMemory(latest.id, id)
                id
            }
            check(UserPreferencesManager.getInstance(context).getMemorySpaceFlow(spaceId).first().ownerOrbitId == latest.id) {
                "Orbit memory ownership is invalid"
            }
            spaceId
        }

    suspend fun checkChatAccess(context: Context, callerChatId: String?, targetChatId: String) {
        check(owner(context, callerChatId)?.id == owner(context, targetChatId)?.id) {
            "Chats from another memory scope are private"
        }
    }

    /**
     * Orbit memory changes never wait for the user. New proposals are approved as they come in;
     * this clears the ones left waiting from before that rule. A restored file version still
     * needs the user, and a proposal that no longer fits is skipped.
     */
    suspend fun approveWaitingChanges(context: Context, spaceId: String) {
        if (!com.ai.assistance.operit.data.preferences.MemorySearchSettingsPreferences.isOrbitSpace(spaceId)) return
        val reviews = com.ai.assistance.operit.data.preferences.MemoryReviewRepository(context, spaceId)
        reviews.list().filter { it.status == "pending" && it.restoredFrom.isBlank() }.forEach { change ->
            try {
                reviews.decide(context, change.id, true, "automatic", "Orbit memory changes are always approved")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.ai.assistance.operit.util.AppLogger.w("OrbitMemoryScope", "Could not approve ${change.id}: ${e.message}")
            }
        }
    }

    suspend fun privateOwner(context: Context, spaceId: String): String? =
        UserPreferencesManager.getInstance(context).getMemorySpaceFlow(spaceId).first().ownerOrbitId

    /** Filtering is applied by SQL before pagination, including direct message reads. */
    suspend fun recallScope(context: Context, chatId: String?): Pair<Boolean, List<String>> {
        val caller = owner(context, chatId)
        val settings = OrbitPreferences.getInstance(context).current()
        val ids = (settings.orbits.filter { caller == null || it.id == caller.id }
            .flatMap { it.ownedChatIds + listOf(it.chatId) } +
            settings.chatOwners.filterValues { caller == null || it == caller.id }.keys).toMutableSet()
        // Older branches may not have a saved ownership entry yet. Expand their ancestry before
        // SQL pagination; never classify an unvisited branch as global merely because it is new.
        val chats = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(context)
            .chatDao().getAllChatsDirectly()
        return (caller != null) to orbitDescendants(ids, chats.map { it.id to it.parentChatId }).toList()
    }
}

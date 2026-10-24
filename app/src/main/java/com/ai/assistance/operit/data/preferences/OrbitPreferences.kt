package com.ai.assistance.operit.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.core.tools.defaultTool.websession.browser.forgetOrbitBrowser
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.orbitDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "orbit_preferences")

/** How the last Orbit check-in ended. */
enum class OrbitRunOutcome {
    /** Orbit wrote a message into its chat. */
    SPOKE,
    /** The model decided there was nothing worth saying. */
    SILENT,
    FAILED,
}

/**
 * One companion with its own chat, name, look and Soul.md.
 *
 * A role card only seeds a new Orbit: its persona is copied into Soul.md once. Editing or deleting
 * the role card later does not change the Orbit.
 */
data class OrbitProfile(
    val id: String,
    /** The role card this Orbit was made from, kept so each card makes at most one Orbit. */
    val sourceRoleCardId: String,
    val chatId: String,
    /** The name Orbit chose for itself; only Orbit changes it, through its tool. */
    val name: String,
    /** Orbit's picture: copied from the role card at creation, or one the user picked. */
    val avatarUri: String? = null,
    val createdAt: Long = 0L,
    val memorySpaceId: String? = null,
    /** Includes task and descendant chats; ownership survives worker/process restarts. */
    val ownedChatIds: Set<String> = emptySet(),
    /** Persisted before cancellation so restart cannot resume a partially deleted companion. */
    val deleting: Boolean = false,
    /** Separate topic chats next to the main chat, like Muse's side chats. Their ids are also owned chats. */
    val sideChats: List<OrbitSideChat> = emptyList(),
    /**
     * When true the Orbit's browser uses the main app's logins. Its own saved logins stay on disk
     * while sharing, so turning this off brings them back.
     */
    val browserSharesMainLogin: Boolean = false,
    /** Live pet avatar; avatarUri remains its still fallback if the pet is removed. */
    val avatarPetId: String? = null,
    val appearance: OrbitAppearance = OrbitAppearance(),
) {
    /** The main chat and every side chat: the chats where the user talks to this Orbit. */
    val talkChatIds: List<String> get() = listOf(chatId) + sideChats.map { it.chatId }
}

/**
 * One side chat. [pinnedAt] above zero keeps it at the top of the menu, newest pin first; an
 * [archived] one leaves the menu for the archive list, like Muse's, and keeps its history.
 */
data class OrbitSideChat(
    val chatId: String,
    val title: String,
    val createdAt: Long,
    val pinnedAt: Long = 0L,
    val archived: Boolean = false,
    /** A reference only: inherited words are never inserted as new chat or memory rows. */
    val forkOrigin: OrbitChatOrigin? = null,
) {
    val pinned: Boolean get() = pinnedAt > 0L
}

data class OrbitChatOrigin(val chatId: String, val messageId: Long)

/** The side chats still in the menu, which the side chat limit counts. */
val OrbitProfile.openSideChats: List<OrbitSideChat> get() = sideChats.filterNot { it.archived }

/** Whether one more side chat fits: open ones under the limit, archived ones under a larger cap. */
val OrbitProfile.canAddSideChat: Boolean
    get() = openSideChats.size < OrbitSettings.MAX_SIDE_CHATS && sideChats.size < OrbitSettings.MAX_ALL_SIDE_CHATS

data class OrbitSettings(
    /** Every Orbit the user has made. */
    val orbits: List<OrbitProfile> = emptyList(),
    /** The one running Orbit; null means every Orbit is paused. */
    val activeOrbitId: String? = null,
    /** Whether the running Orbit checks in by itself every day. */
    val proactiveEnabled: Boolean = false,
    /** Minutes after midnight of the daily check-in. */
    val checkInMinuteOfDay: Int = DEFAULT_CHECK_IN_MINUTE,
    /** What the user wants Orbit to keep an eye on, in their own words. */
    val focusNotes: String = "",
    val lastRunAt: Long = 0L,
    val lastRunOutcome: OrbitRunOutcome? = null,
    val lastRunDetail: String = "",
    /** Retained after Orbit deletion so its archived chats never become global recall sources. */
    val chatOwners: Map<String, String> = emptyMap(),
    /** Developer option: shows the raw conversations, hidden turns and all, for auditing. */
    val developerMode: Boolean = false,
) {
    val active: OrbitProfile?
        get() = activeOrbitId?.let { id -> orbits.firstOrNull { it.id == id } }

    fun orbitForChat(chatId: String?): OrbitProfile? =
        chatId?.takeIf { it.isNotBlank() }?.let { id ->
            orbits.firstOrNull { it.chatId == id || it.sideChats.any { side -> side.chatId == id } }
        }

    fun orbitFromRoleCard(roleCardId: String): OrbitProfile? =
        orbits.firstOrNull { it.sourceRoleCardId == roleCardId }

    fun ownerForChat(chatId: String?): OrbitProfile? =
        chatId?.let { id -> orbits.firstOrNull {
            it.chatId == id || id in it.ownedChatIds || chatOwners[id] == it.id
        } }

    companion object {
        const val DEFAULT_CHECK_IN_MINUTE = 9 * 60
        const val MAX_FOCUS_NOTES_LENGTH = 1000
        const val MAX_NAME_LENGTH = 24
        const val MAX_SIDE_CHATS = 20
        /** Archived side chats keep their history; this only keeps the list from growing without end. */
        const val MAX_ALL_SIDE_CHATS = 100
        const val MAX_SIDE_CHAT_TITLE = 40
    }
}

class OrbitPreferences private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.orbitDataStore

    val settingsFlow: Flow<OrbitSettings> = dataStore.data.map { preferences ->
        val activeId = preferences[ACTIVE_ORBIT_ID]
        val daily = activeId?.let { dailySettings(preferences, it) } ?: JSONObject()
        OrbitSettings(
            orbits = decodeOrbits(preferences[ORBITS]),
            activeOrbitId = preferences[ACTIVE_ORBIT_ID]?.takeIf { it.isNotBlank() },
            proactiveEnabled = daily.optBoolean("enabled", false),
            checkInMinuteOfDay =
                daily.optInt("minute", OrbitSettings.DEFAULT_CHECK_IN_MINUTE)
                    .coerceIn(0, 24 * 60 - 1),
            focusNotes = daily.optString("focus", ""),
            lastRunAt = daily.optLong("lastAt", 0),
            lastRunOutcome =
                daily.optString("outcome", "").let { name ->
                    OrbitRunOutcome.entries.firstOrNull { it.name == name }
                },
            lastRunDetail = daily.optString("detail", ""),
            chatOwners = preferences[CHAT_OWNERS]?.let { raw ->
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { json.getString(it) }
            }.orEmpty(),
            developerMode = preferences[DEVELOPER_MODE] ?: false,
        )
    }

    /**
     * The latest settings for code that cannot suspend, such as tool visibility hooks. It is filled
     * shortly after the first use, so it may briefly be empty at app start.
     */
    @Volatile
    var snapshot: OrbitSettings = OrbitSettings()
        private set

    @Volatile
    private var snapshotLoaded = false

    /** The settings already read, or null before the first read; lets a page open with them at once. */
    fun loadedSnapshot(): OrbitSettings? = if (snapshotLoaded) snapshot else null

    init {
        settingsFlow.onEach { keepSnapshot(it) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    private fun keepSnapshot(settings: OrbitSettings) {
        snapshot = settings
        snapshotLoaded = true
    }

    suspend fun current(): OrbitSettings = settingsFlow.first().also { keepSnapshot(it) }

    /** Late tool work must fail closed once its companion starts deletion. */
    suspend fun requireModelOwner(orbitId: String): OrbitProfile =
        checkNotNull(current().orbits.firstOrNull { it.id == orbitId && !it.deleting }) {
            appContext.getString(com.ai.assistance.operit.R.string.orbit_model_owner_unavailable)
        }

    /**
     * The latest settings for code that cannot suspend but must not mistake an Orbit chat for a
     * normal one: it waits for the first read once, right after the app starts, and is free after.
     */
    fun loadedSettingsBlocking(): OrbitSettings =
        if (snapshotLoaded) snapshot else runBlocking(Dispatchers.IO) { current() }

    suspend fun setProactiveEnabled(enabled: Boolean, orbitId: String? = null) {
        editDaily(orbitId) { it.put("enabled", enabled) }
    }

    suspend fun setDeveloperMode(enabled: Boolean) {
        dataStore.edit { it[DEVELOPER_MODE] = enabled }
    }

    suspend fun setCheckInMinuteOfDay(minuteOfDay: Int) {
        editDaily(null) { it.put("minute", minuteOfDay.coerceIn(0, 24 * 60 - 1)) }
    }

    suspend fun setFocusNotes(notes: String) {
        editDaily(null) { it.put("focus", notes.take(OrbitSettings.MAX_FOCUS_NOTES_LENGTH)) }
    }

    private fun dailyKey(id: String) = stringPreferencesKey("daily_$id")

    /** Only the old active companion may inherit the legacy global settings, exactly once. */
    private fun dailySettings(preferences: Preferences, id: String): JSONObject =
        preferences[dailyKey(id)]?.let(::JSONObject) ?: if (preferences[ACTIVE_ORBIT_ID] == id) {
            JSONObject().put("enabled", preferences[PROACTIVE_ENABLED] ?: false)
                .put("minute", preferences[CHECK_IN_MINUTE] ?: OrbitSettings.DEFAULT_CHECK_IN_MINUTE)
                .put("focus", preferences[FOCUS_NOTES].orEmpty())
                .put("lastAt", preferences[LAST_RUN_AT] ?: 0)
                .put("outcome", preferences[LAST_RUN_OUTCOME].orEmpty())
                .put("detail", preferences[LAST_RUN_DETAIL].orEmpty())
        } else JSONObject()

    private suspend fun editDaily(orbitId: String?, change: (JSONObject) -> Unit) {
        dataStore.edit { preferences ->
            val id = orbitId ?: preferences[ACTIVE_ORBIT_ID] ?: return@edit
            check(decodeOrbits(preferences[ORBITS]).any { it.id == id && !it.deleting })
            val value = dailySettings(preferences, id)
            change(value)
            preferences[dailyKey(id)] = value.toString()
        }
    }

    /** Adds [profile], or replaces the Orbit with the same id. */
    suspend fun putOrbit(profile: OrbitProfile) {
        updateOrbits { orbits -> orbits.filterNot { it.id == profile.id } + profile }
    }

    suspend fun rememberChatOwner(chatId: String, orbitId: String) {
        var added = false
        dataStore.edit { preferences ->
            val owners = JSONObject(preferences[CHAT_OWNERS] ?: "{}")
            check(!owners.has(chatId) || owners.getString(chatId) == orbitId)
            added = !owners.has(chatId)
            owners.put(chatId, orbitId)
            preferences[CHAT_OWNERS] = owners.toString()
        }
        // Code that cannot suspend reads the snapshot; make the new owner visible to it right away.
        if (added) current()
    }

    /** Temporary model chats have no user archive; only forget ownership after confirmed deletion. */
    internal suspend fun forgetDeletedTaskChatOwner(chatId: String, orbitId: String) {
        require(chatId.startsWith("orbit-task-"))
        check(com.ai.assistance.operit.data.db.AppDatabase.getDatabase(appContext)
            .chatDao().getChatById(chatId) == null)
        dataStore.edit { preferences ->
            val owners = JSONObject(preferences[CHAT_OWNERS] ?: "{}")
            if (owners.optString(chatId) == orbitId) {
                owners.remove(chatId)
                preferences[CHAT_OWNERS] = owners.toString()
            }
        }
    }

    suspend fun markDeleting(orbitId: String) {
        updateOrbits { orbits -> orbits.map { if (it.id == orbitId) it.copy(deleting = true) else it } }
        if (current().activeOrbitId == orbitId) setActive(null)
    }

    /** Removes the Orbit and its Soul.md, and pauses it first when it was running. */
    suspend fun removeOrbit(orbitId: String) {
        var avatarUri: String? = null
        dataStore.edit { preferences ->
            val orbits = decodeOrbits(preferences[ORBITS])
            val owners = JSONObject(preferences[CHAT_OWNERS] ?: "{}")
            orbits.forEach { orbit ->
                (orbit.ownedChatIds + orbit.chatId).forEach { owners.put(it, orbit.id) }
            }
            preferences[CHAT_OWNERS] = owners.toString()
            avatarUri = orbits.firstOrNull { it.id == orbitId }?.avatarUri
            preferences[ORBITS] = encodeOrbits(orbits.filterNot { it.id == orbitId })
            preferences.remove(dailyKey(orbitId))
            if (preferences[ACTIVE_ORBIT_ID] == orbitId) preferences.remove(ACTIVE_ORBIT_ID)
        }
        OrbitModelPreferences(appContext).remove(orbitId)
        withContext(Dispatchers.IO) {
            com.ai.assistance.operit.features.orbit.OrbitSoulRepository.removeDirectory(orbitDir(orbitId))
            com.ai.assistance.operit.features.orbit.OrbitPackages.forgetNamesInUse()
            com.ai.assistance.operit.features.orbit.OrbitPackages.pruneCopies(appContext,
                com.ai.assistance.operit.core.tools.packTool.PackageManager.getInstance(appContext,
                    com.ai.assistance.operit.core.tools.AIToolHandler.getInstance(appContext)))
            deleteOwnedAvatar(avatarUri)
        }
        // Its tabs close and its own browser logins go with it.
        try {
            val browser = withContext(Dispatchers.IO) {
                com.ai.assistance.operit.core.tools.defaultTool.standard.StandardBrowserSessionTools(appContext)
            }
            withContext(Dispatchers.Main) { browser.forgetOrbitBrowser(orbitId) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.ai.assistance.operit.util.AppLogger.w("OrbitPreferences", "Could not clear the Orbit's browser: ${e.message}")
        }
    }

    suspend fun setName(orbitId: String, name: String) {
        updateOrbits { orbits -> orbits.map { if (it.id == orbitId) it.copy(name = name) else it } }
    }

    suspend fun setBrowserSharesMainLogin(orbitId: String, shared: Boolean) {
        updateOrbits { orbits -> orbits.map { if (it.id == orbitId) it.copy(browserSharesMainLogin = shared) else it } }
    }

    suspend fun bindMemory(orbitId: String, memorySpaceId: String) {
        updateOrbits { orbits ->
            check(orbits.any { it.id == orbitId }) { "Orbit no longer exists" }
            orbits.map {
                if (it.id == orbitId) {
                    check(it.memorySpaceId == null || it.memorySpaceId == memorySpaceId)
                    it.copy(memorySpaceId = memorySpaceId)
                } else it
            }
        }
    }

    suspend fun inheritChat(parentChatId: String, childChatId: String) {
        updateOrbits { orbits ->
            val owner = orbits.firstOrNull { it.chatId == parentChatId || parentChatId in it.ownedChatIds }
                ?: return@updateOrbits orbits
            check(orbits.none { it.id != owner.id && (it.chatId == childChatId || childChatId in it.ownedChatIds) })
            orbits.map { if (it.id == owner.id) it.copy(ownedChatIds = it.ownedChatIds + childChatId) else it }
        }
        // Code that cannot suspend reads the snapshot; make the inherited owner visible to it right away.
        current()
    }

    suspend fun addSideChat(orbitId: String, chatId: String, title: String, forkOrigin: OrbitChatOrigin? = null) {
        updateOrbits { orbits ->
            orbits.map {
                if (it.id != orbitId) return@map it
                check(it.canAddSideChat) { "Too many side chats" }
                it.copy(sideChats = it.sideChats + OrbitSideChat(chatId, title, System.currentTimeMillis(), forkOrigin = forkOrigin),
                    ownedChatIds = it.ownedChatIds + chatId)
            }
        }
    }

    /** Pins a side chat to the top of the menu, or unpins it. */
    suspend fun setSideChatPinned(orbitId: String, chatId: String, pinned: Boolean) {
        updateOrbits { orbits ->
            orbits.map { orbit ->
                if (orbit.id != orbitId) orbit
                else orbit.copy(sideChats = orbit.sideChats.map {
                    if (it.chatId == chatId) it.copy(pinnedAt = if (pinned) System.currentTimeMillis() else 0L) else it
                })
            }
        }
    }

    /**
     * Moves a side chat to the archive, or back to the menu. Archiving drops its pin. Bringing one
     * back fails when the menu already holds as many side chats as it may.
     */
    suspend fun setSideChatArchived(orbitId: String, chatId: String, archived: Boolean) {
        updateOrbits { orbits ->
            orbits.map { orbit ->
                if (orbit.id != orbitId) return@map orbit
                val side = orbit.sideChats.firstOrNull { it.chatId == chatId } ?: return@map orbit
                if (side.archived == archived) return@map orbit
                if (!archived) check(orbit.openSideChats.size < OrbitSettings.MAX_SIDE_CHATS) { "Too many side chats" }
                orbit.copy(sideChats = orbit.sideChats.map {
                    if (it.chatId == chatId) it.copy(archived = archived, pinnedAt = 0L) else it
                })
            }
        }
    }

    suspend fun renameSideChat(orbitId: String, chatId: String, title: String) {
        updateOrbits { orbits ->
            orbits.map { orbit ->
                if (orbit.id != orbitId) orbit
                else orbit.copy(sideChats = orbit.sideChats.map { if (it.chatId == chatId) it.copy(title = title) else it })
            }
        }
    }

    /** Renames a side chat only if its title is still [expected], in one update, so a name given meanwhile stays. */
    suspend fun renameSideChatIfTitled(chatId: String, expected: String, title: String) {
        updateOrbits { orbits ->
            orbits.map { orbit ->
                orbit.copy(sideChats = orbit.sideChats.map {
                    if (it.chatId == chatId && it.title == expected) it.copy(title = title) else it
                })
            }
        }
    }

    /** Drops the side chat from the list; the id stays owned so its rows never show up elsewhere. */
    suspend fun removeSideChat(orbitId: String, chatId: String) {
        updateOrbits { orbits ->
            orbits.map { orbit ->
                if (orbit.id != orbitId) orbit else orbit.copy(sideChats = orbit.sideChats.filterNot { it.chatId == chatId })
            }
        }
    }

    suspend fun setAvatar(orbitId: String, avatarUri: String?, petId: String? = null) {
        var previous: String? = null
        updateOrbits { orbits ->
            orbits.map {
                if (it.id == orbitId) {
                    previous = it.avatarUri
                    it.copy(avatarUri = avatarUri, avatarPetId = petId)
                } else {
                    it
                }
            }
        }
        if (previous != avatarUri) withContext(Dispatchers.IO) { deleteOwnedAvatar(previous) }
    }

    suspend fun updateAppearance(orbitId: String, transform: (OrbitAppearance) -> OrbitAppearance) {
        updateOrbits { orbits ->
            orbits.map { if (it.id == orbitId) it.copy(appearance = transform(it.appearance)) else it }
        }
    }

    /** Deletes a picture copy Orbit made in the app's files, and nothing else. */
    private fun deleteOwnedAvatar(avatarUri: String?) {
        val path = avatarUri?.let { android.net.Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path ?: return
        val file = File(path)
        if (file.parentFile == appContext.filesDir && file.name.startsWith(AVATAR_FILE_PREFIX)) {
            file.delete()
        }
    }

    /** Starts the Orbit [orbitId], or pauses every Orbit when it is null. */
    suspend fun setActive(orbitId: String?) = com.ai.assistance.operit.features.orbit.OrbitExecutionGate.serialized {
        val previous = dataStore.data.first()[ACTIVE_ORBIT_ID]
        if (previous != null && previous != orbitId) {
            val dao = com.ai.assistance.operit.data.db.AppDatabase.getDatabase(appContext).orbitTaskDao()
            val now = System.currentTimeMillis()
            dao.tasks(previous).forEach {
                com.ai.assistance.operit.features.orbit.OrbitBrowserControl.release(it.id)
            }
            dao.tasks(previous).filter { it.state == "ACTIVE" }.forEach { task ->
                dao.replaceDefinition(task.copy(
                    nextRunAt = com.ai.assistance.operit.features.orbit.OrbitTaskRepository.nextAt(task, now),
                    updatedAt = now,
                ), task.version)
            }
        }
        dataStore.edit { preferences ->
            val previousId = preferences[ACTIVE_ORBIT_ID]
            if (previousId != null && preferences[dailyKey(previousId)] == null) {
                preferences[dailyKey(previousId)] = dailySettings(preferences, previousId).toString()
            }
            if (orbitId == null) {
                preferences.remove(ACTIVE_ORBIT_ID)
            } else {
                check(decodeOrbits(preferences[ORBITS]).any { it.id == orbitId && !it.deleting })
                if (preferences[dailyKey(orbitId)] == null) preferences[dailyKey(orbitId)] = "{}"
                preferences[ACTIVE_ORBIT_ID] = orbitId
            }
        }
    }

    suspend fun recordRun(outcome: OrbitRunOutcome, detail: String, orbitId: String? = null) {
        editDaily(orbitId) {
            it.put("lastAt", System.currentTimeMillis())
            it.put("outcome", outcome.name)
            it.put("detail", detail.take(MAX_DETAIL_LENGTH))
        }
    }

    /** The folder that holds one Orbit's own files, such as Soul.md. */
    fun orbitDir(orbitId: String): File {
        val name = File(orbitId).name
        require(name.isNotBlank() && name != "." && name != "..") { "Bad Orbit id" }
        return File(appContext.filesDir, "orbit/$name")
    }

    private fun soulFile(orbitId: String): File = File(orbitDir(orbitId), SOUL_FILE_NAME)

    /** Orbit's persona, or an empty string when it has none. */
    suspend fun readSoul(orbitId: String): String =
        com.ai.assistance.operit.features.orbit.OrbitSoulRepository(appContext, orbitId).snapshot().current.body

    suspend fun writeSoul(orbitId: String, soul: String) {
        com.ai.assistance.operit.features.orbit.OrbitSoulRepository(appContext, orbitId).seed(soul)
    }

    private suspend fun updateOrbits(transform: (List<OrbitProfile>) -> List<OrbitProfile>) {
        dataStore.edit { preferences ->
            val previous = decodeOrbits(preferences[ORBITS])
            val updated = transform(previous)
            val owners = JSONObject(preferences[CHAT_OWNERS] ?: "{}")
            (previous + updated).forEach { orbit ->
                (orbit.ownedChatIds + orbit.chatId).forEach { chatId ->
                    check(!owners.has(chatId) || owners.getString(chatId) == orbit.id)
                    owners.put(chatId, orbit.id)
                }
            }
            preferences[CHAT_OWNERS] = owners.toString()
            preferences[ORBITS] = encodeOrbits(updated)
        }
    }

    companion object {
        const val SOUL_FILE_NAME = "Soul.md"
        /** Name prefix of picture copies Orbit keeps in the app's files. */
        const val AVATAR_FILE_PREFIX = "orbit_avatar_"
        private const val MAX_DETAIL_LENGTH = 300
        private val ORBITS = stringPreferencesKey("orbit_profiles")
        private val CHAT_OWNERS = stringPreferencesKey("orbit_chat_owners")
        private val ACTIVE_ORBIT_ID = stringPreferencesKey("active_orbit_id")
        private val PROACTIVE_ENABLED = booleanPreferencesKey("proactive_enabled")
        private val CHECK_IN_MINUTE = intPreferencesKey("check_in_minute_of_day")
        private val FOCUS_NOTES = stringPreferencesKey("focus_notes")
        private val LAST_RUN_AT = longPreferencesKey("last_run_at")
        private val LAST_RUN_OUTCOME = stringPreferencesKey("last_run_outcome")
        private val LAST_RUN_DETAIL = stringPreferencesKey("last_run_detail")
        private val DEVELOPER_MODE = booleanPreferencesKey("orbit_developer_mode")

        private fun decodeOrbits(raw: String?): List<OrbitProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val id = item.optString("id")
                    val chatId = item.optString("chatId")
                    if (id.isBlank() || chatId.isBlank()) return@mapNotNull null
                    OrbitProfile(
                        id = id,
                        sourceRoleCardId = item.optString("sourceRoleCardId"),
                        chatId = chatId,
                        name = item.optString("name"),
                        avatarUri = item.optString("avatarUri").takeIf { it.isNotBlank() },
                        avatarPetId = item.optString("avatarPetId").takeIf { it.isNotBlank() },
                        appearance = OrbitAppearance.fromJson(item.optJSONObject("appearance")),
                        createdAt = item.optLong("createdAt"),
                        memorySpaceId = item.optString("memorySpaceId").takeIf { it.isNotBlank() },
                        ownedChatIds = item.optJSONArray("ownedChatIds")?.let { ids ->
                            (0 until ids.length()).map { ids.getString(it) }.toSet()
                        }.orEmpty(),
                        deleting = item.optBoolean("deleting", false),
                        sideChats = item.optJSONArray("sideChats")?.let { list ->
                            (0 until list.length()).mapNotNull { i ->
                                list.optJSONObject(i)?.let { side ->
                                    side.optString("chatId").takeIf { it.isNotBlank() }?.let {
                                        OrbitSideChat(
                                            it, side.optString("title"), side.optLong("createdAt"),
                                            pinnedAt = side.optLong("pinnedAt", 0L),
                                            archived = side.optBoolean("archived", false),
                                            forkOrigin = side.optJSONObject("forkOrigin")?.let { origin ->
                                                val source = origin.optString("chatId")
                                                val message = origin.optLong("messageId")
                                                if (source.isNotBlank() && message > 0) OrbitChatOrigin(source, message) else null
                                            },
                                        )
                                    }
                                }
                            }
                        }.orEmpty(),
                        browserSharesMainLogin = item.optBoolean("browserSharesMainLogin", false),
                    )
                }
            }.getOrDefault(emptyList())
        }

        private fun encodeOrbits(orbits: List<OrbitProfile>): String =
            JSONArray().apply {
                orbits.forEach { orbit ->
                    put(
                        JSONObject().apply {
                            put("id", orbit.id)
                            put("sourceRoleCardId", orbit.sourceRoleCardId)
                            put("chatId", orbit.chatId)
                            put("name", orbit.name)
                            orbit.avatarUri?.let { put("avatarUri", it) }
                            orbit.avatarPetId?.let { put("avatarPetId", it) }
                            put("appearance", orbit.appearance.toJson())
                            put("createdAt", orbit.createdAt)
                            orbit.memorySpaceId?.let { put("memorySpaceId", it) }
                            put("ownedChatIds", JSONArray(orbit.ownedChatIds.toList()))
                            put("deleting", orbit.deleting)
                            put("sideChats", JSONArray(orbit.sideChats.map {
                                JSONObject().put("chatId", it.chatId).put("title", it.title).put("createdAt", it.createdAt)
                                    .put("pinnedAt", it.pinnedAt).put("archived", it.archived)
                                    .apply { it.forkOrigin?.let { origin ->
                                        put("forkOrigin", JSONObject().put("chatId", origin.chatId).put("messageId", origin.messageId))
                                    } }
                            }))
                            put("browserSharesMainLogin", orbit.browserSharesMainLogin)
                        }
                    )
                }
            }.toString()

        @Volatile
        private var instance: OrbitPreferences? = null

        fun getInstance(context: Context): OrbitPreferences =
            instance ?: synchronized(this) {
                instance ?: OrbitPreferences(context).also { instance = it }
            }
    }
}

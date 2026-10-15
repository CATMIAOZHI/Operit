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
)

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
) {
    val active: OrbitProfile?
        get() = activeOrbitId?.let { id -> orbits.firstOrNull { it.id == id } }

    fun orbitForChat(chatId: String?): OrbitProfile? =
        chatId?.takeIf { it.isNotBlank() }?.let { id -> orbits.firstOrNull { it.chatId == id } }

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
    }
}

class OrbitPreferences private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val dataStore = appContext.orbitDataStore

    val settingsFlow: Flow<OrbitSettings> = dataStore.data.map { preferences ->
        OrbitSettings(
            orbits = decodeOrbits(preferences[ORBITS]),
            activeOrbitId = preferences[ACTIVE_ORBIT_ID]?.takeIf { it.isNotBlank() },
            proactiveEnabled = preferences[PROACTIVE_ENABLED] ?: false,
            checkInMinuteOfDay =
                (preferences[CHECK_IN_MINUTE] ?: OrbitSettings.DEFAULT_CHECK_IN_MINUTE)
                    .coerceIn(0, 24 * 60 - 1),
            focusNotes = preferences[FOCUS_NOTES].orEmpty(),
            lastRunAt = preferences[LAST_RUN_AT] ?: 0L,
            lastRunOutcome =
                preferences[LAST_RUN_OUTCOME]?.let { name ->
                    OrbitRunOutcome.entries.firstOrNull { it.name == name }
                },
            lastRunDetail = preferences[LAST_RUN_DETAIL].orEmpty(),
            chatOwners = preferences[CHAT_OWNERS]?.let { raw ->
                val json = JSONObject(raw)
                json.keys().asSequence().associateWith { json.getString(it) }
            }.orEmpty(),
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

    init {
        settingsFlow.onEach { keepSnapshot(it) }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    private fun keepSnapshot(settings: OrbitSettings) {
        snapshot = settings
        snapshotLoaded = true
    }

    suspend fun current(): OrbitSettings = settingsFlow.first().also { keepSnapshot(it) }

    /**
     * The latest settings for code that cannot suspend but must not mistake an Orbit chat for a
     * normal one: it waits for the first read once, right after the app starts, and is free after.
     */
    fun loadedSettingsBlocking(): OrbitSettings =
        if (snapshotLoaded) snapshot else runBlocking(Dispatchers.IO) { current() }

    suspend fun setProactiveEnabled(enabled: Boolean) {
        dataStore.edit { it[PROACTIVE_ENABLED] = enabled }
    }

    suspend fun setCheckInMinuteOfDay(minuteOfDay: Int) {
        dataStore.edit { it[CHECK_IN_MINUTE] = minuteOfDay.coerceIn(0, 24 * 60 - 1) }
    }

    suspend fun setFocusNotes(notes: String) {
        dataStore.edit { it[FOCUS_NOTES] = notes.take(OrbitSettings.MAX_FOCUS_NOTES_LENGTH) }
    }

    /** Adds [profile], or replaces the Orbit with the same id. */
    suspend fun putOrbit(profile: OrbitProfile) {
        updateOrbits { orbits -> orbits.filterNot { it.id == profile.id } + profile }
    }

    suspend fun rememberChatOwner(chatId: String, orbitId: String) {
        dataStore.edit { preferences ->
            val owners = JSONObject(preferences[CHAT_OWNERS] ?: "{}")
            check(!owners.has(chatId) || owners.getString(chatId) == orbitId)
            owners.put(chatId, orbitId)
            preferences[CHAT_OWNERS] = owners.toString()
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
            if (preferences[ACTIVE_ORBIT_ID] == orbitId) preferences.remove(ACTIVE_ORBIT_ID)
        }
        withContext(Dispatchers.IO) {
            orbitDir(orbitId).deleteRecursively()
            deleteOwnedAvatar(avatarUri)
        }
    }

    suspend fun setName(orbitId: String, name: String) {
        updateOrbits { orbits -> orbits.map { if (it.id == orbitId) it.copy(name = name) else it } }
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
    }

    suspend fun setAvatar(orbitId: String, avatarUri: String?) {
        var previous: String? = null
        updateOrbits { orbits ->
            orbits.map {
                if (it.id == orbitId) {
                    previous = it.avatarUri
                    it.copy(avatarUri = avatarUri)
                } else {
                    it
                }
            }
        }
        if (previous != avatarUri) withContext(Dispatchers.IO) { deleteOwnedAvatar(previous) }
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
    suspend fun setActive(orbitId: String?) {
        dataStore.edit { preferences ->
            if (orbitId == null) {
                preferences.remove(ACTIVE_ORBIT_ID)
            } else {
                check(decodeOrbits(preferences[ORBITS]).any { it.id == orbitId && !it.deleting })
                preferences[ACTIVE_ORBIT_ID] = orbitId
            }
        }
    }

    suspend fun recordRun(outcome: OrbitRunOutcome, detail: String) {
        dataStore.edit {
            it[LAST_RUN_AT] = System.currentTimeMillis()
            it[LAST_RUN_OUTCOME] = outcome.name
            it[LAST_RUN_DETAIL] = detail.take(MAX_DETAIL_LENGTH)
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
        withContext(Dispatchers.IO) {
            soulFile(orbitId).takeIf { it.isFile }?.readText().orEmpty()
        }

    suspend fun writeSoul(orbitId: String, soul: String) {
        withContext(Dispatchers.IO) {
            val file = soulFile(orbitId)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "$SOUL_FILE_NAME.tmp")
            temp.writeText(soul)
            if (!temp.renameTo(file)) {
                file.writeText(soul)
                temp.delete()
            }
        }
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
                        createdAt = item.optLong("createdAt"),
                        memorySpaceId = item.optString("memorySpaceId").takeIf { it.isNotBlank() },
                        ownedChatIds = item.optJSONArray("ownedChatIds")?.let { ids ->
                            (0 until ids.length()).map { ids.getString(it) }.toSet()
                        }.orEmpty(),
                        deleting = item.optBoolean("deleting", false),
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
                            put("createdAt", orbit.createdAt)
                            orbit.memorySpaceId?.let { put("memorySpaceId", it) }
                            put("ownedChatIds", JSONArray(orbit.ownedChatIds.toList()))
                            put("deleting", orbit.deleting)
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

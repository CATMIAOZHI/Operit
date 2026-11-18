package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.assistance.operit.features.orbit.OrbitFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** How a part of the Builds tab orders its rows. Folders always come first. */
internal enum class BuildSort { TIME, NAME, SIZE, TYPE }

private val Context.buildSortStore by preferencesDataStore(name = "orbit_build_sort")

/** The order each part of the Builds tab was last set to; kept for every Orbit alike. */
internal class BuildSortPreferences private constructor(context: Context) {
    private val store = context.applicationContext.buildSortStore

    /** The order of every part, by part; one read serves all three, so switching parts shows the right one at once. */
    fun observeAll(): Flow<List<BuildSort>> = store.data
        .map { prefs ->
            SECTIONS.map { section ->
                prefs[key(section)]?.let { saved -> BuildSort.entries.firstOrNull { it.name == saved } } ?: defaultFor(section)
            }
        }
        .distinctUntilChanged()

    suspend fun set(section: Int, sort: BuildSort) {
        store.edit { it[key(section)] = sort.name }
    }

    private fun key(section: Int) = stringPreferencesKey("section_$section")

    companion object {
        private val SECTIONS = 0..2

        @Volatile private var instance: BuildSortPreferences? = null

        fun getInstance(context: Context): BuildSortPreferences =
            instance ?: synchronized(this) { instance ?: BuildSortPreferences(context).also { instance = it } }

        /** What each part showed before it could be changed: newest first, system files by name. */
        fun defaultFor(section: Int) = if (section == 2) BuildSort.NAME else BuildSort.TIME

        val defaults: List<BuildSort> = SECTIONS.map(::defaultFor)
    }
}

/**
 * The order for [sort]: newest first, by name, biggest first, or by file type. Folders stay on top;
 * ties fall back to the name, so the order never jumps between reloads.
 */
internal fun <T> buildOrder(
    sort: BuildSort,
    name: (T) -> String,
    time: (T) -> Long,
    size: (T) -> Long,
    folder: (T) -> Boolean = { false },
): Comparator<T> {
    val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.SECONDARY }
    val byName = Comparator<T> { a, b -> collator.compare(name(a), name(b)) }
    val main: Comparator<T> = when (sort) {
        BuildSort.TIME -> compareByDescending(time)
        BuildSort.NAME -> byName
        BuildSort.SIZE -> compareByDescending(size)
        BuildSort.TYPE -> compareBy { if (folder(it)) "" else name(it).substringAfterLast('.', "").lowercase() }
    }
    return compareByDescending(folder).then(main).then(byName)
}

/** A folder inside files/ as the Builds tab shows it: what is in it, all the way down. */
internal data class MadeFolder(val path: String, val name: String, val count: Int, val size: Long, val modifiedAt: Long)

/**
 * What is directly in [folder] ("" is files/ itself) out of Orbit's listed [files]: the folders
 * below it, each summing what it holds, and its own files. Built from the listing, so it shows
 * exactly the files the listing allows and nothing outside them.
 */
internal fun madeFolderView(files: List<OrbitFiles.Entry>, folder: String): Pair<List<MadeFolder>, List<OrbitFiles.Entry>> {
    val prefix = if (folder.isEmpty()) "files/" else "files/$folder/"
    val folders = LinkedHashMap<String, MutableList<OrbitFiles.Entry>>()
    val own = ArrayList<OrbitFiles.Entry>()
    files.forEach { file ->
        if (!file.path.startsWith(prefix)) return@forEach
        val rest = file.path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash < 0) own += file else folders.getOrPut(rest.substring(0, slash)) { ArrayList() } += file
    }
    return folders.map { (name, inside) ->
        MadeFolder(
            path = if (folder.isEmpty()) name else "$folder/$name",
            name = name,
            count = inside.size,
            size = inside.sumOf { it.size.coerceAtLeast(0) },
            modifiedAt = inside.maxOf { it.modifiedAt },
        )
    } to own
}

/** [folder] if anything is still in it, else the nearest folder above it that has something ("" at the top). */
internal fun nearestMadeFolder(files: List<OrbitFiles.Entry>, folder: String): String {
    var candidate = folder
    while (candidate.isNotEmpty() && files.none { it.path.startsWith("files/$candidate/") }) {
        candidate = candidate.substringBeforeLast('/', "")
    }
    return candidate
}

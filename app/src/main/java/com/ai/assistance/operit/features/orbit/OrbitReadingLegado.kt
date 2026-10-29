package com.ai.assistance.operit.features.orbit

import android.content.Context
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.reading.ReadingCompanionFileStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The running Orbit's paragraph comments as Legado asks for them, through the same read-only
 * provider as the main app's reading companion. When both have comments for a chapter laid out the
 * same way, Legado gets them together; otherwise it gets the set that matches the page it shows.
 */
internal object OrbitReadingLegado {
    private const val KIND = "orbit"

    /** The running Orbit's name and reading folder; null while no Orbit runs. */
    private fun active(context: Context): Pair<String, File>? {
        val store = OrbitPreferences.getInstance(context)
        val orbit = store.loadedSettingsBlocking().active?.takeIf { !it.deleting } ?: return null
        return orbit.name.ifBlank { "Orbit" } to File(store.orbitDir(orbit.id), OrbitReading.DIR)
    }

    /** The summary of chapter [chapterIndex] (from 0), or null when Orbit has no comments there. */
    fun summary(context: Context, bookId: String, chapterIndex: Int, expectedHash: String?, mappingRequested: Boolean): JSONObject? {
        val (_, root) = active(context) ?: return null
        val chapter = OrbitReading.pick(OrbitReading.chapterDirIn(root, bookId), chapterIndex, hash = expectedHash) ?: return null
        return summaryOf(chapter, expectedHash, mappingRequested)
    }

    /**
     * Comments grouped by paragraph. A chapter laid out differently from the page Legado shows
     * ([expectedHash]) is offered for remapping by paragraph text when Legado can remap; comments
     * whose paragraph text is not unique are left out then, as Legado cannot place them.
     */
    internal fun summaryOf(chapter: OrbitReading.ChapterComments, expectedHash: String?, mappingRequested: Boolean): JSONObject {
        val exact = expectedHash == null || chapter.contractHash == expectedHash
        if (!exact && !mappingRequested) return JSONObject().put("enabled", true).put("ready", false).put("stale", true)
        val groups = chapter.comments.filter { exact || (it.unique && it.fingerprint.isNotBlank()) }
            .groupBy { it.paragraph }.toSortedMap()
        if (groups.isEmpty()) return JSONObject().put("enabled", true).put("ready", false).put("stale", !exact)
        return JSONObject().put("enabled", true).put("ready", true)
            .put("contentHash", chapter.contractHash)
            .put("remapRequired", !exact)
            .put("mappingVersion", ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION)
            .put("comments", JSONArray(groups.map { (paragraph, comments) ->
                JSONObject().put("paragraphIndex", paragraph).put("count", comments.size)
                    .put("preview", comments.first().text)
                    .put("paragraphFingerprint", comments.first().fingerprint)
                    .put("sourceUnique", comments.first().unique)
            }))
    }

    /** Orbit's comments on one paragraph, as Legado's detail items; null when it has none there. */
    fun detail(context: Context, bookId: String, chapterIndex: Int, paragraphIndex: Int, expectedHash: String?): JSONArray? {
        val (name, root) = active(context) ?: return null
        val chapter = OrbitReading.pick(OrbitReading.chapterDirIn(root, bookId), chapterIndex, hash = expectedHash) ?: return null
        if (expectedHash != null && chapter.contractHash != expectedHash) return null
        val items = chapter.comments.filter { it.paragraph == paragraphIndex }
        if (items.isEmpty()) return null
        return JSONArray(items.map { comment ->
            JSONObject().put("id", "orbit-${comment.id}").put("name", name).put("badges", JSONArray().put("AI"))
                .put("content", comment.text).put("kind", KIND).put("createdAt", comment.createdAt)
        })
    }

    /**
     * One summary from the main app's ([main], null while its package is off) and Orbit's. Both
     * ready and saved on the same layout (also the same older one, offered for remapping): counted
     * together, the main app's preview first. Otherwise the ready one; when both are ready but saved
     * on different layouts, the one that matches the page as it is.
     */
    internal fun mergeSummary(main: JSONObject?, orbit: JSONObject?): JSONObject {
        val mainReady = main?.takeIf { it.optBoolean("enabled") && it.optBoolean("ready") }
        val orbitReady = orbit?.takeIf { it.optBoolean("ready") }
        if (mainReady == null || orbitReady == null) {
            return mainReady ?: orbitReady ?: main ?: orbit ?: JSONObject().put("enabled", false).put("ready", false)
        }
        val remap = mainReady.optBoolean("remapRequired")
        val sameLayout = mainReady.optString("contentHash") == orbitReady.optString("contentHash") &&
            remap == orbitReady.optBoolean("remapRequired")
        if (!sameLayout) {
            return if (remap && !orbitReady.optBoolean("remapRequired")) orbitReady else mainReady
        }
        val groups = sortedMapOf<Int, JSONObject>()
        listOf(mainReady, orbitReady).forEach { summary ->
            val list = summary.optJSONArray("comments") ?: return@forEach
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val paragraph = item.optInt("paragraphIndex")
                val seen = groups[paragraph]
                if (seen == null) {
                    groups[paragraph] = JSONObject().put("paragraphIndex", paragraph).put("count", item.optInt("count"))
                        .put("preview", item.optString("preview"))
                        .apply {
                            // Legado places comments from an older layout by these.
                            item.optString("paragraphFingerprint").takeIf { it.isNotBlank() }?.let { put("paragraphFingerprint", it) }
                            if (item.has("sourceUnique")) put("sourceUnique", item.optBoolean("sourceUnique"))
                        }
                } else {
                    seen.put("count", seen.optInt("count") + item.optInt("count"))
                }
            }
        }
        return JSONObject().put("enabled", true).put("ready", true)
            .put("contentHash", mainReady.optString("contentHash"))
            .put("remapRequired", remap)
            .apply { if (remap) put("mappingVersion", ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION) }
            .put("comments", JSONArray(groups.values.toList()))
    }

    /** The main app's detail ([main]) with Orbit's comments on that paragraph after it. */
    internal fun mergeDetail(main: JSONObject?, orbit: JSONArray?): JSONObject {
        if (orbit == null || orbit.length() == 0) return main ?: JSONObject().put("ready", false).put("comments", JSONArray())
        val comments = JSONArray()
        main?.takeIf { it.optBoolean("ready") }?.optJSONArray("comments")?.let { list ->
            for (i in 0 until list.length()) comments.put(list.get(i))
        }
        for (i in 0 until orbit.length()) comments.put(orbit.get(i))
        return JSONObject().put("ready", true).put("comments", comments)
    }
}

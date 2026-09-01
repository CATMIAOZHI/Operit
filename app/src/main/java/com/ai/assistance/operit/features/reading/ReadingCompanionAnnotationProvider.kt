package com.ai.assistance.operit.features.reading

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only paragraph-comment surface consumed by Legado.
 *
 * The provider exposes generated comments only; it does not serve raw chapter text. Disabling
 * the optional auto-commentary subpackage stops future generation but does not
 * hide comments that were already generated; disabling the parent Reading Companion ToolPkg
 * removes this read surface entirely.
 */
class ReadingCompanionAnnotationProvider : ContentProvider() {
    private lateinit var store: ReadingCompanionStore
    private lateinit var fileStore: ReadingCompanionFileStore
    private lateinit var matcher: UriMatcher

    override fun onCreate(): Boolean {
        val appContext = requireNotNull(context).applicationContext
        store = ReadingCompanionStore(appContext)
        fileStore = ReadingCompanionFileStore(appContext)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            val authority = "${appContext.packageName}.readingCompanionAnnotations"
            addURI(authority, "reviews/summary", MATCH_SUMMARY)
            addURI(authority, "reviews/detail", MATCH_DETAIL)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        enforceLegadoCaller()
        val result = try {
            if (!ReadingCompanionAutoCommentary.isBasePackageEnabled(requireNotNull(context))) {
                success(JSONObject().put("enabled", false).put("ready", false))
            } else {
                when (matcher.match(uri)) {
                    MATCH_SUMMARY -> querySummary(uri)
                    MATCH_DETAIL -> queryDetail(uri)
                    else -> failure("未知的 AI 段评查询路径")
                }
            }
        } catch (error: Throwable) {
            failure(error.message ?: "AI 段评查询失败")
        }
        return MatrixCursor(arrayOf(RESULT_COLUMN), 1).apply {
            addRow(arrayOf(result.toString()))
        }
    }

    override fun getType(uri: Uri): String =
        "application/vnd.operit.reading-companion-review+json"

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceLegadoCaller()
        require(method == METHOD_READING_PROGRESS_CHANGED) {
            "未知的 AI 段评调用方法"
        }
        val appContext = requireNotNull(context)
        val enabled = ReadingCompanionAutoCommentary.isEnabled(appContext)
        if (enabled) {
            ReadingCompanionAutoCommentary.schedule(appContext)
        }
        return Bundle().apply { putBoolean("scheduled", enabled) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("AI 段评数据接口为只读")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("AI 段评数据接口为只读")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("AI 段评数据接口为只读")

    private fun querySummary(uri: Uri): JSONObject {
        val bookId = requireParameter(uri, "bookId")
        val chapterIndex = requireParameter(uri, "chapterIndex").toInt()
        val expectedHash = uri.getQueryParameter("contentHash")?.takeIf(String::isNotBlank)
        val requestedMappingVersion = uri.getQueryParameter("mappingVersion")
        fileStore.readPublishedComments(
            bookId = bookId,
            chapterIndex = chapterIndex,
            expectedContractHash = expectedHash,
            allowStaleFingerprintMapping =
                requestedMappingVersion == ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION,
        )?.let { published ->
            if (!published.optBoolean("ready")) {
                return success(
                    JSONObject()
                        .put("enabled", true)
                        .put("ready", false)
                        .put("stale", published.optBoolean("stale")),
                )
            }
            val grouped = linkedMapOf<Int, MutableList<JSONObject>>()
            val comments = published.optJSONArray("comments") ?: JSONArray()
            repeat(comments.length()) { position ->
                val comment = comments.optJSONObject(position) ?: return@repeat
                if (
                    published.optBoolean("remapRequired") &&
                    (
                        comment.optString(
                            ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION_FIELD,
                        ) != ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION ||
                            comment.optString(
                                ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_FIELD,
                            ).isBlank() ||
                            !comment.optBoolean(
                                ReadingCompanionFileStore.PARAGRAPH_FINGERPRINT_UNIQUE_FIELD,
                            )
                    )
                ) {
                    return@repeat
                }
                grouped.getOrPut(comment.optInt("paragraphIndex")) { mutableListOf() }
                    .add(comment)
            }
            return success(
                JSONObject()
                    .put("enabled", true)
                    .put("ready", true)
                    .put("contentHash", published.optString("contractHash"))
                    .put("remapRequired", published.optBoolean("remapRequired"))
                    .put(
                        "mappingVersion",
                        ReadingCompanionFileStore.PARAGRAPH_MAPPING_VERSION,
                    )
                    .put(
                        "comments",
                        JSONArray().apply {
                            grouped.toSortedMap().forEach { (paragraphIndex, values) ->
                                put(
                                    JSONObject()
                                        .put("paragraphIndex", paragraphIndex)
                                        .put("count", values.size)
                                        .put(
                                            "preview",
                                            values.firstOrNull()?.optString("text").orEmpty(),
                                        )
                                        .apply {
                                            values.firstOrNull()?.let { first ->
                                                first.optString(
                                                    ReadingCompanionFileStore
                                                        .PARAGRAPH_FINGERPRINT_FIELD,
                                                ).takeIf(String::isNotBlank)?.let { fingerprint ->
                                                    put(
                                                        "paragraphFingerprint",
                                                        fingerprint,
                                                    )
                                                    put(
                                                        "sourceUnique",
                                                        first.optBoolean(
                                                            ReadingCompanionFileStore
                                                                .PARAGRAPH_FINGERPRINT_UNIQUE_FIELD,
                                                        ),
                                                    )
                                                }
                                            }
                                        },
                                )
                            }
                        },
                    ),
            )
        }
        if (fileStore.hasCatalog(bookId)) {
            return success(JSONObject().put("enabled", true).put("ready", false))
        }
        val chapter = store.getAutoCommentChapter(bookId, chapterIndex)
            ?: return success(JSONObject().put("enabled", true).put("ready", false))
        if (
            chapter.status != ReadingCompanionStore.AUTO_COMMENT_STATUS_READY ||
            chapter.generationPolicyVersion != AutoCommentSupport.GENERATION_POLICY_VERSION ||
            (expectedHash != null && expectedHash != chapter.contentHash)
        ) {
            return success(
                JSONObject()
                    .put("enabled", true)
                    .put("ready", false)
                    .put("stale", expectedHash != null && expectedHash != chapter.contentHash),
            )
        }
        val grouped = store.getAutoComments(bookId, chapterIndex)
            .filter { comment ->
                !comment.roleCardId.isNullOrBlank() && !comment.roleCardName.isNullOrBlank()
            }
            .groupBy(AutoCommentRecord::paragraphIndex)
        return success(
            JSONObject()
                .put("enabled", true)
                .put("ready", true)
                .put("contentHash", chapter.contentHash)
                .put(
                    "comments",
                    JSONArray().apply {
                        grouped.toSortedMap().forEach { (paragraphIndex, comments) ->
                            put(
                                JSONObject()
                                    .put("paragraphIndex", paragraphIndex)
                                    .put("count", comments.size)
                                    .put("preview", comments.firstOrNull()?.text.orEmpty())
                            )
                        }
                    },
                ),
        )
    }

    private fun queryDetail(uri: Uri): JSONObject {
        val bookId = requireParameter(uri, "bookId")
        val chapterIndex = requireParameter(uri, "chapterIndex").toInt()
        val paragraphIndex = requireParameter(uri, "paragraphIndex").toInt()
        val expectedHash = uri.getQueryParameter("contentHash")?.takeIf(String::isNotBlank)
        fileStore.readPublishedComments(bookId, chapterIndex, expectedHash)?.let { published ->
            if (!published.optBoolean("ready")) {
                return success(JSONObject().put("ready", false).put("comments", JSONArray()))
            }
            val roleCardName = published.optString("roleCardName")
            val source = published.optJSONArray("comments") ?: JSONArray()
            return success(
                JSONObject()
                    .put("ready", true)
                    .put(
                        "comments",
                        JSONArray().apply {
                            repeat(source.length()) { position ->
                                val comment = source.optJSONObject(position) ?: return@repeat
                                if (comment.optInt("paragraphIndex") != paragraphIndex) {
                                    return@repeat
                                }
                                put(
                                    JSONObject()
                                        .put("id", "${chapterIndex}_${paragraphIndex}_$position")
                                        .put("name", roleCardName)
                                        .put("badges", JSONArray().put("AI"))
                                        .put("content", comment.optString("text"))
                                        .put("kind", comment.optString("kind"))
                                        .put("createdAt", comment.optLong("createdAt")),
                                )
                            }
                        },
                    ),
            )
        }
        if (fileStore.hasCatalog(bookId)) {
            return success(JSONObject().put("ready", false).put("comments", JSONArray()))
        }
        val chapter = store.getAutoCommentChapter(bookId, chapterIndex)
            ?: return success(JSONObject().put("ready", false).put("comments", JSONArray()))
        if (
            chapter.status != ReadingCompanionStore.AUTO_COMMENT_STATUS_READY ||
            chapter.generationPolicyVersion != AutoCommentSupport.GENERATION_POLICY_VERSION ||
            (expectedHash != null && expectedHash != chapter.contentHash)
        ) {
            return success(JSONObject().put("ready", false).put("comments", JSONArray()))
        }
        val comments = store.getAutoComments(bookId, chapterIndex, paragraphIndex)
            .filter { comment ->
                !comment.roleCardId.isNullOrBlank() && !comment.roleCardName.isNullOrBlank()
            }
        return success(
            JSONObject()
                .put("ready", true)
                .put(
                    "comments",
                    JSONArray().apply {
                        comments.forEach { comment ->
                            put(
                                JSONObject()
                                    .put("id", comment.id)
                                    .put("name", comment.roleCardName)
                                    .put("badges", JSONArray().put("AI"))
                                    .put("content", comment.text)
                                    .put("kind", comment.kind)
                                    .put("createdAt", comment.createdAt)
                            )
                        }
                    },
                ),
        )
    }

    private fun enforceLegadoCaller() {
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return
        val packages = requireNotNull(context).packageManager.getPackagesForUid(callingUid).orEmpty()
        if (packages.none(::isTrustedLegadoPackage)) {
            throw SecurityException("AI 段评数据仅允许 Legado 读取")
        }
    }

    private fun isTrustedLegadoPackage(packageName: String): Boolean =
        LegadoAnnotationTrustPolicy.isTrusted(packageName)

    private fun requireParameter(uri: Uri, name: String): String =
        requireNotNull(uri.getQueryParameter(name)?.takeIf(String::isNotBlank)) {
            "缺少参数 $name"
        }

    private fun success(data: JSONObject): JSONObject =
        JSONObject().put("isSuccess", true).put("data", data)

    private fun failure(message: String): JSONObject =
        JSONObject().put("isSuccess", false).put("errorMsg", message)

    private companion object {
        const val MATCH_SUMMARY = 1
        const val MATCH_DETAIL = 2
        const val RESULT_COLUMN = "result"
        const val METHOD_READING_PROGRESS_CHANGED = "reading_progress_changed"
    }
}

package com.ai.assistance.operit.features.reading

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LegadoReaderProvider(
    context: Context,
) : ReaderProvider {
    private val appContext = context.applicationContext

    override suspend fun getBooks(): List<ReaderBook> = withContext(Dispatchers.IO) {
        val data = query(path = "books/query")
        val array = data as? JSONArray
            ?: throw invalidResponse("Legado 书架响应不是数组")
        buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                add(
                    ReaderBook(
                        id = item.getString("bookUrl"),
                        name = item.optString("name"),
                        author = item.optString("author"),
                        totalChapterCount = item.optInt("totalChapterNum"),
                        lastReadAt = item.optLong("durChapterTime"),
                    )
                )
            }
        }
    }

    override suspend fun getReadingState(bookId: String?): ReadingState =
        withContext(Dispatchers.IO) {
            val data = query(
                path = "reading/snapshot/query",
                parameters = bookId?.let { mapOf("url" to it) }.orEmpty(),
            ) as? JSONObject ?: throw invalidResponse("Legado 阅读快照响应不是对象")
            val book = ReaderBook(
                id = data.getString("bookUrl"),
                name = data.optString("name"),
                author = data.optString("author"),
                totalChapterCount = data.optInt("totalChapterNum"),
                lastReadAt = data.optLong("lastReadAt"),
            )
            ReadingState(
                book = book,
                chapterIndex = data.getInt("currentChapterIndex"),
                chapterTitle = data.optNullableString("currentChapterTitle"),
                layoutPosition = data.optInt("layoutPosition"),
                bodyPosition = data.optNullableInt("bodyPosition"),
                capturedAt = data.optLong("capturedAt"),
            )
        }

    override suspend fun getChapters(bookId: String): List<ReaderChapter> =
        withContext(Dispatchers.IO) {
            val data = query(
                path = "book/chapter/query",
                parameters = mapOf("url" to bookId),
            )
            val array = data as? JSONArray
                ?: throw invalidResponse("Legado 目录响应不是数组")
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    add(
                        ReaderChapter(
                            bookId = bookId,
                            index = item.getInt("index"),
                            title = item.optString("title"),
                        )
                    )
                }
            }
        }

    override suspend fun getReadableChapterContent(
        bookId: String,
        chapterIndex: Int,
    ): ReadableChapterContent = withContext(Dispatchers.IO) {
        val data = try {
            query(
                path = "book/readableContent/query",
                parameters = mapOf(
                    "url" to bookId,
                    "index" to chapterIndex.toString(),
                ),
            )
        } catch (error: ReaderProviderException) {
            val reason = if (
                error.message.orEmpty().contains("拒绝") ||
                error.message.orEmpty().contains("安全正文位置")
            ) {
                ReaderProviderException.Reason.UNSAFE_POSITION
            } else {
                ReaderProviderException.Reason.CHAPTER_READ_FAILED
            }
            throw ReaderProviderException(reason, error.message.orEmpty(), error)
        }
        val item = data as? JSONObject
            ?: throw invalidResponse("Legado 安全正文响应不是对象")
        val content = item.getString("content")
        val readableUntil = item.getInt("readableUntil")
        val responseBookId = item.getString("bookUrl")
        val responseChapterIndex = item.getInt("chapterIndex")
        val readingChapterIndex = item.getInt("readingChapterIndex")
        val isComplete = item.optBoolean("isComplete")
        if (
            responseBookId != bookId ||
            responseChapterIndex != chapterIndex ||
            readableUntil < 0 ||
            readableUntil != content.length ||
            chapterIndex > readingChapterIndex ||
            isComplete != (chapterIndex < readingChapterIndex)
        ) {
            throw ReaderProviderException(
                ReaderProviderException.Reason.UNSAFE_POSITION,
                "Legado 返回的安全正文边界不一致，已拒绝使用",
            )
        }
        ReadableChapterContent(
            bookId = responseBookId,
            chapterIndex = responseChapterIndex,
            chapterTitle = item.optString("chapterTitle"),
            content = content,
            readableUntil = readableUntil,
            isComplete = isComplete,
            readingChapterIndex = readingChapterIndex,
            capturedAt = item.optLong("capturedAt"),
        )
    }

    private fun query(
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): Any? {
        val installedAuthorities = LEGADO_AUTHORITIES.filter { authority ->
            @Suppress("DEPRECATION")
            appContext.packageManager.resolveContentProvider(authority, 0) != null
        }
        if (installedAuthorities.isEmpty()) {
            throw ReaderProviderException(
                ReaderProviderException.Reason.LEGADO_NOT_INSTALLED,
                "未找到兼容的 Legado 阅读数据提供者",
            )
        }
        var lastError: Throwable? = null
        for (authority in installedAuthorities) {
            val uri = Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendEncodedPath(path)
                .apply {
                    parameters.forEach { (name, value) -> appendQueryParameter(name, value) }
                }
                .build()
            try {
                val cursor = appContext.contentResolver.query(
                    uri,
                    arrayOf("result"),
                    null,
                    null,
                    null,
                ) ?: continue
                cursor.use {
                    if (!it.moveToFirst()) {
                        throw invalidResponse("Legado ContentProvider 返回空游标")
                    }
                    val column = it.getColumnIndex("result")
                    if (column < 0) {
                        throw invalidResponse("Legado ContentProvider 缺少 result 列")
                    }
                    val root = JSONObject(it.getString(column))
                    if (!root.optBoolean("isSuccess")) {
                        throw ReaderProviderException(
                            ReaderProviderException.Reason.INVALID_RESPONSE,
                            root.optString("errorMsg", "Legado 请求失败"),
                        )
                    }
                    return root.opt("data").takeUnless { value -> value === JSONObject.NULL }
                }
            } catch (error: ReaderProviderException) {
                throw error
            } catch (error: SecurityException) {
                lastError = error
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw ReaderProviderException(
            ReaderProviderException.Reason.CONNECTION_FAILED,
            "无法连接 Legado 阅读数据提供者",
            lastError,
        )
    }

    private fun invalidResponse(message: String) =
        ReaderProviderException(ReaderProviderException.Reason.INVALID_RESPONSE, message)

    private fun JSONObject.optNullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name)

    private companion object {
        val LEGADO_AUTHORITIES = listOf(
            "com.legado.app.release.readerProvider",
            "com.legado.app.debug.readerProvider",
            "com.legado.app.readerProvider",
        )
    }
}

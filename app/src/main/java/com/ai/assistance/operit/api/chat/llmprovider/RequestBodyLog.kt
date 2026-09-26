package com.ai.assistance.operit.api.chat.llmprovider

import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/** Serializes diagnostics in bounded chunks without cloning the request or its large strings. */
internal object RequestBodyLog {
    fun write(tag: String, prefix: String, body: JSONObject) {
        if (!AppLogger.logRequestBodies) return
        var part = 0
        chunks(body) { chunk -> AppLogger.d(tag, "$prefix Part ${++part}: $chunk") }
    }

    internal fun chunks(body: JSONObject, chunkSize: Int = 3000, emit: (String) -> Unit) {
        require(chunkSize >= 2)
        val buffer = StringBuilder(chunkSize)
        fun char(value: Char) {
            // Log chunks are encoded separately. Keep a UTF-16 surrogate pair in the same chunk.
            if (buffer.length == chunkSize) {
                val pending = buffer.last()
                buffer.setLength(buffer.length - 1)
                emit(buffer.toString())
                buffer.setLength(0)
                buffer.append(pending)
            }
            buffer.append(value)
            if (buffer.length == chunkSize && !value.isHighSurrogate()) {
                emit(buffer.toString())
                buffer.setLength(0)
            }
        }
        fun text(value: String) = value.forEach(::char)
        fun quoted(value: String) {
            char('"')
            value.forEach { c ->
                when (c) {
                    '"' -> text("\\\"")
                    '\\' -> text("\\\\")
                    '\b' -> text("\\b")
                    '\u000c' -> text("\\f")
                    '\n' -> text("\\n")
                    '\r' -> text("\\r")
                    '\t' -> text("\\t")
                    else -> if (c < ' ') {
                        text("\\u")
                        val hex = "0123456789abcdef"
                        char('0')
                        char('0')
                        char(hex[c.code ushr 4])
                        char(hex[c.code and 15])
                    } else char(c)
                }
            }
            char('"')
        }
        fun value(item: Any?, key: String? = null, imageData: Boolean = false, root: Boolean = false) {
            when (item) {
                null, JSONObject.NULL -> text("null")
                is JSONObject -> {
                    char('{')
                    var first = true
                    val mime = sequenceOf("media_type", "mime_type", "mimeType")
                        .map { item.optString(it, "") }.any { it.startsWith("image/", true) }
                    val keys = item.keys()
                    while (keys.hasNext()) {
                        val name = keys.next()
                        if (!first) char(',')
                        first = false
                        quoted(name)
                        char(':')
                        val child = item.get(name)
                        if (root && name == "tools" && child is JSONArray) {
                            quoted("[${child.length()} tools omitted for brevity]")
                        } else value(child, name, mime && name == "data")
                    }
                    char('}')
                }
                is JSONArray -> {
                    char('[')
                    for (i in 0 until item.length()) {
                        if (i > 0) char(',')
                        value(item.get(i))
                    }
                    char(']')
                }
                is String -> {
                    val dataUrl = item.startsWith("data:") && item.contains(";base64,")
                    val base64 = key == "data" && item.length > 256 &&
                        item.all { it.isLetterOrDigit() || it in "+/=\n\r" }
                    quoted(when {
                        imageData || dataUrl -> "[image base64 omitted, length=${item.length}]"
                        base64 -> "[base64 omitted, length=${item.length}]"
                        else -> item
                    })
                }
                is Number -> text(JSONObject.numberToString(item))
                is Boolean -> text(item.toString())
                else -> quoted(item.toString())
            }
        }
        value(body, root = true)
        if (buffer.isNotEmpty()) emit(buffer.toString())
    }
}

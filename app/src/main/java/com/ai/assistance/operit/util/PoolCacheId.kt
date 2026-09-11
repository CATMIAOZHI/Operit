package com.ai.assistance.operit.util

/**
 * 池缓存 id 能否直接当作缓存文件名使用。
 *
 * 图片池与媒体池都把 id 拼进文件名（`<id>.dat`、`<id>.meta`、`<id>.b64`），而 id 还可能来自
 * 消息里的 `<link ... id="...">` 标签。`../x`、`a/b` 这类取值会让读写删落到缓存目录之外，
 * 所以只有简单名字才允许进入磁盘读写。
 */
internal fun isSimplePoolCacheId(id: String): Boolean {
    if (id.isBlank() || id == "error") {
        return false
    }
    return !id.contains('/') && !id.contains('\\') && !id.contains("..")
}

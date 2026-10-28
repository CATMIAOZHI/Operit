package com.ai.assistance.operit.features.orbit

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Built-in PROMPT.md and IDENTITY.md for a new Orbit, kept as Markdown in
 * assets/orbit_prompt/<zh|en>/ so they are easy to read and edit. The structure follows Muse's full
 * system prompt, cut down to what Orbit can do inside Operit today; a Muse feature Orbit does not
 * have yet lives in docs/TODO/orbit_muse_parity/index.md. Change both languages together.
 */
internal object OrbitPromptDefaults {
    private const val ROOT = "orbit_prompt"
    private val cache = ConcurrentHashMap<String, String>()

    private fun read(context: Context, english: Boolean, file: String): String {
        val path = "$ROOT/${if (english) "en" else "zh"}/$file"
        return cache.getOrPut(path) {
            context.applicationContext.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                .replace("\r\n", "\n").trim()
        }
    }

    fun prompt(context: Context, english: Boolean): String = read(context, english, "PROMPT.md")

    fun identity(context: Context, english: Boolean): String = read(context, english, "IDENTITY.md")
}
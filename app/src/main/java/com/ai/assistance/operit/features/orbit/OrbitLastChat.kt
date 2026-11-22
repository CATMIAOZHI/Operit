package com.ai.assistance.operit.features.orbit

import android.content.Context

/**
 * The chat of each Orbit the user had on screen last, main or side, so the page opens there again
 * after it was rebuilt (the app reopened, or brought back from a notification).
 */
internal object OrbitLastChat {
    private const val PREFS = "orbit_last_chat"

    fun get(context: Context, orbitId: String): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(orbitId, null)

    fun set(context: Context, orbitId: String, chatId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(orbitId, null) != chatId) prefs.edit().putString(orbitId, chatId).apply()
    }
}

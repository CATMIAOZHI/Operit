package com.ai.assistance.operit.core.tools.defaultTool.websession.browser

import android.content.Context
import android.net.Uri
import android.webkit.PermissionRequest
import java.util.Locale

/**
 * Per-site answers to camera, microphone and location requests, kept per login profile. A site
 * with no saved answer goes through the usual Android permission prompt.
 */
internal object BrowserSitePermissions {
    private const val PREFS = "browser_site_permissions"
    val KINDS = listOf("camera", "microphone", "location", "protected_media", "midi")

    fun kindForResource(resource: String): String? =
        when (resource) {
            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"
            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> "protected_media"
            PermissionRequest.RESOURCE_MIDI_SYSEX -> "midi"
            else -> null
        }

    /** "https://example.com" from any URL on the site; null when it has no web origin. */
    fun originOf(raw: String): String? {
        val uri = runCatching { Uri.parse(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = uri.port.takeIf { it > 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    private fun key(profile: String, origin: String, kind: String) = "$profile\u0001$origin\u0001$kind"

    fun decision(context: Context, profile: String, origin: String, kind: String): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val value = prefs.getString(key(profile, origin, kind), null) ?: return null
        return value == "allow"
    }

    /** [allow] null forgets the answer; kind "all" covers every kind. */
    fun set(context: Context, profile: String, origin: String, kind: String, allow: Boolean?) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val kinds = if (kind == "all") KINDS else listOf(kind)
        kinds.forEach { k ->
            if (allow == null) {
                editor.remove(key(profile, origin, k))
            } else {
                editor.putString(key(profile, origin, k), if (allow) "allow" else "deny")
            }
        }
        editor.apply()
    }

    /** (origin, kind, allowed) for one profile. */
    fun list(context: Context, profile: String): List<Triple<String, String, Boolean>> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) ->
            val parts = k.split('\u0001')
            if (parts.size != 3 || parts[0] != profile) null else Triple(parts[1], parts[2], v == "allow")
        }.sortedWith(compareBy({ it.first }, { it.second }))

    /** Drops every saved answer of a deleted Orbit's profile. */
    fun clearProfile(context: Context, profile: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$profile\u0001") }.forEach(editor::remove)
        editor.apply()
    }
}

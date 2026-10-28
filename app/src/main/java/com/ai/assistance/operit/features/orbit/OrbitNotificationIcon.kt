package com.ai.assistance.operit.features.orbit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import com.ai.assistance.operit.data.preferences.OrbitAppearance
import com.ai.assistance.operit.data.preferences.OrbitProfile
import com.ai.assistance.operit.ui.features.orbit.OrbitLogoDefaults

/**
 * Orbit's picture for a notification: its own avatar cut to a circle, or, without one, its logo in
 * its chosen colors on white, drawn the way the chat draws it at rest.
 */
internal object OrbitNotificationIcon {
    const val SIZE = 192
    private const val USER_HEX = OrbitLogoDefaults.USER_HEX
    private const val ORBIT_HEX = OrbitLogoDefaults.ORBIT_HEX

    /** [loadPicture] reads the avatar uri; null when the picture cannot be read, then the logo is used. */
    fun of(orbit: OrbitProfile, loadPicture: (String) -> Bitmap?): Bitmap? = runCatching {
        orbit.avatarUri?.takeIf { it.isNotBlank() }?.let(loadPicture)?.let(::circle) ?: logo(orbit)
    }.getOrNull()

    private fun circle(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        val side = minOf(source.width, source.height).toFloat()
        val scale = SIZE / side
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-(source.width - side) / 2f * scale, -(source.height - side) / 2f * scale)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        }
        canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, paint)
        return out
    }

    private fun color(hex: String?, fallback: String): Int =
        android.graphics.Color.parseColor(OrbitAppearance.normalizeColor(hex) ?: fallback)

    private fun logo(orbit: OrbitProfile): Bitmap {
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val c = SIZE / 2f
        canvas.drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        // The same proportions as the chat avatar: the logo fills the circle less an 18% margin.
        val unit = SIZE * (1f - 2 * 0.18f) / 24f
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f * unit
            strokeCap = Paint.Cap.ROUND
            color = color(orbit.appearance.logoOrbitColor, ORBIT_HEX)
        }
        val arc = RectF(c - 10f * unit, c - 10f * unit, c + 10f * unit, c + 10f * unit)
        canvas.drawArc(arc, -33.5f, 133.5f, false, stroke)
        canvas.drawArc(arc, 146.5f, 133.5f, false, stroke)
        canvas.drawCircle(c + 7f * unit, c - 7f * unit, 2f * unit, stroke)
        canvas.drawCircle(c - 7f * unit, c + 7f * unit, 2f * unit, stroke)
        stroke.color = color(orbit.appearance.logoUserColor, USER_HEX)
        canvas.drawCircle(c, c, 3f * unit, stroke)
        return out
    }
}

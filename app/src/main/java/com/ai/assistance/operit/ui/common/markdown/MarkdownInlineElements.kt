package com.ai.assistance.operit.ui.common.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.ai.assistance.operit.ui.common.dynamicui.cardFlowIcons
import com.ai.assistance.operit.ui.common.dynamicui.cardSemanticToneColors
import com.ai.assistance.operit.util.markdown.InlineDirectives
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor

private const val BADGE_TEXT_SCALE = 0.82f

/** Android paths for the shared icon set, so spans can draw them on a plain Canvas. */
private object InlineIconPaths {
    class Icon(val viewport: Float, val paths: List<android.graphics.Path>)

    private val cache = ConcurrentHashMap<String, Icon>()

    fun isKnown(name: String): Boolean = name in cardFlowIcons

    fun get(name: String): Icon? {
        cache[name]?.let { return it }
        val vector = cardFlowIcons[name] ?: return null
        val paths = ArrayList<android.graphics.Path>()
        collect(vector.root, paths)
        return cache.getOrPut(name) { Icon(vector.viewportWidth, paths) }
    }

    private fun collect(group: VectorGroup, out: MutableList<android.graphics.Path>) {
        for (node in group) {
            when (node) {
                is VectorGroup -> collect(node, out)
                is VectorPath -> out += node.pathData.toPath().asAndroidPath().apply {
                    fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                        android.graphics.Path.FillType.EVEN_ODD
                    } else {
                        android.graphics.Path.FillType.WINDING
                    }
                }
            }
        }
    }
}

private fun drawInlineIcon(canvas: Canvas, name: String, left: Float, top: Float, size: Float, color: Int) {
    val icon = InlineIconPaths.get(name) ?: return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }
    canvas.save()
    canvas.translate(left, top)
    canvas.scale(size / icon.viewport, size / icon.viewport)
    icon.paths.forEach { canvas.drawPath(it, paint) }
    canvas.restore()
}

/** Grows the line only when the element is taller than the text, centered like inline formulas. */
private fun fitCentered(paint: Paint, height: Float, metrics: Paint.FontMetricsInt?) {
    metrics ?: return
    val text = paint.fontMetricsInt
    val center = (text.ascent + text.descent) / 2f
    metrics.ascent = minOf(text.ascent, floor(center - height / 2f).toInt())
    metrics.descent = maxOf(text.descent, ceil(center + height / 2f).toInt())
    metrics.top = minOf(text.top, metrics.ascent)
    metrics.bottom = maxOf(text.bottom, metrics.descent)
}

private fun mediumTypeface(base: Typeface?): Typeface =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(base ?: Typeface.DEFAULT, 500, false)
    } else {
        Typeface.create(base ?: Typeface.DEFAULT, Typeface.BOLD)
    }

/** A rounded status pill drawn over its own label, so copying the text still yields the label. */
internal class InlineBadgeSpan(
    private val ink: Int,
    private val fill: Int,
    private val icon: String?,
    density: Float,
) : ReplacementSpan() {
    private val padH = 7f * density
    private val padV = 1.5f * density
    private val gap = 3f * density

    private fun labelPaint(paint: Paint) = Paint(paint).apply {
        textSize = paint.textSize * BADGE_TEXT_SCALE
        typeface = mediumTypeface(paint.typeface)
        isFakeBoldText = false
        isUnderlineText = false
        isStrikeThruText = false
        isAntiAlias = true
        color = ink
    }

    private fun iconSize(label: Paint) = if (icon == null) 0f else label.textSize * 1.05f

    private fun width(label: Paint, text: CharSequence, start: Int, end: Int): Float {
        val size = iconSize(label)
        return padH * 2 + label.measureText(text, start, end) + if (size > 0f) size + gap else 0f
    }

    private fun height(label: Paint) = label.descent() - label.ascent() + padV * 2

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val label = labelPaint(paint)
        fitCentered(paint, height(label), fm)
        return ceil(width(label, text, start, end) + 2f).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, baseline: Int, bottom: Int, paint: Paint,
    ) {
        val label = labelPaint(paint)
        val center = baseline + (paint.ascent() + paint.descent()) / 2f
        val h = height(label)
        val left = x + 1f
        val rect = RectF(left, center - h / 2f, left + width(label, text, start, end), center + h / 2f)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
        var cursor = left + padH
        val size = iconSize(label)
        if (icon != null) {
            drawInlineIcon(canvas, icon, cursor, center - size / 2f, size, ink)
            cursor += size + gap
        }
        canvas.drawText(text, start, end, cursor, center - (label.ascent() + label.descent()) / 2f, label)
    }
}

/** A text-sized icon; without a tone it follows the surrounding text color. */
internal class InlineIconSpan(
    private val name: String,
    private val color: Int?,
    density: Float,
) : ReplacementSpan() {
    private val margin = 1.5f * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val size = paint.textSize
        fitCentered(paint, size, fm)
        return ceil(size + margin * 2).toInt()
    }

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, baseline: Int, bottom: Int, paint: Paint,
    ) {
        val size = paint.textSize
        val center = baseline + (paint.ascent() + paint.descent()) / 2f
        drawInlineIcon(canvas, name, x + margin, center - size / 2f, size, color ?: paint.color)
    }
}

/** Appends plain text, turning `:badge[…]` and `:icon[…]` into native inline elements. */
internal fun appendPlainTextWithInlineElements(
    builder: SpannableStringBuilder,
    text: String,
    textColor: Color,
    primaryColor: Color,
    density: Float?,
) {
    if (!InlineDirectives.mayContain(text)) {
        builder.append(text)
        return
    }
    if (density == null) {
        builder.append(InlineDirectives.toPlainText(text))
        return
    }
    val dark = textColor.luminance() > 0.5f
    // Theme primary is too light for small text; pull it toward the text side for contrast.
    val accentInk = lerp(primaryColor, if (dark) Color.White else Color.Black, 0.3f)
    InlineDirectives.parse(text, InlineIconPaths::isKnown).forEach { segment ->
        when (segment) {
            is InlineDirectives.Text -> builder.append(segment.text)
            is InlineDirectives.Badge -> {
                val (ink, fill) = when (segment.tone) {
                    "accent" -> accentInk to primaryColor.copy(alpha = 0.14f)
                    else -> cardSemanticToneColors(segment.tone, dark)
                        ?: (textColor.copy(alpha = 0.78f) to textColor.copy(alpha = 0.09f))
                }
                val start = builder.length
                builder.append(segment.label)
                builder.setSpan(
                    InlineBadgeSpan(ink.toArgb(), fill.toArgb(), segment.icon, density),
                    start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            is InlineDirectives.Icon -> {
                val color = when (segment.tone) {
                    "accent" -> accentInk
                    else -> cardSemanticToneColors(segment.tone, dark)?.first
                }
                val start = builder.length
                builder.append(INLINE_LATEX_PLACEHOLDER)
                builder.setSpan(
                    InlineIconSpan(segment.name, color?.toArgb(), density),
                    start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }
}

package com.ai.assistance.operit.ui.common.dynamicui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.R
import kotlin.math.*

internal data class CardChartPoint(val label: String, val x: Double, val y: Double)
internal data class CardChartSeries(val label: String, val points: List<CardChartPoint>)

/** Runtime validation also covers values supplied through bindings. */
internal fun cardChartSeries(value: Any?, labels: List<String>, type: String): List<CardChartSeries> {
    require(type in setOf("line", "bar", "pie", "scatter"))
    val raw = value as? List<*> ?: error("Expected chart series")
    require(raw.size <= if (type == "pie") 1 else 4)
    fun finite(v: Any?): Double = (v as? Number)?.toDouble()?.also {
        require(it.isFinite() && abs(it) <= 1e12)
    } ?: error("Invalid chart number")
    return raw.map { item ->
        val series = item as? Map<*, *> ?: error("Invalid series")
        val name = (series["label"] as? String).orEmpty().take(120)
        val points = if (type == "scatter") {
            val data = series["points"] as? List<*> ?: error("Missing points")
            require(data.size <= 128)
            data.mapIndexed { i, v ->
                val p = v as? Map<*, *> ?: error("Invalid point")
                CardChartPoint((p["label"] as? String) ?: (i + 1).toString(), finite(p["x"]), finite(p["y"]))
            }
        } else {
            val data = series["values"] as? List<*> ?: error("Missing values")
            require(data.size <= 128 && (labels.isEmpty() || labels.size == data.size))
            data.mapIndexed { i, v ->
                val y = finite(v)
                if (type == "pie") require(y >= 0)
                CardChartPoint(labels.getOrNull(i) ?: (i + 1).toString(), i.toDouble(), y)
            }
        }
        CardChartSeries(name, points)
    }.also { data ->
        if (type != "scatter" && data.isNotEmpty()) require(data.all { it.points.size == data.first().points.size })
    }
}

@Composable
internal fun CardChart(type: String, title: String, data: List<CardChartSeries>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val palette = listOf(primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.error)
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var selected by remember(data) { mutableStateOf<Pair<Int, Int>?>(null) }
    var showData by rememberSaveable { mutableStateOf(false) }
    val points = remember(data) { data.flatMap { it.points } }
    val pieColors = remember(points.size, primary) {
        List(points.size) { i -> if (i == 0) primary else Color.hsv((i * 137.508f) % 360f, .65f, .78f) }
    }
    val empty = points.isEmpty() || (type == "pie" && points.sumOf { it.y } <= 0)
    val chartDescription = stringResource(R.string.dynamic_ui_chart_description, title, points.size)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleMedium)
        if (empty) Text(stringResource(R.string.dynamic_ui_no_data), style = MaterialTheme.typography.bodyMedium)
        else {
            val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }.let { if (it == minX) it + 1 else it }
            val low = points.minOf { it.y }.let { if (type == "bar") minOf(0.0, it) else it }
            val high = points.maxOf { it.y }.let { if (type == "bar") maxOf(0.0, it) else it }
            val minY = if (low == high) low - 1 else low
            val maxY = if (low == high) high + 1 else high
            val tickStyle = TextStyle(color = ink, fontSize = 10.sp)
            val tickLabels = (0..3).map { i -> measurer.measure(String.format("%.2g", maxY - (maxY - minY) * i / 3), tickStyle) }
            val left = tickLabels.maxOf { it.size.width }.toFloat() + with(density) { 8.dp.toPx() }
            val right = with(density) { 16.dp.toPx() }
            val top = with(density) { 12.dp.toPx() }
            val bottom = with(density) { 28.dp.toPx() }
            fun plotWidth(w: Float) = (w - left - right).coerceAtLeast(1f)
            fun barWidth(w: Float) = plotWidth(w) / data.first().points.size.coerceAtLeast(1) * .7f / data.size
            fun position(p: CardChartPoint, w: Float, h: Float, seriesIndex: Int = 0): Offset {
                val x = if (type == "bar") {
                    val slot = plotWidth(w) / data.first().points.size.coerceAtLeast(1)
                    left + slot * (p.x.toFloat() + .15f) + (seriesIndex + .5f) * barWidth(w)
                } else left + ((p.x - minX) / (maxX - minX)).toFloat() * plotWidth(w)
                return Offset(x, top + ((maxY - p.y) / (maxY - minY)).toFloat() * (h - top - bottom).coerceAtLeast(1f))
            }
            Canvas(Modifier.fillMaxWidth().height(210.dp).semantics { contentDescription = chartDescription }
                .pointerInput(data, type, left, top, bottom, right) {
                    detectTapGestures { tap ->
                        if (type == "pie") {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            if ((tap - center).getDistance() <= minOf(size.width, size.height) * .43f) {
                                val angle = ((atan2(tap.y - center.y, tap.x - center.x) * 180 / PI + 90 + 360) % 360).toFloat()
                                val total = points.sumOf { it.y }; var end = 0f
                                val index = points.indexOfFirst { end += (it.y / total * 360).toFloat(); angle < end }
                                if (index >= 0) selected = 0 to index
                            }
                        } else {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            val zeroY = position(CardChartPoint("", minX, 0.0), w, h).y
                            selected = data.flatMapIndexed { s, series -> series.points.mapIndexed { i, p ->
                                val pos = position(p, w, h, s)
                                // Distance to the drawn bar rectangle, not its top point.
                                val distance = if (type == "bar") {
                                    val halfWidth = barWidth(w).coerceAtLeast(1f) / 2
                                    val dx = maxOf(abs(tap.x - pos.x) - halfWidth, 0f)
                                    val dy = maxOf(minOf(pos.y, zeroY) - tap.y, tap.y - maxOf(pos.y, zeroY), 0f)
                                    hypot(dx, dy)
                                } else (pos - tap).getDistance()
                                Triple(s, i, distance)
                            } }.minByOrNull { it.third }?.let { it.first to it.second }
                        }
                    }
                }) {
                val w = size.width; val h = size.height
                if (type == "pie") {
                    val diameter = min(w, h) * .86f
                    val corner = Offset((w - diameter) / 2, (h - diameter) / 2)
                    val total = points.sumOf { it.y }; var start = -90f
                    points.forEachIndexed { i, p ->
                        val sweep = (p.y / total * 360).toFloat()
                        drawArc(pieColors[i], start, sweep, true, corner, Size(diameter, diameter))
                        start += sweep
                    }
                } else {
                    repeat(4) { i ->
                        val y = maxY - (maxY - minY) * i / 3
                        val pos = position(CardChartPoint("", minX, y), w, h)
                        drawLine(grid, Offset(left, pos.y), Offset(w - right, pos.y), 1f)
                        drawText(tickLabels[i], topLeft = Offset(0f, pos.y - tickLabels[i].size.height / 2))
                    }
                    val zeroY = position(CardChartPoint("", minX, 0.0), w, h).y
                    data.forEachIndexed { s, series ->
                        val color = palette[s % palette.size]
                        val path = Path()
                        series.points.forEachIndexed { i, p ->
                            val pos = position(p, w, h, s)
                            when (type) {
                                "bar" -> {
                                    val width = barWidth(w).coerceAtLeast(1f)
                                    // Bars occupy full category slots; no half-bar clipping at the ends.
                                    val x = pos.x - width / 2
                                    drawRect(color, Offset(x, minOf(pos.y, zeroY)), Size(width, abs(zeroY - pos.y).coerceAtLeast(1f)))
                                }
                                "scatter" -> drawCircle(color, 4.dp.toPx(), pos)
                                else -> {
                                    if (i == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y)
                                    if (series.points.size == 1) drawCircle(color, 4.dp.toPx(), pos)
                                }
                            }
                        }
                        if (type == "line") drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                        selected?.takeIf { it.first == s }?.second?.let { i ->
                            series.points.getOrNull(i)?.let { drawCircle(ink, 5.dp.toPx(), position(it, w, h, s)) }
                        }
                    }
                    val labels = if (type == "scatter") (0..3).map { i ->
                        val x = minX + (maxX - minX) * i / 3
                        CardChartPoint(String.format("%.2g", x), x, 0.0)
                    } else data.first().points
                    val step = max(1, ceil(labels.size / 4.0).toInt())
                    labels.forEachIndexed { i, p ->
                        if (i % step == 0 || i == labels.lastIndex) {
                            val x = if (type == "bar") left + plotWidth(w) / labels.size * (i + .5f) else position(p, w, h).x
                            val layout = measurer.measure(p.label.take(8), TextStyle(color = ink, fontSize = 10.sp))
                            drawText(layout, topLeft = Offset((x - layout.size.width / 2).coerceIn(0f, (w - layout.size.width).coerceAtLeast(0f)), h - bottom + 4.dp.toPx()))
                        }
                    }
                }
            }
            if (type == "pie") Column(Modifier.heightIn(max = 180.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                val total = points.sumOf { it.y }
                points.forEachIndexed { i, point ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("●", color = pieColors[i])
                        Text("${point.label}: ${point.y} (${String.format("%.1f", point.y / total * 100)}%)", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else if (data.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                data.forEachIndexed { i, series -> Text("● ${series.label}", color = palette[i % palette.size], style = MaterialTheme.typography.labelMedium) }
            }
            selected?.let { (s, i) -> data.getOrNull(s)?.points?.getOrNull(i)?.let { p ->
                Text("${data[s].label} ${p.label}: ${p.y}" + if (type == "scatter") " (x=${p.x})" else "", style = MaterialTheme.typography.labelLarge)
            } }
            TextButton(onClick = { showData = !showData }) { Text(stringResource(R.string.dynamic_ui_chart_data)) }
            if (showData) Column(Modifier.heightIn(max = 240.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                data.forEach { series ->
                    if (series.label.isNotBlank()) Text(series.label, style = MaterialTheme.typography.labelLarge)
                    series.points.forEach { p -> Text("${p.label}: ${p.y}" + if (type == "scatter") " (x=${p.x})" else "", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

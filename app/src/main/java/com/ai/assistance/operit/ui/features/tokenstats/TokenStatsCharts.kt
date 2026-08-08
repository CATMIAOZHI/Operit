package com.ai.assistance.operit.ui.features.tokenstats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.stats.TokenStatsGranularity
import com.ai.assistance.operit.data.stats.TokenStatsTrendBucket
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.pow

/**
 * 统计图表（阶段 4）：纯 Compose Canvas 实现，不引入重型图表依赖。
 *
 * 交互契约（避免与页面滚动互抢）：
 * - 点击与**水平拖动**才选中/切换桶详情（[detectTapGestures] +
 *   [detectHorizontalDragGestures]，与 Rainytoken 参考实现一致）；
 * - 垂直手势不消费，LazyColumn 纵向滚动不受影响；
 * - 桶详情以图表下方的 tooltip 卡片呈现（无悬浮层，不遮挡内容）。
 *
 * 空桶由阶段 3 聚合器补齐（buckets 已含全零桶），图表直接绘制；全部为 0
 * 时由调用方传入 [emptyText] 显示空提示。unknown 不当作 0：调用方通过
 * [unknownNote] 在 tooltip 里给出“部分数据未知”提示。
 */

/** 堆叠柱状图：每桶若干堆叠分量（值 + 颜色）。 */
@Composable
internal fun TokenStatsStackedBarChart(
    modifier: Modifier = Modifier,
    buckets: List<TokenStatsTrendBucket>,
    granularity: TokenStatsGranularity,
    zone: ZoneId,
    formatValue: (Double) -> String,
    emptyText: String,
    chartLabel: String = "",
    stackSelector: (TokenStatsTrendBucket) -> List<Pair<Double, Color>>,
    stackLabels: (TokenStatsTrendBucket) -> List<String>,
    /**
     * tooltip/无障碍里的“合计”数值。默认 = 堆叠分量之和（诊断口径）；调用方可
     * 传入 canonical 合计（如 [com.ai.assistance.operit.data.stats.TokenStatsTotals.totalTokens]）
     * 使展示总 Token 与聚合器口径一致，堆叠分量仍作为诊断明细展示。
     */
    stackTotalSelector: (TokenStatsTrendBucket) -> Double = { bucket ->
        stackSelector(bucket).sumOf { it.first }
    },
    unknownNote: (TokenStatsTrendBucket) -> String? = { null },
    legendItems: List<Pair<String, Color>> = emptyList(),
) {
    if (buckets.isEmpty()) {
        ChartEmptyText(emptyText, modifier)
        return
    }
    val colors = LocalTokenStatsColors.current
    var selectedIndex by remember(buckets) { mutableIntStateOf(buckets.lastIndex) }
    val density = LocalDensity.current
    val d = density.density
    val chartHPx = 160f * d
    val labelHPx = 20f * d

    // 无障碍文案预取：semantics 块不是 Composable，不能在块内解析资源（P1-8）
    val summaryTemplate = stringResource(R.string.token_stats_chart_summary)
    val bucketPositionTemplate = stringResource(R.string.token_stats_chart_bucket_of)
    val prevBucketLabel = stringResource(R.string.token_stats_chart_prev_bucket)
    val nextBucketLabel = stringResource(R.string.token_stats_chart_next_bucket)

    val maxVal = buckets.maxOf { bucket -> stackSelector(bucket).sumOf { it.first } }.coerceAtLeast(0.0)
    val refTop = niceCeil(maxVal)
    val refHalf = refTop / 2.0
    val scale = refTop

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availPx = maxWidth.value * d
        val barAreaPx = availPx / buckets.size
        val barW = barAreaPx * 0.65f

        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .semantics(mergeDescendants = true) {
                        val selected = buckets[selectedIndex]
                        val stacks = stackSelector(selected)
                        val positionText =
                            String.format(bucketPositionTemplate, selectedIndex + 1, buckets.size)
                        val summary = String.format(
                            summaryTemplate,
                            chartLabel,
                            bucketTimeLabel(selected.bucketStartMs, granularity, zone),
                            positionText,
                            formatValue(stackTotalSelector(selected)),
                        )
                        val rows = stacks.mapIndexedNotNull { index, (value, color) ->
                            val label = stackLabels(selected).getOrNull(index) ?: ""
                            if (value > 0.0 || label.isNotEmpty()) {
                                "${label.ifEmpty { "" }} ${formatValue(value)}".trim()
                            } else {
                                null
                            }
                        }
                        contentDescription = chartAccessibilityDescription(summary, rows)
                        stateDescription = positionText
                        role = Role.Image
                        customActions = listOf(
                            CustomAccessibilityAction(prevBucketLabel) {
                                previousBucketIndex(selectedIndex, buckets.size)
                                    ?.let { selectedIndex = it; true } ?: false
                            },
                            CustomAccessibilityAction(nextBucketLabel) {
                                nextBucketIndex(selectedIndex, buckets.size)
                                    ?.let { selectedIndex = it; true } ?: false
                            },
                        )
                    }
                    .focusable()
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            val idx = (offset.x / barAreaPx).toInt().coerceIn(0, buckets.lastIndex)
                            selectedIndex = idx
                        }
                    }
                    .pointerInput(buckets) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val idx = (change.position.x / barAreaPx).toInt().coerceIn(0, buckets.lastIndex)
                            selectedIndex = idx
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    val chartH = chartHPx
                    buckets.forEachIndexed { i, bucket ->
                        val x = i * barAreaPx + (barAreaPx - barW) / 2
                        var yBase = chartH
                        stackSelector(bucket).forEach { (value, color) ->
                            val h = (value / scale * chartH).toFloat().coerceAtLeast(0f)
                            drawRect(color, Offset(x, yBase - h), Size(barW, h))
                            yBase -= h
                        }
                        val (label, show) = bucketLabel(bucket.bucketStartMs, granularity, zone, i, buckets.size)
                        if (show) {
                            drawContext.canvas.nativeCanvas.drawText(
                                label, x + barW / 2, chartH + labelHPx - 4f * d,
                                android.graphics.Paint().apply {
                                    color = colors.chartLabel.toArgb()
                                    textSize = 10f * d * density.fontScale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                            )
                        }
                    }
                    // 参考线（满刻度与半刻度）+ 数值标签
                    val refY = chartH - (refTop / scale * chartH).toFloat()
                    val refHalfY = chartH - (refHalf / scale * chartH).toFloat()
                    drawLine(colors.chartGrid, Offset(0f, refY), Offset(size.width, refY), strokeWidth = 0.5f * d)
                    drawLine(colors.chartGrid, Offset(0f, refHalfY), Offset(size.width, refHalfY), strokeWidth = 0.5f * d)
                    val paint = android.graphics.Paint().apply {
                        color = colors.chartLabel.toArgb()
                        textSize = 8f * d * density.fontScale
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refTop), 2f * d, refY - 2f * d, paint)
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refHalf), 2f * d, refHalfY - 2f * d, paint)
                }
            }

            if (legendItems.isNotEmpty()) {
                ChartLegend(legendItems)
            }

            val selected = buckets[selectedIndex]
            val stacks = stackSelector(selected)
            ChartTooltip(
                title = bucketTimeLabel(selected.bucketStartMs, granularity, zone),
                rows = stacks.mapIndexedNotNull { index, (value, color) ->
                    val label = stackLabels(selected).getOrNull(index) ?: ""
                    if (value > 0.0 || label.isNotEmpty()) {
                        Triple(color, label, formatValue(value))
                    } else {
                        null
                    }
                },
                total = formatValue(stackTotalSelector(selected)),
                unknownNote = unknownNote(selected),
            )
        }
    }
}

/** 折线图：每桶一个值；无有效样本的桶不画点、线段断开。 */
@Composable
internal fun TokenStatsLineChart(
    modifier: Modifier = Modifier,
    buckets: List<TokenStatsTrendBucket>,
    granularity: TokenStatsGranularity,
    zone: ZoneId,
    formatValue: (Double) -> String,
    emptyText: String,
    chartLabel: String = "",
    valueSelector: (TokenStatsTrendBucket) -> Double?,
    unknownNote: (TokenStatsTrendBucket) -> String? = { null },
) {
    if (buckets.isEmpty()) {
        ChartEmptyText(emptyText, modifier)
        return
    }
    val colors = LocalTokenStatsColors.current
    var selectedIndex by remember(buckets) { mutableIntStateOf(buckets.lastIndex) }
    val density = LocalDensity.current
    val d = density.density
    val chartHPx = 140f * d
    val labelHPx = 20f * d

    // 无障碍文案预取：semantics 块不是 Composable，不能在块内解析资源（P1-8）
    val summaryTemplate = stringResource(R.string.token_stats_chart_summary)
    val bucketPositionTemplate = stringResource(R.string.token_stats_chart_bucket_of)
    val prevBucketLabel = stringResource(R.string.token_stats_chart_prev_bucket)
    val nextBucketLabel = stringResource(R.string.token_stats_chart_next_bucket)

    val knownValues = buckets.mapNotNull(valueSelector)
    val maxVal = (knownValues.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
    val refTop = niceCeil(maxVal)
    val refHalf = refTop / 2.0
    val scale = refTop

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availPx = maxWidth.value * d
        val barAreaPx = availPx / buckets.size

        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .semantics(mergeDescendants = true) {
                        val selected = buckets[selectedIndex]
                        val value = valueSelector(selected)
                        val positionText =
                            String.format(bucketPositionTemplate, selectedIndex + 1, buckets.size)
                        val summary = String.format(
                            summaryTemplate,
                            chartLabel,
                            bucketTimeLabel(selected.bucketStartMs, granularity, zone),
                            positionText,
                            if (value == null) "" else formatValue(value),
                        )
                        contentDescription = chartAccessibilityDescription(summary, emptyList())
                        stateDescription = positionText
                        role = Role.Image
                        customActions = listOf(
                            CustomAccessibilityAction(prevBucketLabel) {
                                previousBucketIndex(selectedIndex, buckets.size)
                                    ?.let { selectedIndex = it; true } ?: false
                            },
                            CustomAccessibilityAction(nextBucketLabel) {
                                nextBucketIndex(selectedIndex, buckets.size)
                                    ?.let { selectedIndex = it; true } ?: false
                            },
                        )
                    }
                    .focusable()
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            val idx = (offset.x / barAreaPx).toInt().coerceIn(0, buckets.lastIndex)
                            selectedIndex = idx
                        }
                    }
                    .pointerInput(buckets) {
                        detectHorizontalDragGestures { change, _ ->
                            change.consume()
                            val idx = (change.position.x / barAreaPx).toInt().coerceIn(0, buckets.lastIndex)
                            selectedIndex = idx
                        }
                    }
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                ) {
                    val points = buckets.mapIndexed { i, bucket ->
                        val value = valueSelector(bucket)
                        if (value == null) {
                            null
                        } else {
                            Offset(i * barAreaPx + barAreaPx / 2, chartHPx - (value / scale * chartHPx).toFloat())
                        }
                    }
                    // 分段连线：null 断段；每段只连接**相邻**有效点（P2 修复，
                    // 此前一直从段首重复连线导致斜率错误）
                    lineSegments(points).forEach { (start, end) ->
                        drawLine(colors.chartAccent, start, end, strokeWidth = 2f * d)
                    }
                    points.forEachIndexed { i, point ->
                        if (point != null) {
                            drawCircle(colors.chartAccent, radius = 3f * d, center = point)
                        }
                    }
                    buckets.forEachIndexed { i, bucket ->
                        val (label, show) = bucketLabel(bucket.bucketStartMs, granularity, zone, i, buckets.size)
                        if (show) {
                            drawContext.canvas.nativeCanvas.drawText(
                                label, i * barAreaPx + barAreaPx / 2, chartHPx + labelHPx - 4f * d,
                                android.graphics.Paint().apply {
                                    color = colors.chartLabel.toArgb()
                                    textSize = 10f * d * density.fontScale
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                            )
                        }
                    }
                    val refY = chartHPx - (refTop / scale * chartHPx).toFloat()
                    val refHalfY = chartHPx - (refHalf / scale * chartHPx).toFloat()
                    drawLine(colors.chartGrid, Offset(0f, refY), Offset(size.width, refY), strokeWidth = 0.5f * d)
                    drawLine(colors.chartGrid, Offset(0f, refHalfY), Offset(size.width, refHalfY), strokeWidth = 0.5f * d)
                    val paint = android.graphics.Paint().apply {
                        color = colors.chartLabel.toArgb()
                        textSize = 8f * d * density.fontScale
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refTop), 2f * d, refY - 2f * d, paint)
                    drawContext.canvas.nativeCanvas.drawText(formatValue(refHalf), 2f * d, refHalfY - 2f * d, paint)
                }
            }

            val selected = buckets[selectedIndex]
            val value = valueSelector(selected)
            ChartTooltip(
                title = bucketTimeLabel(selected.bucketStartMs, granularity, zone),
                rows = value?.let { listOf(Triple(colors.chartAccent, "", formatValue(it))) } ?: emptyList(),
                total = if (value == null) null else formatValue(value),
                unknownNote = unknownNote(selected),
            )
        }
    }
}

/** tooltip 卡片（图表下方，不遮挡内容）。 */
@Composable
private fun ChartTooltip(
    title: String,
    rows: List<Triple<Color, String, String>>,
    total: String?,
    unknownNote: String?,
) {
    val colors = LocalTokenStatsColors.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = colors.tooltipContainer),
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = colors.tooltipContent,
            )
            total?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.chartAccent,
                )
            }
            rows.forEach { (color, label, value) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(color, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (label.isNotEmpty()) "$label $value" else value,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.tooltipContent,
                    )
                }
            }
            unknownNote?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.unknownHint,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartLegend(items: List<Pair<String, Color>>) {
    val colors = LocalTokenStatsColors.current
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        items.forEachIndexed { i, (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.chartLabel,
                )
            }
            if (i < items.size - 1) Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun ChartEmptyText(text: String, modifier: Modifier = Modifier) {
    val colors = LocalTokenStatsColors.current
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.chartLabel,
        )
    }
}

/** 桶起点时间标签（本地时区对齐，与聚合器同语义）。 */
private fun bucketTimeLabel(startMs: Long, granularity: TokenStatsGranularity, zone: ZoneId): String {
    val zdt = Instant.ofEpochMilli(startMs).atZone(zone)
    return when (granularity) {
        TokenStatsGranularity.TEN_MINUTES, TokenStatsGranularity.HOURLY ->
            DateTimeFormatter.ofPattern("HH:mm").format(zdt)
        TokenStatsGranularity.DAILY ->
            DateTimeFormatter.ofPattern("MM/dd").format(zdt)
    }
}

/** 底部时间轴标签：首尾 + 均匀抽稀（约 6 个），避免手机宽度拥挤。 */
private fun bucketLabel(
    startMs: Long,
    granularity: TokenStatsGranularity,
    zone: ZoneId,
    index: Int,
    total: Int,
): Pair<String, Boolean> {
    if (total <= 1) return bucketTimeLabel(startMs, granularity, zone) to true
    val stride = ceil(total / 6.0).toInt().coerceAtLeast(1)
    val show = index == 0 || index == total - 1 || index % stride == 0
    return bucketTimeLabel(startMs, granularity, zone) to show
}

/** 向上取整到“漂亮”刻度（9→10、883→1000、150M→200M），与参考实现一致。 */
internal fun niceCeil(value: Double): Double {
    if (value <= 0.0) return 1.0
    val exp = log10(value).toInt()
    val magnitude = 10.0.pow(exp.toDouble())
    val normalized = value / magnitude
    val nice =
        when {
            normalized <= 1.0 -> 1.0
            normalized <= 1.15 -> 1.15
            normalized <= 1.25 -> 1.25
            normalized <= 1.5 -> 1.5
            normalized <= 2.0 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 3.0 -> 3.0
            normalized <= 4.0 -> 4.0
            normalized <= 5.0 -> 5.0
            normalized <= 7.5 -> 7.5
            else -> 10.0
        }
    return nice * magnitude
}

/** Token 数量紧凑格式：1.2K / 3.4M。 */
internal fun formatCompactCount(value: Long): String =
    when {
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", value / 1_000.0)
        else -> "$value"
    }

/** 千分位格式（图表 tooltip 明细用）。 */
internal fun formatCountWithComma(value: Long): String =
    String.format(java.util.Locale.US, "%,d", value)

/** 时长格式：<1s 用毫秒，否则秒（1 位小数）。 */
internal fun formatDuration(ms: Double): String =
    if (ms < 1_000.0) {
        String.format(java.util.Locale.US, "%.0fms", ms)
    } else {
        String.format(java.util.Locale.US, "%.1fs", ms / 1_000.0)
    }

// ==== 图表无障碍模型（P1-8，纯函数，供 JVM 测试） ====

/** 无障碍“上一桶”目标索引；已在最前或无桶返回 null（边界禁用）。 */
internal fun previousBucketIndex(current: Int, count: Int): Int? =
    if (count <= 1 || current <= 0) null else current - 1

/** 无障碍“下一桶”目标索引；已在最后或无桶返回 null（边界禁用）。 */
internal fun nextBucketIndex(current: Int, count: Int): Int? =
    if (count <= 1 || current >= count - 1) null else current + 1

/**
 * 图表无障碍描述（TalkBack 朗读）：[summary] 已由调用方按资源拼好（图表名、
 * 当前桶时间、第 n/m 桶、合计），[rows] 为“标签 值”明细行；无行时只读摘要。
 */
internal fun chartAccessibilityDescription(summary: String, rows: List<String>): String =
    if (rows.isEmpty()) summary else "$summary：${rows.joinToString("，")}"

/**
 * 折线分段（P2）：null 断段；每段连接**相邻**有效点（而非从段首重复连线）。
 * 返回线段对列表，供 Canvas 绘制与纯 JVM 测试共用。
 */
internal fun lineSegments(points: List<Offset?>): List<Pair<Offset, Offset>> {
    val segments = ArrayList<Pair<Offset, Offset>>()
    var previous: Offset? = null
    for (point in points) {
        if (point == null) {
            previous = null
        } else {
            previous?.let { segments += it to point }
            previous = point
        }
    }
    return segments
}

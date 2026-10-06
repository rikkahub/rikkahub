package me.rerere.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private class ChartGeometry(
    val yScale: ChartAxisScale,
    val xScale: ChartAxisScale?, // 仅散点图
    val categoryCount: Int,
)

private fun ChartSpec.buildGeometry(): ChartGeometry = when (style) {
    ChartStyle.Scatter -> {
        val points = series.flatMap { it.points }
        ChartGeometry(
            yScale = buildAxisScale(
                values = points.map { it.y },
                userMin = yAxis.min,
                userMax = yAxis.max,
                log = yAxis.scale == ChartScaleType.Log,
                includeZero = false,
            ),
            xScale = buildAxisScale(
                values = points.map { it.x },
                userMin = xAxis.min,
                userMax = xAxis.max,
                log = xAxis.scale == ChartScaleType.Log,
                includeZero = false,
            ),
            categoryCount = 0,
        )
    }

    else -> ChartGeometry(
        yScale = buildAxisScale(
            values = series.flatMap { it.values },
            // 柱状图始终从 0 开始, 忽略 min/max
            userMin = if (style == ChartStyle.Bar) null else yAxis.min,
            userMax = if (style == ChartStyle.Bar) null else yAxis.max,
            log = yAxis.scale == ChartScaleType.Log,
            includeZero = style == ChartStyle.Bar,
        ),
        xScale = null,
        categoryCount = categoryCount,
    )
}

/**
 * 图表绘制区域: 坐标轴刻度、网格线以及数据
 */
@Composable
internal fun ChartPlot(
    spec: ChartSpec,
    colors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(spec) { spec.buildGeometry() }
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f)

    Canvas(modifier = modifier) {
        val labelGap = 8.dp.toPx()
        val yScale = geometry.yScale
        val xScale = geometry.xScale

        val yLabels = yScale.ticks.map { tick ->
            textMeasurer.measure(
                text = formatChartNumber(tick, spec.yAxis.format, yScale.tickDecimals),
                style = labelStyle,
                maxLines = 1,
            )
        }
        val xTickLabels = xScale?.ticks?.map { tick ->
            textMeasurer.measure(
                text = formatChartNumber(tick, spec.xAxis.format, xScale.tickDecimals),
                style = labelStyle,
                maxLines = 1,
            )
        }
        val lineHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()

        val plot = Rect(
            left = (yLabels.maxOfOrNull { it.size.width } ?: 0) + labelGap,
            top = lineHeight / 2,
            right = size.width - (xTickLabels?.lastOrNull()?.size?.width?.div(2f) ?: 0f),
            bottom = size.height - lineHeight - labelGap,
        )
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        fun yToPx(value: Double) = plot.bottom - yScale.fraction(value) * plot.height

        // 水平网格线与 Y 轴刻度
        yScale.ticks.forEachIndexed { index, tick ->
            val y = yToPx(tick)
            drawLine(gridColor, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
            val label = yLabels[index]
            drawText(label, topLeft = Offset(plot.left - labelGap - label.size.width, y - label.size.height / 2f))
        }

        when (spec.style) {
            ChartStyle.Bar, ChartStyle.Line -> {
                val count = geometry.categoryCount
                if (count == 0) return@Canvas
                val slot = plot.width / count
                drawCategoryLabels(spec, textMeasurer, labelStyle, plot, slot, labelGap)

                val baseline = when {
                    spec.style == ChartStyle.Line || yScale.log -> yScale.min
                    else -> 0.0.coerceIn(yScale.min, yScale.max)
                }
                val baselineY = yToPx(baseline)
                drawLine(axisColor, Offset(plot.left, baselineY), Offset(plot.right, baselineY), 1.dp.toPx())

                clipRect(plot.left, plot.top - 1.dp.toPx(), plot.right, plot.bottom + 1.dp.toPx()) {
                    if (spec.style == ChartStyle.Bar) {
                        drawBars(spec, colors, plot, slot, baselineY, ::yToPx)
                    } else {
                        drawLines(spec, colors, plot, slot, ::yToPx)
                    }
                }
            }

            ChartStyle.Scatter -> {
                xScale ?: return@Canvas
                fun xToPx(value: Double) = plot.left + xScale.fraction(value) * plot.width

                // 垂直网格线与 X 轴刻度
                xScale.ticks.forEachIndexed { index, tick ->
                    val x = xToPx(tick)
                    drawLine(gridColor, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
                    val label = xTickLabels!![index]
                    drawText(
                        label,
                        topLeft = Offset(
                            clampLabelX(x - label.size.width / 2f, label.size.width),
                            plot.bottom + labelGap,
                        )
                    )
                }
                drawLine(axisColor, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1.dp.toPx())

                val totalPoints = spec.series.sumOf { it.points.size }
                val radius = (if (totalPoints > 500) 2.5.dp else 4.dp).toPx()
                clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                    spec.series.forEachIndexed { seriesIndex, series ->
                        val color = colors[seriesIndex].copy(alpha = 0.85f)
                        series.points.forEach { point ->
                            if (xScale.log && point.x <= 0 || yScale.log && point.y <= 0) return@forEach
                            drawCircle(color, radius, Offset(xToPx(point.x), yToPx(point.y)))
                        }
                    }
                }
            }
        }
    }
}

/** 将标签的左边界限制在画布内; 标签比画布宽时贴左对齐, 保证区间有效 */
private fun DrawScope.clampLabelX(left: Float, labelWidth: Int): Float =
    left.coerceIn(0f, max(0f, size.width - labelWidth))

private fun DrawScope.drawCategoryLabels(
    spec: ChartSpec,
    textMeasurer: TextMeasurer,
    style: TextStyle,
    plot: Rect,
    slot: Float,
    labelGap: Float,
) {
    val count = spec.categoryCount
    val gap = 8.dp.toPx()
    // 标签过密时间隔显示
    val widest = (0 until count).maxOf { textMeasurer.measure(spec.categoryLabel(it), style, maxLines = 1).size.width }
    val stride = max(1, ceil((widest + gap) / slot).toInt())
    // 单个标签比整个画布还宽时 stride 也无法兜住, 需再限制在画布宽度内, 否则省略号不生效
    val maxWidth = (slot * stride - gap).coerceAtMost(size.width).toInt().coerceAtLeast(1)
    for (index in 0 until count step stride) {
        val label: TextLayoutResult = textMeasurer.measure(
            text = spec.categoryLabel(index),
            style = style,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidth),
        )
        val centerX = plot.left + slot * (index + 0.5f)
        drawText(
            label,
            topLeft = Offset(
                clampLabelX(centerX - label.size.width / 2f, label.size.width),
                plot.bottom + labelGap,
            )
        )
    }
}

private fun DrawScope.drawBars(
    spec: ChartSpec,
    colors: List<Color>,
    plot: Rect,
    slot: Float,
    baselineY: Float,
    yToPx: (Double) -> Float,
) {
    val seriesCount = spec.series.size
    val gap = if (seriesCount > 1) 2.dp.toPx() else 0f
    val groupWidth = slot * 0.72f
    val barWidth = ((groupWidth - gap * (seriesCount - 1)) / seriesCount)
        .coerceIn(1f, 40.dp.toPx())
    val totalWidth = barWidth * seriesCount + gap * (seriesCount - 1)
    val radius = CornerRadius(min(4.dp.toPx(), barWidth / 2))
    val path = Path()

    for (index in 0 until spec.categoryCount) {
        val groupLeft = plot.left + slot * (index + 0.5f) - totalWidth / 2
        spec.series.forEachIndexed { seriesIndex, series ->
            val value = series.values.getOrNull(index) ?: return@forEachIndexed
            val valueY = yToPx(value)
            if (valueY == baselineY) return@forEachIndexed
            val left = groupLeft + seriesIndex * (barWidth + gap)
            val positive = valueY < baselineY
            path.reset()
            path.addRoundRect(
                RoundRect(
                    left = left,
                    top = min(valueY, baselineY),
                    right = left + barWidth,
                    bottom = max(valueY, baselineY),
                    // 圆角只加在远离基线的一端
                    topLeftCornerRadius = if (positive) radius else CornerRadius.Zero,
                    topRightCornerRadius = if (positive) radius else CornerRadius.Zero,
                    bottomRightCornerRadius = if (positive) CornerRadius.Zero else radius,
                    bottomLeftCornerRadius = if (positive) CornerRadius.Zero else radius,
                )
            )
            drawPath(path, colors[seriesIndex])
        }
    }
}

private fun DrawScope.drawLines(
    spec: ChartSpec,
    colors: List<Color>,
    plot: Rect,
    slot: Float,
    yToPx: (Double) -> Float,
) {
    val showDots = spec.categoryCount <= 40
    val dotRadius = 3.dp.toPx()
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

    spec.series.forEachIndexed { seriesIndex, series ->
        val color = colors[seriesIndex]
        val path = Path()
        series.values.forEachIndexed { index, value ->
            val offset = Offset(plot.left + slot * (index + 0.5f), yToPx(value))
            if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
        }
        drawPath(path, color, style = stroke)
        if (showDots || series.values.size == 1) {
            series.values.forEachIndexed { index, value ->
                drawCircle(color, dotRadius, Offset(plot.left + slot * (index + 0.5f), yToPx(value)))
            }
        }
    }
}

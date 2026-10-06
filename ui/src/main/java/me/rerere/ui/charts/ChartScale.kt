package me.rerere.ui.charts

import androidx.compose.ui.graphics.Color
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

internal val ChartPalette = listOf(
    Color(0xFF4A7FD6),
    Color(0xFFE2774A),
    Color(0xFF4FAE7B),
    Color(0xFF9B6FD1),
    Color(0xFFD9A93A),
    Color(0xFFD65C8A),
    Color(0xFF3FA7B5),
    Color(0xFFB0694E),
    Color(0xFF7D9A3E),
    Color(0xFF5F7896),
    Color(0xFFE0605F),
    Color(0xFF8C8C8C),
)

internal fun ChartSpec.seriesColors(): List<Color> =
    series.mapIndexed { index, s -> s.color ?: ChartPalette[index % ChartPalette.size] }

/**
 * 坐标轴刻度: 负责值到 [0, 1] 比例的映射以及刻度位置
 */
internal class ChartAxisScale(
    val min: Double,
    val max: Double,
    val ticks: List<Double>,
    val log: Boolean,
    val tickDecimals: Int,
) {
    private val lo = if (log) log10(min) else min
    private val hi = if (log) log10(max) else max

    fun fraction(value: Double): Float {
        val v = if (log) log10(value.coerceAtLeast(Double.MIN_VALUE)) else value
        return if (hi == lo) 0.5f else ((v - lo) / (hi - lo)).toFloat()
    }
}

internal fun buildAxisScale(
    values: List<Double>,
    userMin: Double?,
    userMax: Double?,
    log: Boolean,
    includeZero: Boolean,
    targetTickCount: Int = 6,
): ChartAxisScale = if (log) {
    buildLogScale(values, userMin, userMax)
} else {
    buildLinearScale(values, userMin, userMax, includeZero, targetTickCount)
}

private fun buildLinearScale(
    values: List<Double>,
    userMin: Double?,
    userMax: Double?,
    includeZero: Boolean,
    targetTickCount: Int,
): ChartAxisScale {
    var low = userMin ?: values.minOrNull() ?: 0.0
    var high = userMax ?: values.maxOrNull() ?: 1.0
    if (includeZero) {
        if (userMin == null) low = minOf(low, 0.0)
        if (userMax == null) high = maxOf(high, 0.0)
    }
    if (high <= low) {
        // 所有值相同或 min/max 冲突时扩展出一个可见范围
        val pad = if (low == 0.0) 1.0 else abs(low) * 0.1
        if (includeZero && low == 0.0) high = low + pad else {
            low -= pad
            high = low + pad * 2
        }
    }

    val step = niceStep((high - low) / (targetTickCount - 1))
    val min = userMin ?: (floor(low / step) * step)
    val max = userMax ?: (ceil(high / step) * step)
    val ticks = buildList {
        var k = ceil(min / step - 1e-9).toLong()
        while (k * step <= max + step * 1e-9) {
            add((k * step).let { if (it == 0.0) 0.0 else it })
            k++
        }
    }
    return ChartAxisScale(min, max, ticks, log = false, tickDecimals = decimalsForStep(step))
}

private fun buildLogScale(values: List<Double>, userMin: Double?, userMax: Double?): ChartAxisScale {
    val positive = values.filter { it > 0 }
    val lo = userMin?.takeIf { it > 0 }?.let { log10(it) }
        ?: floor(log10(positive.minOrNull() ?: 1.0))
    var hi = userMax?.takeIf { it > 0 }?.let { log10(it) }
        ?: ceil(log10(positive.maxOrNull() ?: 10.0))
    if (hi <= lo) hi = lo + 1

    val first = ceil(lo - 1e-9).toInt()
    val last = floor(hi + 1e-9).toInt()
    val stride = max(1, ceil((last - first + 1) / 6.0).toInt())
    val ticks = (first..last step stride).map { 10.0.pow(it) }
    return ChartAxisScale(
        min = 10.0.pow(lo),
        max = 10.0.pow(hi),
        ticks = ticks,
        log = true,
        tickDecimals = max(0, -first),
    )
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0 || !raw.isFinite()) return 1.0
    val exponent = floor(log10(raw))
    val base = 10.0.pow(exponent)
    val fraction = raw / base
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 2.5 -> 2.5
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

private fun decimalsForStep(step: Double): Int {
    for (d in 0..8) {
        val scaled = step * 10.0.pow(d)
        if (abs(scaled - scaled.roundToLong()) < 1e-6 * max(1.0, scaled)) return d
    }
    return 8
}

// 支持 f 风格格式: ".1f" ",.0f" ".0%" ",d"; 其它格式（如 strftime）忽略
private val NUMBER_FORMAT_REGEX = Regex("""^(,)?(?:\.(\d+))?([fd%])$""")

/**
 * 格式化图表中的数字
 *
 * @param format 模型给出的格式字符串, 不支持时回退默认格式
 * @param decimals 回退格式使用的固定小数位数, 为 null 时自动决定
 */
internal fun formatChartNumber(value: Double, format: String? = null, decimals: Int? = null): String {
    val spec = format?.let { NUMBER_FORMAT_REGEX.matchEntire(it.trim()) }
    val numberFormat = NumberFormat.getNumberInstance(Locale.getDefault())
    if (spec != null) {
        val (grouping, digits, type) = spec.destructured
        val fractionDigits = if (type == "d") 0 else digits.toIntOrNull() ?: 0
        numberFormat.isGroupingUsed = grouping.isNotEmpty()
        numberFormat.minimumFractionDigits = fractionDigits
        numberFormat.maximumFractionDigits = fractionDigits
        return if (type == "%") numberFormat.format(value * 100) + "%" else numberFormat.format(value)
    }

    val magnitude = abs(value)
    numberFormat.isGroupingUsed = magnitude >= 10_000
    if (decimals != null) {
        numberFormat.minimumFractionDigits = decimals
        numberFormat.maximumFractionDigits = decimals
    } else {
        numberFormat.minimumFractionDigits = 0
        numberFormat.maximumFractionDigits = when {
            magnitude >= 100 -> 2
            magnitude >= 1 -> 4
            else -> 6
        }
    }
    return numberFormat.format(value)
}

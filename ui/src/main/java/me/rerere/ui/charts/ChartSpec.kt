package me.rerere.ui.charts

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

enum class ChartStyle { Line, Bar, Scatter }

enum class ChartScaleType { Linear, Log }

@Immutable
data class ChartAxis(
    val title: String? = null,
    val data: List<String> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val scale: ChartScaleType = ChartScaleType.Linear,
    val format: String? = null,
)

@Immutable
data class ChartPoint(val x: Double, val y: Double)

@Immutable
data class ChartSeries(
    val name: String? = null,
    val color: Color? = null,
    val values: List<Double> = emptyList(),
    val points: List<ChartPoint> = emptyList(),
)

@Immutable
data class ChartSpec(
    val style: ChartStyle,
    val title: String? = null,
    val xAxis: ChartAxis = ChartAxis(),
    val yAxis: ChartAxis = ChartAxis(),
    val series: List<ChartSeries>,
) {
    /** 类目数量（折线图/柱状图），取 x_axis.data 与最长系列的较大值 */
    val categoryCount: Int
        get() = maxOf(xAxis.data.size, series.maxOfOrNull { it.values.size } ?: 0)

    fun categoryLabel(index: Int): String = xAxis.data.getOrNull(index) ?: (index + 1).toString()

    companion object {
        /**
         * 从 chart_display 工具参数解析图表, 尽量宽松; 无法构成有效图表时返回 null
         */
        fun fromJson(element: JsonElement): ChartSpec? {
            val obj = element as? JsonObject ?: return null
            val style = when (obj.string("style")) {
                "line" -> ChartStyle.Line
                "bar" -> ChartStyle.Bar
                "scatter" -> ChartStyle.Scatter
                else -> return null
            }
            val series = (obj["series"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.toSeries() }
                ?.filter { if (style == ChartStyle.Scatter) it.points.isNotEmpty() else it.values.isNotEmpty() }
                .orEmpty()
            if (series.isEmpty()) return null
            return ChartSpec(
                style = style,
                title = obj.string("title")?.takeIf { it.isNotBlank() },
                xAxis = (obj["x_axis"] as? JsonObject)?.toAxis() ?: ChartAxis(),
                yAxis = (obj["y_axis"] as? JsonObject)?.toAxis() ?: ChartAxis(),
                series = series,
            )
        }
    }
}

private val HEX_COLOR_REGEX = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

private fun JsonObject.toAxis(): ChartAxis = ChartAxis(
    title = string("title")?.takeIf { it.isNotBlank() },
    data = (this["data"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty(),
    min = number("min"),
    max = number("max"),
    scale = if (string("scale") == "log") ChartScaleType.Log else ChartScaleType.Linear,
    format = string("format")?.takeIf { it.isNotBlank() },
)

private fun JsonObject.toSeries(): ChartSeries = ChartSeries(
    name = string("name")?.takeIf { it.isNotBlank() },
    color = parseHexColor(string("color")),
    values = (this["values"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.takeIf { v -> v.isFinite() } }
        .orEmpty(),
    points = (this["points"] as? JsonArray)
        ?.mapNotNull { point ->
            val p = point as? JsonObject ?: return@mapNotNull null
            val x = p.number("x") ?: return@mapNotNull null
            val y = p.number("y") ?: return@mapNotNull null
            ChartPoint(x, y)
        }
        .orEmpty(),
)

private fun parseHexColor(text: String?): Color? {
    if (text == null || !HEX_COLOR_REGEX.matches(text)) return null
    val hex = text.drop(1).let { raw ->
        if (raw.length == 3) raw.map { "$it$it" }.joinToString("") else raw
    }
    return Color(0xFF000000 or hex.toLong(16))
}

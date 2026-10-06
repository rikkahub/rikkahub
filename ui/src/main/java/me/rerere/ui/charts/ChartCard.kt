package me.rerere.ui.charts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.ChartScatter
import me.rerere.hugeicons.stroke.Table
import me.rerere.ui.R

enum class ChartDisplayMode { Chart, Table }

/**
 * 图表卡片: 标题、图表/表格切换、绘图区与图例
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartCard(
    spec: ChartSpec,
    modifier: Modifier = Modifier,
    initialMode: ChartDisplayMode = ChartDisplayMode.Chart,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val colors = remember(spec) { spec.seriesColors() }
    val seriesNames = spec.series.mapIndexed { index, series ->
        series.name ?: stringResource(R.string.chart_series_name, index + 1)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    spec.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (mode == ChartDisplayMode.Chart) {
                        spec.yAxis.title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                ChartModeToggle(
                    style = spec.style,
                    mode = mode,
                    onModeChange = { mode = it },
                )
            }

            when (mode) {
                ChartDisplayMode.Chart -> {
                    ChartPlot(
                        spec = spec,
                        colors = colors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                    spec.xAxis.title?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (spec.series.size > 1 || spec.series.any { it.name != null }) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            spec.series.indices.forEach { index ->
                                LegendItem(color = colors[index], label = seriesNames[index])
                            }
                        }
                    }
                }

                ChartDisplayMode.Table -> {
                    ChartTable(spec = spec, colors = colors, seriesNames = seriesNames, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ChartModeToggle(
    style: ChartStyle,
    mode: ChartDisplayMode,
    onModeChange: (ChartDisplayMode) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ChartModeButton(
                icon = when (style) {
                    ChartStyle.Line -> HugeIcons.ChartLineData01
                    ChartStyle.Bar -> HugeIcons.ChartColumn
                    ChartStyle.Scatter -> HugeIcons.ChartScatter
                },
                contentDescription = stringResource(R.string.chart_mode_chart),
                selected = mode == ChartDisplayMode.Chart,
                onClick = { onModeChange(ChartDisplayMode.Chart) },
            )
            ChartModeButton(
                icon = HugeIcons.Table,
                contentDescription = stringResource(R.string.chart_mode_table),
                selected = mode == ChartDisplayMode.Table,
                onClick = { onModeChange(ChartDisplayMode.Table) },
            )
        }
    }
}

@Composable
private fun ChartModeButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 不用 Surface(onClick): 它会强制 48dp 最小触控尺寸, 把紧凑的切换条撑大
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .then(if (selected) Modifier.shadow(1.dp, shape) else Modifier)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(14.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChartCardBarPreview() {
    MaterialTheme {
        ChartCard(
            spec = ChartSpec(
                style = ChartStyle.Bar,
                title = "示例：各语言项目的平均构建时间（虚构数据）",
                xAxis = ChartAxis(data = listOf("Go", "Rust", "TypeScript", "Kotlin", "C++")),
                yAxis = ChartAxis(title = "秒"),
                series = listOf(
                    ChartSeries(name = "冷构建", values = listOf(12.0, 85.0, 20.0, 64.0, 110.0)),
                    ChartSeries(name = "增量构建", values = listOf(2.0, 9.0, 4.0, 11.0, 15.0)),
                ),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChartCardLinePreview() {
    MaterialTheme {
        ChartCard(
            spec = ChartSpec(
                style = ChartStyle.Line,
                title = "月活跃用户",
                xAxis = ChartAxis(data = listOf("1月", "2月", "3月", "4月", "5月", "6月")),
                yAxis = ChartAxis(title = "万人", format = ".1f"),
                series = listOf(
                    ChartSeries(name = "Android", values = listOf(12.5, 14.1, 15.8, 15.2, 17.9, 19.4)),
                    ChartSeries(name = "iOS", values = listOf(8.2, 8.9, 9.4, 10.8, 11.1, 12.6)),
                ),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChartCardScatterPreview() {
    MaterialTheme {
        ChartCard(
            spec = ChartSpec(
                style = ChartStyle.Scatter,
                title = "身高与体重",
                xAxis = ChartAxis(title = "身高 (cm)"),
                yAxis = ChartAxis(title = "体重 (kg)"),
                series = listOf(
                    ChartSeries(
                        name = "样本",
                        points = listOf(
                            ChartPoint(160.0, 52.0), ChartPoint(165.0, 58.0), ChartPoint(170.0, 63.0),
                            ChartPoint(175.0, 70.0), ChartPoint(180.0, 76.0), ChartPoint(172.0, 61.0),
                        ),
                    ),
                ),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

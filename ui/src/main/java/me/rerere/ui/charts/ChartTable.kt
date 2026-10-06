package me.rerere.ui.charts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.rerere.ui.R
import me.rerere.ui.table.DataTable

private class ChartTableColumn(
    val title: String,
    val numeric: Boolean,
    val color: Color? = null,
)

private class ChartTableData(
    val columns: List<ChartTableColumn>,
    val rows: List<List<String>>,
)

private fun ChartSpec.buildTableData(
    colors: List<Color>,
    seriesNames: List<String>,
    seriesHeader: String,
): ChartTableData = when (style) {
    ChartStyle.Scatter -> ChartTableData(
        columns = listOf(
            ChartTableColumn(seriesHeader, numeric = false),
            ChartTableColumn(xAxis.title ?: "x", numeric = true),
            ChartTableColumn(yAxis.title ?: "y", numeric = true),
        ),
        rows = series.flatMapIndexed { index, s ->
            val name = seriesNames[index]
            s.points.map { point ->
                listOf(name, formatChartNumber(point.x, xAxis.format), formatChartNumber(point.y, yAxis.format))
            }
        },
    )

    else -> ChartTableData(
        columns = buildList {
            add(ChartTableColumn(xAxis.title.orEmpty(), numeric = false))
            series.indices.forEach { index ->
                add(ChartTableColumn(seriesNames[index], numeric = true, color = colors[index]))
            }
        },
        rows = (0 until categoryCount).map { index ->
            buildList {
                add(categoryLabel(index))
                series.forEach { s ->
                    add(s.values.getOrNull(index)?.let { formatChartNumber(it, yAxis.format) } ?: "-")
                }
            }
        },
    )
}

/**
 * 图表的表格视图, 基于 [DataTable]; 行数较多时在限定高度内纵向滚动
 */
@Composable
internal fun ChartTable(
    spec: ChartSpec,
    colors: List<Color>,
    seriesNames: List<String>,
    modifier: Modifier = Modifier,
) {
    val seriesHeader = stringResource(R.string.chart_series)
    val table = remember(spec, colors, seriesNames, seriesHeader) {
        spec.buildTableData(colors, seriesNames, seriesHeader)
    }

    Box(
        modifier = modifier
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState())
    ) {
        DataTable(
            headers = table.columns.map { column ->
                @Composable {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (column.color != null) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(column.color, RoundedCornerShape(2.dp))
                            )
                        }
                        Text(
                            text = column.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            rows = table.rows.map { row ->
                row.mapIndexed { columnIndex, cell ->
                    val numeric = table.columns[columnIndex].numeric
                    @Composable {
                        Text(
                            text = cell,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = if (numeric) Modifier.fillMaxWidth() else Modifier,
                        )
                    }
                }
            },
            cellPadding = 6.dp,
            columnMinWidths = table.columns.map { if (it.numeric) 64.dp else 48.dp },
            // 数值列不设上限: 首轮测量为无界约束, fillMaxWidth 不会把列撑大, 次轮固定列宽后才右对齐
            columnMaxWidths = table.columns.map { if (it.numeric) Dp.Infinity else 200.dp },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

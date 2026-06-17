package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.table.DataTable
import org.jsoup.nodes.Element

@Composable
internal fun HtmlTable(element: Element, onClickCitation: (String) -> Unit) {
    val headerElements = element.select("thead tr th")
    val rawColumnCount = headerElements.size.takeIf { it > 0 }
        ?: element.select("tbody tr:first-child td").size
    val bodyRows = element.select("tbody tr")
    val (clampedRows, columnCount) = clampTableDimensions(rawRows = bodyRows.size, rawCols = rawColumnCount)
    if (columnCount == 0) return

    val headers = List(columnCount) { col ->
        @Composable {
            if (col < headerElements.size) {
                val cellElement = headerElements[col]
                val cellText = cellElement.text()
                if (cellText.length > RenderLimits.MAX_CELL_CHARS) {
                    Text(text = "${cellText.take(RenderLimits.MAX_CELL_CHARS)}…")
                } else {
                    HtmlStyledElement(element = cellElement) {
                        HtmlInlineGroup(
                            nodes = cellElement.childNodes(),
                            onClickCitation = onClickCitation,
                        )
                    }
                }
            }
        }
    }

    val rows = bodyRows.take(clampedRows).map { tr ->
        val cellElements = tr.select("td")
        List(columnCount) { col ->
            @Composable {
                if (col < cellElements.size) {
                    val cellElement = cellElements[col]
                    val cellText = cellElement.text()
                    if (cellText.length > RenderLimits.MAX_CELL_CHARS) {
                        Text(text = "${cellText.take(RenderLimits.MAX_CELL_CHARS)}…")
                    } else {
                        HtmlStyledElement(element = cellElement) {
                            HtmlInlineGroup(
                                nodes = cellElement.childNodes(),
                                onClickCitation = onClickCitation,
                            )
                        }
                    }
                }
            }
        }
    }

    DataTable(
        headers = headers,
        rows = rows,
        modifier = Modifier.padding(vertical = 8.dp),
        columnMinWidths = List(columnCount) { 80.dp },
        columnMaxWidths = List(columnCount) { 200.dp },
    )

    val truncatedRows = bodyRows.size - rows.size
    if (truncatedRows > 0) {
        Text(
            text = "… table truncated (${truncatedRows} more rows)",
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

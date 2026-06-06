package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.table.DataTable
import org.jsoup.nodes.Element

@Composable
internal fun HtmlTable(element: Element, onClickCitation: (String) -> Unit) {
    val headerElements = element.select("thead tr th")
    val columnCount = headerElements.size.takeIf { it > 0 }
        ?: element.select("tbody tr:first-child td").size
    if (columnCount == 0) return

    val headers = List(columnCount) { col ->
        @Composable {
            if (col < headerElements.size) {
                HtmlStyledElement(element = headerElements[col]) {
                    HtmlInlineGroup(
                        nodes = headerElements[col].childNodes(),
                        onClickCitation = onClickCitation,
                    )
                }
            }
        }
    }

    val bodyRows = element.select("tbody tr")
    val rows = bodyRows.map { tr ->
        val cellElements = tr.select("td")
        List(columnCount) { col ->
            @Composable {
                if (col < cellElements.size) {
                    HtmlStyledElement(element = cellElements[col]) {
                        HtmlInlineGroup(
                            nodes = cellElements[col].childNodes(),
                            onClickCitation = onClickCitation,
                        )
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
}

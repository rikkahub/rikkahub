package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.ext.toDp
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node

// ---- Block renderers ----

@Composable
internal fun HtmlParagraph(
    element: Element,
    onClickCitation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val paragraphStyle = remember(element.attr("style"), density, baseTextStyle) {
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            MarkdownCss.parseBlockTextStyle(
                style = it,
                density = density,
                baseTextStyle = baseTextStyle,
            )
        }
    }

    if (paragraphStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(paragraphStyle)) {
            HtmlParagraphContent(element = element, onClickCitation = onClickCitation, density = density, modifier = modifier)
        }
    } else {
        HtmlParagraphContent(element = element, onClickCitation = onClickCitation, density = density, modifier = modifier)
    }
}

@Composable
private fun HtmlParagraphContent(
    element: Element,
    onClickCitation: (String) -> Unit,
    density: Density,
    modifier: Modifier = Modifier,
) {
    val hasImages = element.select("img").isNotEmpty()
    // A span.math with inline != "true" is a block math element
    val hasBlockMath = element.select("span.math").any { it.attr("inline") != "true" }

    if (hasImages || hasBlockMath) {
        // Mixed block content: render children individually in a FlowRow
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            element.childNodes().fastForEach { child ->
                HtmlInlineAsComposable(node = child, onClickCitation = onClickCitation)
            }
        }
        return
    }

    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val hasInlineMath = element.select("span.math").any { it.attr("inline") == "true" }
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current

    val (annotatedString, inlineContents) = remember(
        element.outerHtml(),
        enableLatexRendering,
        colorScheme,
        density,
        textStyle,
        onClickCitation,
    ) {
        val contents = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            element.childNodes().forEach { child ->
                appendHtmlInlineNode(
                    node = child,
                    colorScheme = colorScheme,
                    inlineContents = contents,
                    density = density,
                    style = textStyle,
                    enableLatexRendering = enableLatexRendering,
                    onClickCitation = onClickCitation,
                )
            }
        }
        text to contents
    }

    Text(
        text = annotatedString,
        inlineContent = inlineContents,
        softWrap = true,
        overflow = TextOverflow.Visible,
        modifier = modifier.fillMaxWidth(),
        style = textStyle.copy(
            lineHeight = if (hasInlineMath && enableLatexRendering)
                TextUnit.Unspecified
            else
                textStyle.lineHeight,
        ),
    )
}

@Composable
internal fun HtmlHeading(element: Element, onClickCitation: (String) -> Unit) {
    val level = element.tagName().removePrefix("h").toIntOrNull() ?: 1
    val headingStyle = HeaderStyle.fromLevel(
        level = level,
        fontSizeRatio = LocalSettings.current.displaySetting.fontSizeRatio,
    )
    val verticalPadding = HeaderStyle.verticalPadding(level)
    ProvideTextStyle(LocalTextStyle.current.merge(headingStyle)) {
        Box(modifier = Modifier.padding(vertical = verticalPadding)) {
            HtmlParagraph(element = element, onClickCitation = onClickCitation)
        }
    }
}

@Composable
internal fun HtmlList(
    element: Element,
    ordered: Boolean,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
    HtmlStyledElement(element = element) {
        Column(modifier = Modifier.padding(start = (level * 8).dp, top = 4.dp, bottom = 4.dp)) {
            val bulletBase = when (level % 3) {
                0 -> "•"; 1 -> "◦"; else -> "▪"
            }
            var orderedIndex = 1
            element.children().fastForEach { item ->
                if (item.tagName().lowercase() == "li") {
                    val bullet = if (ordered) "${orderedIndex++}. " else "$bulletBase "
                    HtmlListItem(
                        item = item,
                        bulletText = bullet,
                        onClickCitation = onClickCitation,
                        level = level,
                    )
                }
            }
        }
    }
}

@Composable
private fun HtmlListItem(
    item: Element,
    bulletText: String,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
    val isTaskItem = item.hasClass("task-list-item")
    val checkboxInput = item.selectFirst("input[type=checkbox]")
    val isChecked = checkboxInput?.hasAttr("checked") == true

    HtmlStyledElement(element = item) {
        Column {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                if (isTaskItem && checkboxInput != null) {
                    // Checkbox indicator
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        modifier = Modifier.padding(end = 4.dp, top = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(2.dp)
                                .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isChecked) {
                                Icon(
                                    imageVector = HugeIcons.Tick01,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = bulletText,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.alignByBaseline(),
                    )
                }

                // Item inline content (excluding nested lists and the checkbox input)
                Column(modifier = Modifier.weight(1f)) {
                    val directContentNodes = item.childNodes().filter { node ->
                        !(node is Element &&
                            (node.tagName().lowercase() in listOf("ul", "ol") ||
                                (node.tagName().lowercase() == "input" && node.attr("type") == "checkbox")))
                    }
                    // Group consecutive inline nodes and render as a single paragraph
                    val groups = mutableListOf<MutableList<Node>>()
                    directContentNodes.fastForEach { node ->
                        if (node is Element && node.tagName().lowercase() == "p") {
                            groups.add(mutableListOf(node))
                        } else {
                            val last = groups.lastOrNull()
                            if (last != null && last.none {
                                    it is Element && it.tagName().lowercase() == "p"
                                }) {
                                last.add(node)
                            } else {
                                groups.add(mutableListOf(node))
                            }
                        }
                    }
                    groups.fastForEach { group ->
                        val first = group.firstOrNull()
                        if (first is Element && first.tagName().lowercase() == "p") {
                            HtmlParagraph(element = first, onClickCitation = onClickCitation)
                        } else {
                            HtmlInlineGroup(nodes = group, onClickCitation = onClickCitation)
                        }
                    }
                }
            }

            // Nested lists
            item.children().fastForEach { child ->
                val tag = child.tagName().lowercase()
                if (tag == "ul" || tag == "ol") {
                    HtmlList(
                        element = child,
                        ordered = tag == "ol",
                        onClickCitation = onClickCitation,
                        level = level + 1,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HtmlBlockquote(element: Element, onClickCitation: (String) -> Unit) {
    ProvideTextStyle(LocalTextStyle.current.copy(fontStyle = FontStyle.Italic)) {
        val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        Column(
            modifier = Modifier
                .drawWithContent {
                    drawContent()
                    drawRect(color = bgColor, size = size)
                    drawRect(color = borderColor, size = Size(10f, size.height))
                }
                .padding(8.dp),
        ) {
            element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

@Composable
internal fun HtmlMathBlock(formula: String) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    if (enableLatexRendering) {
        MathBlock(
            latex = formula,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    } else {
        Text(
            text = formula,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

@Composable
internal fun HtmlDetails(element: Element, onClickCitation: (String) -> Unit) {
    // Delegate to the existing SimpleHtmlBlock details renderer via a mini-document
    val summaryElement = element.children().find { it.tagName().lowercase() == "summary" }
    val summaryText = summaryElement?.text() ?: "Details"

    var expanded by remember { mutableStateOf(element.hasAttr("open")) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = if (expanded) "▼ " else "▶ ")
            Text(text = summaryText, fontWeight = FontWeight.Medium)
        }
        if (expanded) {
            Column(modifier = Modifier.padding(start = 16.dp)) {
                element.childNodes().fastForEach { child ->
                    if (!(child is Element && child.tagName().lowercase() == "summary")) {
                        HtmlBodyNode(child, onClickCitation)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HtmlProgress(element: Element) {
    val value = element.attr("value").toFloatOrNull() ?: 0f
    val max = element.attr("max").toFloatOrNull()?.takeIf { it > 0 } ?: 100f
    val progress = (value / max).coerceIn(0f, 1f)

    val style = element.attr("style")
    val widthValue = MarkdownCss.parseCssDeclarations(style)["width"] ?: element.attr("width")

    val widthModifier = when {
        widthValue.endsWith("%") -> widthValue.removeSuffix("%").toFloatOrNull()
            ?.let { Modifier.fillMaxWidth(it / 100f) } ?: Modifier.fillMaxWidth()
        widthValue.endsWith("px") -> widthValue.removeSuffix("px").toIntOrNull()
            ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        widthValue.isNotEmpty() -> widthValue.toIntOrNull()
            ?.let { Modifier.width(it.dp) } ?: Modifier.fillMaxWidth()
        else -> Modifier.fillMaxWidth()
    }

    androidx.compose.material3.LinearProgressIndicator(
        progress = { progress },
        modifier = widthModifier.padding(vertical = 4.dp),
    )
}

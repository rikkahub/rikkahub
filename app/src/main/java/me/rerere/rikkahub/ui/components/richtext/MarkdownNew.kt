package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ---- Preprocessing (mirrors Markdown.kt logic) ----

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)

private fun preProcess(content: String): String {
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { codeBlocks.add(it.range) }
    fun isInCodeBlock(pos: Int) = codeBlocks.any { pos in it }

    var result = INLINE_LATEX_REGEX.replace(content) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "$" + m.groupValues[1] + "$"
    }
    result = BLOCK_LATEX_REGEX.replace(result) { m ->
        if (isInCodeBlock(m.range.first)) m.value else "$$" + m.groupValues[1] + "$$"
    }
    return result
}

// ---- HTML generation ----

private val flavour by lazy {
    GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
}

private val parser by lazy { MarkdownParser(flavour) }

private fun generateMarkdownHtml(content: String): String {
    val preprocessed = preProcess(content)
    val tree = parser.buildMarkdownTreeFromString(preprocessed)
    return HtmlGenerator(preprocessed, tree, flavour).generateHtml()
}

// ---- Main composable ----

@Composable
fun MarkdownNew(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
) {
    var html by remember {
        mutableStateOf(
            value = generateMarkdownHtml(content),
        )
    }

    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        snapshotFlow { updatedContent }
            .distinctUntilChanged()
            .mapLatest { generateMarkdownHtml(it) }
            .catch { it.printStackTrace() }
            .flowOn(Dispatchers.Default)
            .collect { html = it }
    }

    val document = remember(html) {
        runCatching { Jsoup.parse(html) }.getOrElse { Jsoup.parse("") }
    }

    ProvideTextStyle(style) {
        Column(modifier = modifier.padding(start = 4.dp)) {
            document.body().childNodes().fastForEach { node ->
                HtmlBodyNode(node = node, onClickCitation = onClickCitation)
            }
        }
    }
}

// ---- Node dispatching ----

@Composable
private fun HtmlBodyNode(node: Node, onClickCitation: (String) -> Unit) {
    when (node) {
        is Element -> HtmlBlockElement(element = node, onClickCitation = onClickCitation)
        is TextNode -> {
            val text = node.text().trim()
            if (text.isNotEmpty()) Text(text = text)
        }
    }
}

@Composable
private fun HtmlBlockElement(
    element: Element,
    onClickCitation: (String) -> Unit,
    listLevel: Int = 0,
) {
    when (element.tagName().lowercase()) {
        "p" -> HtmlParagraph(element = element, onClickCitation = onClickCitation)

        "h1", "h2", "h3", "h4", "h5", "h6" -> HtmlHeading(
            element = element,
            onClickCitation = onClickCitation,
        )

        "ul" -> HtmlList(
            element = element,
            ordered = false,
            onClickCitation = onClickCitation,
            level = listLevel,
        )

        "ol" -> HtmlList(
            element = element,
            ordered = true,
            onClickCitation = onClickCitation,
            level = listLevel,
        )

        "pre" -> HtmlCodeBlock(element = element)

        "blockquote" -> HtmlBlockquote(element = element, onClickCitation = onClickCitation)

        "table" -> HtmlTable(element = element, onClickCitation = onClickCitation)

        "hr" -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            thickness = 0.5.dp,
        )

        "img" -> {
            val src = element.attr("src")
            val alt = element.attr("alt")
            if (src.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ZoomableAsyncImage(
                        model = src,
                        contentDescription = alt.takeIf { it.isNotEmpty() },
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .widthIn(min = 120.dp)
                            .heightIn(min = 120.dp),
                    )
                }
            }
        }

        "span" -> {
            // Block-level math span emitted directly into body
            if (element.hasClass("math") && element.attr("inline") != "true") {
                HtmlMathBlock(formula = element.text())
            }
        }

        "details" -> HtmlDetails(element = element, onClickCitation = onClickCitation)

        "progress" -> HtmlProgress(element = element)

        "div" -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
            }
        }

        else -> {
            // Generic fallback: recurse into children
            element.childNodes().forEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

// ---- Block renderers ----

@Composable
private fun HtmlParagraph(element: Element, onClickCitation: (String) -> Unit) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val paragraphStyle = remember(element.attr("style"), density, baseTextStyle) {
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            parseBlockTextStyle(
                style = it,
                density = density,
                baseTextStyle = baseTextStyle,
            )
        }
    }

    if (paragraphStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(paragraphStyle)) {
            HtmlParagraphContent(element = element, onClickCitation = onClickCitation, density = density)
        }
    } else {
        HtmlParagraphContent(element = element, onClickCitation = onClickCitation, density = density)
    }
}

@Composable
private fun HtmlParagraphContent(
    element: Element,
    onClickCitation: (String) -> Unit,
    density: Density,
) {
    val hasImages = element.select("img").isNotEmpty()
    // A span.math with inline != "true" is a block math element
    val hasBlockMath = element.select("span.math").any { it.attr("inline") != "true" }

    if (hasImages || hasBlockMath) {
        // Mixed block content: render children individually in a FlowRow
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
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

    val (annotatedString, inlineContents) = remember(element.outerHtml(), enableLatexRendering) {
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
        modifier = Modifier.fillMaxWidth(),
        style = textStyle.copy(
            lineHeight = if (hasInlineMath && enableLatexRendering)
                TextUnit.Unspecified
            else
                textStyle.lineHeight,
        ),
    )
}

@Composable
private fun HtmlHeading(element: Element, onClickCitation: (String) -> Unit) {
    val level = element.tagName().removePrefix("h").toIntOrNull() ?: 1
    val headingStyle = when (level) {
        1 -> HeaderStyle.H1
        2 -> HeaderStyle.H2
        3 -> HeaderStyle.H3
        4 -> HeaderStyle.H4
        5 -> HeaderStyle.H5
        else -> HeaderStyle.H6
    }
    val verticalPadding = when (level) {
        1 -> 16.dp; 2 -> 14.dp; 3 -> 12.dp; 4 -> 10.dp; 5 -> 8.dp; else -> 6.dp
    }
    ProvideTextStyle(headingStyle) {
        Box(modifier = Modifier.padding(vertical = verticalPadding)) {
            HtmlParagraph(element = element, onClickCitation = onClickCitation)
        }
    }
}

@Composable
private fun HtmlList(
    element: Element,
    ordered: Boolean,
    onClickCitation: (String) -> Unit,
    level: Int,
) {
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
                                it is Element && (it as Element).tagName().lowercase() == "p"
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

@Composable
private fun HtmlCodeBlock(element: Element) {
    val codeElement = element.selectFirst("code")
    val language = codeElement?.classNames()
        ?.find { it.startsWith("language-") }
        ?.removePrefix("language-")
        ?: "plaintext"
    val code = codeElement?.wholeText()?.trimEnd('\n') ?: element.wholeText().trimEnd('\n')

    HighlightCodeBlock(
        code = code,
        language = language,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        completeCodeBlock = true,
    )
}

@Composable
private fun HtmlBlockquote(element: Element, onClickCitation: (String) -> Unit) {
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
private fun HtmlMathBlock(formula: String) {
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
private fun HtmlTable(element: Element, onClickCitation: (String) -> Unit) {
    val headerElements = element.select("thead tr th")
    val columnCount = headerElements.size.takeIf { it > 0 }
        ?: element.select("tbody tr:first-child td").size
    if (columnCount == 0) return

    val headers = List(columnCount) { col ->
        @Composable {
            if (col < headerElements.size) {
                HtmlInlineGroup(
                    nodes = headerElements[col].childNodes(),
                    onClickCitation = onClickCitation,
                )
            }
        }
    }

    val bodyRows = element.select("tbody tr")
    val rows = bodyRows.map { tr ->
        val cellElements = tr.select("td")
        List(columnCount) { col ->
            @Composable {
                if (col < cellElements.size) {
                    HtmlInlineGroup(
                        nodes = cellElements[col].childNodes(),
                        onClickCitation = onClickCitation,
                    )
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

@Composable
private fun HtmlDetails(element: Element, onClickCitation: (String) -> Unit) {
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
private fun HtmlProgress(element: Element) {
    val value = element.attr("value").toFloatOrNull() ?: 0f
    val max = element.attr("max").toFloatOrNull()?.takeIf { it > 0 } ?: 100f
    val progress = (value / max).coerceIn(0f, 1f)

    val style = element.attr("style")
    val widthValue = style.split(";")
        .mapNotNull { it.split(":").takeIf { p -> p.size == 2 }?.let { p -> p[0].trim() to p[1].trim() } }
        .toMap()["width"] ?: element.attr("width")

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

// ---- Inline group rendering (for list items with mixed inline nodes) ----

/**
 * Renders a list of inline Jsoup nodes as a single Text composable with AnnotatedString.
 * This prevents inline siblings (e.g. <strong>A</strong>和<strong>B</strong>) from being
 * rendered on separate lines.
 */
@Composable
private fun HtmlInlineGroup(nodes: List<Node>, onClickCitation: (String) -> Unit) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current

    val key = remember(nodes) { nodes.joinToString("") { if (it is Element) it.outerHtml() else it.toString() } }
    val (annotatedString, inlineContents) = remember(key, enableLatexRendering) {
        val contents = mutableMapOf<String, InlineTextContent>()
        val text = buildAnnotatedString {
            nodes.fastForEach { node ->
                appendHtmlInlineNode(
                    node = node,
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

    if (annotatedString.isNotEmpty()) {
        Text(text = annotatedString, inlineContent = inlineContents)
    }
}

// ---- Inline-as-Composable rendering (for FlowRow mixed content) ----

/**
 * Renders an individual Jsoup node as a standalone Composable.
 * Used inside FlowRow for paragraphs that mix images, math, and text.
 */
@Composable
private fun HtmlInlineAsComposable(node: Node, onClickCitation: (String) -> Unit) {
    when (node) {
        is TextNode -> {
            val text = node.text()
            if (text.isNotEmpty()) Text(text = text)
        }

        is Element -> {
            val tag = node.tagName().lowercase()
            when {
                tag == "img" -> {
                    val src = node.attr("src")
                    val alt = node.attr("alt")
                    if (src.isNotEmpty()) {
                        ZoomableAsyncImage(
                            model = src,
                            contentDescription = alt.takeIf { it.isNotEmpty() },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .widthIn(min = 120.dp)
                                .heightIn(min = 120.dp),
                        )
                    }
                }

                tag == "span" && node.hasClass("math") && node.attr("inline") != "true" -> {
                    HtmlMathBlock(formula = node.text())
                }

                tag == "br" -> {
                    // handled by inline text
                }

                else -> {
                    // Render as an inline text segment
                    val colorScheme = MaterialTheme.colorScheme
                    val textStyle = LocalTextStyle.current
                    val density = LocalDensity.current
                    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
                    val (annotated, inlineContents) = remember(node.outerHtml(), enableLatexRendering) {
                        val contents = mutableMapOf<String, InlineTextContent>()
                        val text = buildAnnotatedString {
                            appendHtmlInlineElement(
                                element = node,
                                colorScheme = colorScheme,
                                inlineContents = contents,
                                density = density,
                                style = textStyle,
                                enableLatexRendering = enableLatexRendering,
                                onClickCitation = onClickCitation,
                            )
                        }
                        text to contents
                    }
                    Text(text = annotated, inlineContent = inlineContents)
                }
            }
        }
    }
}

// ---- Inline AnnotatedString building ----

private fun AnnotatedString.Builder.appendHtmlInlineNode(
    node: Node,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
) {
    when (node) {
        is TextNode -> append(node.text())
        is Element -> appendHtmlInlineElement(
            element = node,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = style,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
        )
    }
}

private fun AnnotatedString.Builder.appendHtmlInlineElement(
    element: Element,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
) {
    fun recurse(el: Element) = appendHtmlInlineElement(
        element = el,
        colorScheme = colorScheme,
        inlineContents = inlineContents,
        density = density,
        style = style,
        enableLatexRendering = enableLatexRendering,
        onClickCitation = onClickCitation,
    )

    fun recurseChildren(el: Element) = el.childNodes().fastForEach {
        appendHtmlInlineNode(it, colorScheme, inlineContents, density, style, enableLatexRendering, onClickCitation)
    }

    fun appendStyledChildren(spanStyle: SpanStyle) = withStyle(spanStyle) {
        recurseChildren(element)
    }

    when (element.tagName().lowercase()) {
        "b", "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { recurseChildren(element) }

        "i", "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { recurseChildren(element) }

        "del", "s", "strike" -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
            recurseChildren(element)
        }

        "u" -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { recurseChildren(element) }

        "code" -> withStyle(
            SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 0.95.em,
                background = colorScheme.secondaryContainer.copy(alpha = 0.2f),
            )
        ) {
            append(element.text())
        }

        "a" -> {
            val href = element.attr("href")
            val text = element.text()
            when {
                text.startsWith("citation,") -> {
                    // Citation link: [citation,domain](id)
                    val domain = text.substringAfter("citation,")
                    val id = href
                    if (id.length == 6) {
                        inlineContents.putIfAbsent(
                            "citation:$id",
                            InlineTextContent(
                                placeholder = Placeholder(
                                    width = (domain.length * 7).sp,
                                    height = 1.em,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                                ),
                                children = {
                                    Box(
                                        modifier = Modifier
                                            .clickable { onClickCitation(id.trim()) }
                                            .fillMaxSize()
                                            .clip(CircleShape)
                                            .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = domain,
                                            modifier = Modifier.wrapContentSize(),
                                            style = TextStyle(
                                                fontSize = 10.sp,
                                                lineHeight = 10.sp,
                                                fontFamily = JetbrainsMono,
                                                color = colorScheme.onTertiaryContainer,
                                                fontWeight = FontWeight.Thin,
                                            ),
                                        )
                                    }
                                },
                            ),
                        )
                        appendInlineContent("citation:$id")
                    }
                }

                href.isNotEmpty() -> {
                    withLink(LinkAnnotation.Url(href)) {
                        withStyle(
                            SpanStyle(
                                color = colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            )
                        ) {
                            recurseChildren(element)
                        }
                    }
                }

                else -> recurseChildren(element)
            }
        }

        "span" -> {
            if (element.hasClass("math") && element.attr("inline") == "true") {
                val formula = element.text()
                if (enableLatexRendering) {
                    appendInlineContent(formula, "[Latex]")
                    val (width, height) = with(density) {
                        assumeLatexSize(latex = formula, fontSize = style.fontSize.toPx()).let {
                            it.width().toSp() to it.height().toSp()
                        }
                    }
                    inlineContents.putIfAbsent(
                        formula,
                        InlineTextContent(
                            placeholder = Placeholder(
                                width = width,
                                height = height,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ),
                            children = {
                                MathInline(latex = formula, modifier = Modifier)
                            },
                        ),
                    )
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.95.em)) {
                        append(formula)
                    }
                }
            } else {
                val inlineStyle = element.attr("style").takeIf { it.isNotBlank() }?.let {
                    parseInlineSpanStyle(
                        style = it,
                        density = density,
                        baseFontSize = style.fontSize,
                    )
                }
                if (inlineStyle != null) {
                    appendStyledChildren(inlineStyle)
                } else {
                    recurseChildren(element)
                }
            }
        }

        "font" -> {
            val inlineStyle = buildFontTagStyle(
                element = element,
                density = density,
                baseFontSize = style.fontSize,
            )
            if (inlineStyle != null) {
                appendStyledChildren(inlineStyle)
            } else {
                recurseChildren(element)
            }
        }

        "br" -> append("\n")

        else -> recurseChildren(element)
    }
}

private fun buildFontTagStyle(
    element: Element,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val color = element.attr("color").takeIf { it.isNotBlank() }?.let(::parseColor)
    val styleAttr = element.attr("style").takeIf { it.isNotBlank() }?.let {
        parseInlineSpanStyle(
            style = it,
            density = density,
            baseFontSize = baseFontSize,
        )
    }
    val sizeAttr = element.attr("size").takeIf { it.isNotBlank() }?.let {
        parseLegacyFontSize(
            fontSize = it,
            density = density,
            baseFontSize = baseFontSize,
        )
    }

    var resolvedStyle = styleAttr ?: SpanStyle()
    color?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(color = it)) }
    sizeAttr?.let { resolvedStyle = resolvedStyle.merge(SpanStyle(fontSize = it)) }

    return resolvedStyle.takeIf {
        color != null || styleAttr != null || sizeAttr != null
    }
}

private fun parseInlineSpanStyle(
    style: String,
    density: Density,
    baseFontSize: TextUnit,
): SpanStyle? {
    val properties = style
        .split(";")
        .mapNotNull { property ->
            val parts = property.split(":", limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
        }
        .toMap()

    var hasStyle = false
    var spanStyle = SpanStyle()

    properties["color"]?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(color = it))
            hasStyle = true
        }
    }

    properties["background-color"]?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(background = it))
            hasStyle = true
        }
    }

    properties["font-weight"]?.let { value ->
        parseFontWeight(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontWeight = it))
            hasStyle = true
        }
    }

    properties["font-style"]?.let { value ->
        parseFontStyle(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontStyle = it))
            hasStyle = true
        }
    }

    properties["font-family"]?.let { value ->
        parseFontFamily(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontFamily = it))
            hasStyle = true
        }
    }

    properties["font-size"]?.let { value ->
        parseFontSize(
            fontSize = value,
            density = density,
            baseFontSize = baseFontSize,
        )?.let {
            spanStyle = spanStyle.merge(SpanStyle(fontSize = it))
            hasStyle = true
        }
    }

    properties["letter-spacing"]?.let { value ->
        parseSpacing(
            spacing = value,
            density = density,
            baseFontSize = baseFontSize,
        )?.let {
            spanStyle = spanStyle.merge(SpanStyle(letterSpacing = it))
            hasStyle = true
        }
    }

    properties["text-decoration"]?.let { value ->
        parseTextDecoration(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(textDecoration = it))
            hasStyle = true
        }
    }

    val backgroundValue = properties["background-color"] ?: properties["background"]
    backgroundValue?.let { value ->
        parseColor(value)?.let {
            spanStyle = spanStyle.merge(SpanStyle(background = it))
            hasStyle = true
        }
    }

    return spanStyle.takeIf { hasStyle }
}

private fun parseBlockTextStyle(
    style: String,
    density: Density,
    baseTextStyle: TextStyle,
): TextStyle? {
    val properties = style
        .split(";")
        .mapNotNull { property ->
            val parts = property.split(":", limit = 2)
            if (parts.size == 2) parts[0].trim().lowercase() to parts[1].trim() else null
        }
        .toMap()

    val inlineStyle = parseInlineSpanStyle(
        style = style,
        density = density,
        baseFontSize = baseTextStyle.fontSize,
    )

    var hasStyle = inlineStyle != null
    var textStyle = TextStyle(
        color = inlineStyle?.color ?: Color.Unspecified,
        fontSize = inlineStyle?.fontSize ?: TextUnit.Unspecified,
        fontWeight = inlineStyle?.fontWeight,
        fontStyle = inlineStyle?.fontStyle,
        fontFamily = inlineStyle?.fontFamily,
        letterSpacing = inlineStyle?.letterSpacing ?: TextUnit.Unspecified,
        background = inlineStyle?.background ?: Color.Unspecified,
        textDecoration = inlineStyle?.textDecoration,
    )

    properties["line-height"]?.let { value ->
        parseLineHeight(
            lineHeight = value,
            density = density,
            baseFontSize = baseTextStyle.fontSize,
        )?.let {
            textStyle = textStyle.merge(TextStyle(lineHeight = it))
            hasStyle = true
        }
    }

    properties["text-align"]?.let { value ->
        parseTextAlign(value)?.let {
            textStyle = textStyle.merge(TextStyle(textAlign = it))
            hasStyle = true
        }
    }

    return textStyle.takeIf { hasStyle }
}

private fun parseFontSize(
    fontSize: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = fontSize.trim().lowercase()
    if (normalized.isEmpty()) return null

    fun scaleBase(multiplier: Float): TextUnit? {
        if (!baseFontSize.isSpecified) return null
        return when (baseFontSize.type) {
            TextUnitType.Sp -> (baseFontSize.value * multiplier).sp
            TextUnitType.Em -> (baseFontSize.value * multiplier).em
            else -> null
        }
    }

    val absoluteKeywordScale = when (normalized) {
        "xx-small" -> 0.6f
        "x-small" -> 0.75f
        "small" -> 0.89f
        "medium" -> 1f
        "large" -> 1.2f
        "x-large" -> 1.5f
        "xx-large" -> 2f
        "smaller" -> 0.833f
        "larger" -> 1.2f
        else -> null
    }
    if (absoluteKeywordScale != null) {
        return scaleBase(absoluteKeywordScale)
    }

    return when {
        normalized.endsWith("sp") -> normalized.removeSuffix("sp").trim().toFloatOrNull()?.sp
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }

        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.em
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let {
            if (baseFontSize.isSpecified && baseFontSize.type == TextUnitType.Sp) {
                (baseFontSize.value * it).sp
            } else {
                16.sp * it
            }
        }

        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let {
            scaleBase(it / 100f)
        }

        else -> normalized.toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }
    }
}

private fun parseSpacing(
    spacing: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = spacing.trim().lowercase()
    if (normalized.isEmpty()) return null

    return when {
        normalized.endsWith("sp") -> normalized.removeSuffix("sp").trim().toFloatOrNull()?.sp
        normalized.endsWith("px") -> normalized.removeSuffix("px").trim().toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }

        normalized.endsWith("em") -> normalized.removeSuffix("em").trim().toFloatOrNull()?.em
        normalized.endsWith("rem") -> normalized.removeSuffix("rem").trim().toFloatOrNull()?.let {
            if (baseFontSize.isSpecified && baseFontSize.type == TextUnitType.Sp) {
                (baseFontSize.value * it).sp
            } else {
                16.sp * it
            }
        }

        normalized.endsWith("%") -> normalized.removeSuffix("%").trim().toFloatOrNull()?.let {
            if (!baseFontSize.isSpecified) return@let null
            when (baseFontSize.type) {
                TextUnitType.Sp -> (baseFontSize.value * it / 100f).sp
                TextUnitType.Em -> (baseFontSize.value * it / 100f).em
                else -> null
            }
        }

        else -> normalized.toFloatOrNull()?.let {
            with(density) { it.toSp() }
        }
    }
}

private fun parseLineHeight(
    lineHeight: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = lineHeight.trim().lowercase()
    if (normalized.isEmpty()) return null

    if (normalized.matches(Regex("[0-9]*\\.?[0-9]+"))) {
        if (!baseFontSize.isSpecified) return null
        return when (baseFontSize.type) {
            TextUnitType.Sp -> (baseFontSize.value * normalized.toFloat()).sp
            TextUnitType.Em -> (baseFontSize.value * normalized.toFloat()).em
            else -> null
        }
    }

    return parseFontSize(
        fontSize = normalized,
        density = density,
        baseFontSize = baseFontSize,
    )
}

private fun parseLegacyFontSize(
    fontSize: String,
    density: Density,
    baseFontSize: TextUnit,
): TextUnit? {
    val normalized = fontSize.trim()
    val legacyScale = when (normalized) {
        "1" -> 0.625f
        "2" -> 0.8125f
        "3" -> 1f
        "4" -> 1.125f
        "5" -> 1.5f
        "6" -> 2f
        "7" -> 3f
        else -> null
    }
    if (legacyScale != null) {
        return parseFontSize(
            fontSize = "${legacyScale * 100}%",
            density = density,
            baseFontSize = if (baseFontSize.isSpecified) baseFontSize else 16.sp,
        )
    }

    if ((normalized.startsWith("+") || normalized.startsWith("-")) && baseFontSize.isSpecified) {
        val delta = normalized.toIntOrNull() ?: return null
        val adjustedLevel = (3 + delta).coerceIn(1, 7)
        return parseLegacyFontSize(
            fontSize = adjustedLevel.toString(),
            density = density,
            baseFontSize = baseFontSize,
        )
    }

    return parseFontSize(
        fontSize = normalized,
        density = density,
        baseFontSize = baseFontSize,
    )
}

private fun parseFontFamily(fontFamily: String): FontFamily? {
    val normalized = fontFamily
        .split(",")
        .map { it.trim().trim('"', '\'').lowercase() }
        .firstOrNull()
        ?: return null

    return when {
        normalized.contains("mono") || normalized.contains("courier") -> FontFamily.Monospace
        normalized.contains("serif") || normalized.contains("georgia") || normalized.contains("times") -> FontFamily.Serif
        normalized.contains("sans") || normalized.contains("arial") || normalized.contains("helvetica") -> FontFamily.SansSerif
        normalized.contains("cursive") -> FontFamily.Cursive
        else -> null
    }
}

private fun parseColor(colorString: String): Color? {
    return try {
        when {
            colorString.startsWith("#") -> {
                val hex = colorString.removePrefix("#")
                when (hex.length) {
                    6 -> Color("#$hex".toColorInt())
                    3 -> {
                        val r = hex[0].toString().repeat(2)
                        val g = hex[1].toString().repeat(2)
                        val b = hex[2].toString().repeat(2)
                        Color("#$r$g$b".toColorInt())
                    }

                    else -> null
                }
            }

            colorString.startsWith("rgb(") -> {
                val rgb = colorString.removePrefix("rgb(").removeSuffix(")")
                val values = rgb.split(",").map { it.trim().toIntOrNull() }
                if (values.size == 3 && values.all { it != null && it in 0..255 }) {
                    Color(values[0]!!, values[1]!!, values[2]!!)
                } else null
            }

            colorString.startsWith("rgba(") -> {
                val rgba = colorString.removePrefix("rgba(").removeSuffix(")")
                val values = rgba.split(",").map { it.trim() }
                if (values.size == 4) {
                    val r = values[0].toIntOrNull()
                    val g = values[1].toIntOrNull()
                    val b = values[2].toIntOrNull()
                    val a = values[3].toFloatOrNull()
                    if (r != null && g != null && b != null && a != null &&
                        r in 0..255 && g in 0..255 && b in 0..255 && a in 0f..1f
                    ) {
                        Color(r, g, b, (a * 255).toInt())
                    } else null
                } else null
            }

            else -> {
                when (colorString.lowercase()) {
                    "red" -> Color.Red
                    "green" -> Color.Green
                    "blue" -> Color.Blue
                    "black" -> Color.Black
                    "white" -> Color.White
                    "gray", "grey" -> Color.Gray
                    "yellow" -> Color.Yellow
                    "cyan" -> Color.Cyan
                    "magenta" -> Color.Magenta
                    "orange" -> Color(0xFFFFA500)
                    "purple" -> Color(0xFF800080)
                    "brown" -> Color(0xFFA52A2A)
                    "pink" -> Color(0xFFFFC0CB)
                    else -> null
                }
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun parseFontWeight(weightString: String): FontWeight? {
    return when (weightString.lowercase()) {
        "normal" -> FontWeight.Normal
        "bold" -> FontWeight.SemiBold
        "bolder" -> FontWeight.ExtraBold
        "lighter" -> FontWeight.Light
        "100" -> FontWeight.W100
        "200" -> FontWeight.W200
        "300" -> FontWeight.W300
        "400" -> FontWeight.W400
        "500" -> FontWeight.W500
        "600" -> FontWeight.W600
        "700" -> FontWeight.W700
        "800" -> FontWeight.W800
        "900" -> FontWeight.W900
        else -> null
    }
}

private fun parseFontStyle(fontStyle: String): FontStyle? {
    return when (fontStyle.lowercase()) {
        "italic", "oblique" -> FontStyle.Italic
        "normal" -> FontStyle.Normal
        else -> null
    }
}

private fun parseTextDecoration(textDecoration: String): TextDecoration? {
    val parts = textDecoration.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return null

    val decorations = buildList {
        if ("underline" in parts) add(TextDecoration.Underline)
        if ("line-through" in parts) add(TextDecoration.LineThrough)
    }

    return when (decorations.size) {
        0 -> null
        1 -> decorations.first()
        else -> TextDecoration.combine(decorations)
    }
}

private fun parseTextAlign(textAlign: String): TextAlign? {
    return when (textAlign.trim().lowercase()) {
        "left", "start" -> TextAlign.Start
        "right", "end" -> TextAlign.End
        "center" -> TextAlign.Center
        "justify" -> TextAlign.Justify
        else -> null
    }
}

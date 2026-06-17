package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.JetbrainsMono
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

// ---- Inline group rendering (for list items with mixed inline nodes) ----

/**
 * Renders a list of inline Jsoup nodes as a single Text composable with AnnotatedString.
 * This prevents inline siblings (e.g. <strong>A</strong>和<strong>B</strong>) from being
 * rendered on separate lines.
 */
@Composable
internal fun HtmlInlineGroup(nodes: List<Node>, onClickCitation: (String) -> Unit) {
    val enableLatexRendering = LocalSettings.current.displaySetting.enableLatexRendering
    val colorScheme = MaterialTheme.colorScheme
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current

    val key = remember(nodes) { nodes.joinToString("") { if (it is Element) it.outerHtml() else it.toString() } }
    val (annotatedString, inlineContents) = remember(
        key,
        enableLatexRendering,
        colorScheme,
        density,
        textStyle,
        onClickCitation,
    ) {
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
internal fun HtmlInlineAsComposable(node: Node, onClickCitation: (String) -> Unit) {
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
                        val safeSrc = sanitizeLinkUri(src)
                        if (safeSrc != null && isAllowedImageUri(safeSrc)) {
                            ZoomableAsyncImage(
                                model = safeSrc,
                                contentDescription = alt.takeIf { it.isNotEmpty() },
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                .widthIn(min = 120.dp)
                                .heightIn(min = 120.dp),
                            )
                        } else if (alt.isNotBlank()) {
                            Text(text = alt)
                        }
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
                    val (annotated, inlineContents) = remember(
                        node.outerHtml(),
                        enableLatexRendering,
                        colorScheme,
                        density,
                        textStyle,
                        onClickCitation,
                    ) {
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

internal fun AnnotatedString.Builder.appendHtmlInlineNode(
    node: Node,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
    depth: Int = 0,
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
            depth = depth,
        )
    }
}

internal fun AnnotatedString.Builder.appendHtmlInlineElement(
    element: Element,
    colorScheme: androidx.compose.material3.ColorScheme,
    inlineContents: MutableMap<String, InlineTextContent>,
    density: Density,
    style: TextStyle,
    enableLatexRendering: Boolean,
    onClickCitation: (String) -> Unit,
    depth: Int = 0,
) {
    if (shouldStopHtmlDepthRecursion(depth)) {
        append(element.text())
        return
    }

    val cssStyle = element.attr("style").takeIf { it.isNotBlank() }?.let {
        MarkdownCss.parseInlineSpanStyle(
            style = it,
            density = density,
            baseFontSize = style.fontSize,
        )
    }

    fun recurseChildren(el: Element, inheritedStyle: TextStyle = style) = el.childNodes().fastForEach {
        appendHtmlInlineNode(
            node = it,
            colorScheme = colorScheme,
            inlineContents = inlineContents,
            density = density,
            style = inheritedStyle,
            enableLatexRendering = enableLatexRendering,
            onClickCitation = onClickCitation,
            depth = depth + 1,
        )
    }

    fun appendStyledChildren(spanStyle: SpanStyle) = withStyle(spanStyle) {
        recurseChildren(element, style.merge(spanStyle.asTextStyle()))
    }

    fun appendElementChildren(tagStyle: SpanStyle = SpanStyle()) {
        val elementStyle = tagStyle.merge(cssStyle ?: SpanStyle())
        if (elementStyle == SpanStyle()) {
            recurseChildren(element)
        } else {
            appendStyledChildren(elementStyle)
        }
    }

    when (element.tagName().lowercase()) {
        "b", "strong" -> appendElementChildren(SpanStyle(fontWeight = FontWeight.SemiBold))

        "i", "em" -> appendElementChildren(SpanStyle(fontStyle = FontStyle.Italic))

        "del", "s", "strike" -> appendElementChildren(SpanStyle(textDecoration = TextDecoration.LineThrough))

        "u" -> appendElementChildren(SpanStyle(textDecoration = TextDecoration.Underline))

        "code" -> withStyle(
            SpanStyle(
                fontFamily = JetbrainsMono,
                fontSize = 0.95.em,
                background = colorScheme.surfaceVariant,
                color = colorScheme.primary,
            ).merge(cssStyle ?: SpanStyle())
        ) {
            append(' ')
            append(element.text())
            append(' ')
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
                    val linkStyle = SpanStyle(
                        color = colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ).merge(cssStyle ?: SpanStyle())
                    val safeHref = sanitizeLinkUri(href)
                    if (safeHref == null) {
                        appendElementChildren()
                    } else {
                        withLink(LinkAnnotation.Url(safeHref)) {
                            withStyle(linkStyle) {
                                recurseChildren(element, style.merge(linkStyle.asTextStyle()))
                            }
                        }
                    }
                }

                else -> appendElementChildren()
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
                appendElementChildren()
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
                appendElementChildren()
            }
        }

        "br" -> append("\n")

        else -> appendElementChildren()
    }
}

internal fun shouldStopHtmlDepthRecursion(depth: Int): Boolean = depth >= RenderLimits.MAX_HTML_DEPTH

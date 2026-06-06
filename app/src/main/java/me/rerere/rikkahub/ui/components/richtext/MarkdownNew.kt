package me.rerere.rikkahub.ui.components.richtext

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MarkdownNew"

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
            .catch {
                if (it is CancellationException) throw it
                Log.e(TAG, "Failed to generate markdown html", it)
            }
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
internal fun HtmlStyledElement(
    element: Element,
    content: @Composable () -> Unit,
) {
    val baseTextStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val elementStyle = remember(element.attr("style"), density, baseTextStyle) {
        element.attr("style").takeIf { it.isNotBlank() }?.let {
            MarkdownCss.parseBlockTextStyle(
                style = it,
                density = density,
                baseTextStyle = baseTextStyle,
            )
        }
    }

    if (elementStyle != null) {
        ProvideTextStyle(baseTextStyle.merge(elementStyle), content)
    } else {
        content()
    }
}

@Composable
internal fun HtmlBodyNode(node: Node, onClickCitation: (String) -> Unit) {
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
        "p" -> HtmlParagraph(
            element = element,
            onClickCitation = onClickCitation,
            modifier = if (element.nextElementSibling() != null)
                Modifier.padding(bottom = LocalTextStyle.current.fontSize.toDp())
            else Modifier,
        )

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

        "blockquote" -> HtmlStyledElement(element = element) {
            HtmlBlockquote(element = element, onClickCitation = onClickCitation)
        }

        "table" -> HtmlStyledElement(element = element) {
            HtmlTable(element = element, onClickCitation = onClickCitation)
        }

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
            } else {
                HtmlInlineGroup(nodes = listOf(element), onClickCitation = onClickCitation)
            }
        }

        "details" -> HtmlStyledElement(element = element) {
            HtmlDetails(element = element, onClickCitation = onClickCitation)
        }

        "progress" -> HtmlProgress(element = element)

        "div" -> HtmlStyledElement(element = element) {
            Column(modifier = Modifier.fillMaxWidth()) {
                element.childNodes().fastForEach { HtmlBodyNode(it, onClickCitation) }
            }
        }

        else -> HtmlStyledElement(element = element) {
            // Generic fallback: recurse into children
            element.childNodes().forEach { HtmlBodyNode(it, onClickCitation) }
        }
    }
}

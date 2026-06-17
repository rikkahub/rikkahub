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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.ext.toDp
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "MarkdownNew"

// ---- Main composable ----

@Composable
fun MarkdownNew(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
) {
    // 在后台线程生成HTML, 防止首帧及频繁更新时在主线程解析导致掉帧。
    // 初始值为 null：生成完成前不渲染；失败时保留上一次的有效HTML。
    val html by produceState<String?>(initialValue = null, key1 = content) {
        value = withContext(Dispatchers.Default) {
            runCatching { generateMarkdownHtml(content) }
                .onFailure {
                    if (it is CancellationException) throw it
                    Log.e(TAG, "Failed to generate markdown html", it)
                }
                .getOrNull() ?: value
        }
    }

    val document = remember(html) {
        val h = html ?: return@remember null
        runCatching { Jsoup.parse(h) }.getOrElse { Jsoup.parse("") }
    }

    document?.let { doc ->
        ProvideTextStyle(style) {
            Column(modifier = modifier.padding(start = 4.dp)) {
                doc.body().childNodes().fastForEach { node ->
                    HtmlBodyNode(node = node, onClickCitation = onClickCitation)
                }
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
internal fun HtmlBodyNode(
    node: Node,
    onClickCitation: (String) -> Unit,
    depth: Int = 0,
) {
    when (node) {
        is Element -> HtmlBlockElement(
            element = node,
            onClickCitation = onClickCitation,
            depth = depth,
        )
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
    depth: Int = 0,
) {
    if (shouldStopHtmlDepthRecursion(depth)) {
        Text(text = element.text())
        return
    }

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
            depth = depth + 1,
        )

        "ol" -> HtmlList(
            element = element,
            ordered = true,
            onClickCitation = onClickCitation,
            level = listLevel,
            depth = depth + 1,
        )

        "pre" -> HtmlCodeBlock(element = element)

        "blockquote" -> HtmlStyledElement(element = element) {
            HtmlBlockquote(
                element = element,
                onClickCitation = onClickCitation,
                depth = depth + 1,
            )
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
                val safeSrc = sanitizeLinkUri(src)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            HtmlDetails(
                element = element,
                onClickCitation = onClickCitation,
                depth = depth + 1,
            )
        }

        "progress" -> HtmlProgress(element = element)

        "div" -> HtmlStyledElement(element = element) {
            Column(modifier = Modifier.fillMaxWidth()) {
                element.childNodes().fastForEach {
                    HtmlBodyNode(node = it, onClickCitation = onClickCitation, depth = depth + 1)
                }
            }
        }

        else -> HtmlStyledElement(element = element) {
            // Generic fallback: recurse into children
            element.childNodes().forEach {
                HtmlBodyNode(node = it, onClickCitation = onClickCitation, depth = depth + 1)
            }
        }
    }
}

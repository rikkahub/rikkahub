package me.rerere.ui.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.util.fastForEach

internal class MarkdownContext(
    val theme: MarkdownTheme,
    val components: MarkdownComponents,
    /** 为 null 时链接交给系统的 UriHandler 打开 */
    val linkListener: LinkInteractionListener?,
)

internal val LocalMarkdownContext = staticCompositionLocalOf<MarkdownContext> {
    error("LocalMarkdownContext is only available inside Markdown()")
}

/** 把一组行内节点渲染成一个 Text */
@Composable
internal fun InlineText(content: List<MarkdownInline>, modifier: Modifier = Modifier) {
    val context = LocalMarkdownContext.current
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    val contentColor = style.color.takeOrElse { LocalContentColor.current }
    val rendered = remember(content, context, style, density, contentColor) {
        InlineTextBuilder(context, MarkdownInlineScope(style, density, contentColor)).build(content)
    }
    Text(
        text = rendered.text,
        modifier = modifier,
        inlineContent = rendered.inlineContent,
        // 行内公式可能比一行文字高，固定行高会让它和上下行叠在一起
        style = if (rendered.hasMathWidget) style.copy(lineHeight = TextUnit.Unspecified) else style,
    )
}

private class RenderedInlineText(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val hasMathWidget: Boolean,
)

private val MATH_SOURCE_STYLE = SpanStyle(fontFamily = FontFamily.Monospace)

private class InlineTextBuilder(
    private val context: MarkdownContext,
    private val scope: MarkdownInlineScope,
) {
    private val builder = AnnotatedString.Builder()
    private val inlineContent = mutableMapOf<String, InlineTextContent>()
    private var hasMathWidget = false

    fun build(content: List<MarkdownInline>): RenderedInlineText {
        append(content)
        return RenderedInlineText(builder.toAnnotatedString(), inlineContent, hasMathWidget)
    }

    private fun append(nodes: List<MarkdownInline>) = nodes.fastForEach { append(it) }

    private fun append(node: MarkdownInline) {
        when (node) {
            is MarkdownInline.Text -> builder.append(node.text)
            is MarkdownInline.Styled -> builder.withStyle(node.style) { append(node.children) }
            is MarkdownInline.Code -> builder.withStyle(context.theme.inlineCode) { append(node.code) }
            is MarkdownInline.Link -> appendLink(node)
            is MarkdownInline.Math -> appendMath(node)
            // 图片在分段时已经被拆出去单独渲染，走到这里只剩替代文本可用
            is MarkdownInline.Image -> builder.append(node.alt.orEmpty())
        }
    }

    private fun appendLink(link: MarkdownInline.Link) {
        val widget = context.components.inlineLink?.invoke(scope, link)
        if (widget != null) {
            appendWidget(widget, alternateText = link.children.plainText())
            return
        }
        val annotation = LinkAnnotation.Url(
            url = link.destination,
            styles = TextLinkStyles(style = context.theme.link),
            linkInteractionListener = context.linkListener,
        )
        builder.withLink(annotation) { append(link.children) }
    }

    private fun appendMath(math: MarkdownInline.Math) {
        val widgets = context.components.inlineMath?.invoke(scope, math.formula).orEmpty()
        if (widgets.isEmpty()) {
            builder.withStyle(MATH_SOURCE_STYLE) { append(math.formula) }
            return
        }
        hasMathWidget = true
        widgets.forEachIndexed { index, widget ->
            // 零宽空格给拆开的公式提供换行点
            if (index > 0) builder.append('​')
            appendWidget(widget, alternateText = math.formula)
        }
    }

    private fun appendWidget(widget: MarkdownInlineWidget, alternateText: String) {
        val id = "markdown:${inlineContent.size}"
        inlineContent[id] = widget.toInlineTextContent()
        // alternateText 不允许为空
        builder.appendInlineContent(id, alternateText.ifEmpty { "�" })
    }
}

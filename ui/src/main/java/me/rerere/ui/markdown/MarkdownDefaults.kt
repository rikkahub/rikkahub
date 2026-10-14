package me.rerere.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Image02

/** Markdown 的视觉参数 */
@Immutable
data class MarkdownTheme(
    /** h1 ~ h6 的文字样式，会合并到正文样式上 */
    val headings: List<TextStyle>,
    val link: SpanStyle,
    val inlineCode: SpanStyle,
    /** 块与块之间的间距 */
    val blockSpacing: Dp,
    /** 列表符号、序号和任务复选框的颜色 */
    val markerColor: Color,
    /** 引用块左侧竖线的颜色 */
    val quoteColor: Color,
    val dividerColor: Color,
    /** 正文里引号（"…"、“…”、「…」、『…』）括起来的文字的样式，为 null 时不高亮 */
    val quotedText: SpanStyle? = null,
)

/** 嵌在文本流里的自定义内容，按 [placeholder] 的尺寸占位 */
@Immutable
class MarkdownInlineWidget(
    val placeholder: Placeholder,
    val content: @Composable () -> Unit,
)

/** 构建行内自定义内容时可用的环境信息 */
@Immutable
class MarkdownInlineScope(
    /** 所在段落的文字样式 */
    val style: TextStyle,
    val density: Density,
    val contentColor: Color,
)

/**
 * 可替换的渲染插槽。组件本身不带图片加载、代码高亮和公式排版，这些由使用方通过插槽接入。
 *
 * 实例应当保持稳定（定义为顶层常量或用 remember 包住），否则每次重组都会重建全部文本。
 */
@Immutable
class MarkdownComponents(
    val codeBlock: @Composable (block: MarkdownBlock.CodeBlock) -> Unit = { MarkdownDefaults.CodeBlock(it) },
    /** link 不为空表示图片被包在链接里 */
    val image: @Composable (image: MarkdownInline.Image, link: String?) -> Unit = { image, link ->
        MarkdownDefaults.Image(image, link)
    },
    val mathBlock: @Composable (formula: String) -> Unit = { MarkdownDefaults.MathBlock(it) },
    val table: @Composable (table: MarkdownBlock.Table) -> Unit = { MarkdownDefaults.Table(it) },
    /**
     * 行内公式。返回多个时会在它们之间允许换行，用于把过长的公式拆开；
     * 为 null 或返回空列表时以等宽字体显示公式源码。
     */
    val inlineMath: (MarkdownInlineScope.(formula: String) -> List<MarkdownInlineWidget>)? = null,
    /** 自定义某些链接的行内渲染（例如引用角标），返回 null 时按普通链接处理 */
    val inlineLink: (MarkdownInlineScope.(link: MarkdownInline.Link) -> MarkdownInlineWidget?)? = null,
)

object MarkdownDefaults {
    val components = MarkdownComponents()

    private val HeadingScales = floatArrayOf(1.5f, 1.375f, 1.25f, 1.125f, 1f, 0.875f)

    /** 标题字号按 [textStyle] 的字号等比放大，正文字号变化时标题跟着变 */
    @Composable
    fun theme(textStyle: TextStyle = LocalTextStyle.current): MarkdownTheme {
        val fontSize = LocalTextStyle.current.merge(textStyle).fontSize
        val colorScheme = MaterialTheme.colorScheme
        return remember(fontSize, colorScheme) {
            MarkdownTheme(
                headings = HeadingScales.map { scale ->
                    if (fontSize.isSp) {
                        TextStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize * scale,
                            lineHeight = fontSize * (scale * 1.25f),
                        )
                    } else {
                        TextStyle(fontWeight = FontWeight.Bold)
                    }
                },
                link = SpanStyle(color = colorScheme.primary, textDecoration = TextDecoration.Underline),
                inlineCode = SpanStyle(
                    color = colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.9.em,
                ),
                blockSpacing = 12.dp,
                markerColor = colorScheme.primary,
                quoteColor = colorScheme.outlineVariant,
                dividerColor = colorScheme.outlineVariant,
            )
        }
    }

    /** 不带语法高亮的代码块 */
    @Composable
    fun CodeBlock(block: MarkdownBlock.CodeBlock, modifier: Modifier = Modifier) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                block.language?.let { language ->
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = block.code,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    softWrap = false,
                )
            }
        }
    }

    /** 模块里没有图片加载能力，默认只显示替代文本，点击后打开图片地址 */
    @Composable
    fun Image(image: MarkdownInline.Image, link: String?, modifier: Modifier = Modifier) {
        val uriHandler = LocalUriHandler.current
        Surface(
            onClick = { runCatching { uriHandler.openUri(link ?: image.url) } },
            modifier = modifier,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = HugeIcons.Image02,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = image.alt ?: image.url,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /**
     * 默认的表格，只能在 Markdown 的插槽里调用。
     * 想在外面包一层带工具栏的卡片时，可以用 [outerBorder] 和 [shape] 去掉表格自带的边框和圆角。
     */
    @Composable
    fun Table(
        table: MarkdownBlock.Table,
        modifier: Modifier = Modifier,
        outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape: Shape = MaterialTheme.shapes.small,
    ) {
        MarkdownTable(table, modifier, outerBorder, shape)
    }

    /** 以等宽字体显示公式源码 */
    @Composable
    fun MathBlock(formula: String, modifier: Modifier = Modifier) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formula,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

internal fun MarkdownInlineWidget.toInlineTextContent() = InlineTextContent(placeholder) { content() }

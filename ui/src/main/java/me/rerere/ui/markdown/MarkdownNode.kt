package me.rerere.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * 解析后的 Markdown 文档。
 *
 * 所有节点都是不可变的 data class：流式输出时内容没变的块与上一次解析结果相等，
 * 对应的 Composable 会被直接跳过，只有真正变化的块才会重组。
 */
@Immutable
data class MarkdownDocument(
    val source: String,
    val blocks: List<MarkdownBlock>,
)

/** 块级节点 */
@Immutable
sealed interface MarkdownBlock {
    @Immutable
    data class Paragraph(
        val content: List<MarkdownInline>,
        val style: TextStyle? = null,
    ) : MarkdownBlock

    @Immutable
    data class Heading(
        val level: Int,
        val content: List<MarkdownInline>,
        val style: TextStyle? = null,
    ) : MarkdownBlock

    /** [closed] 为 false 表示围栏尚未闭合（流式输出中），此时不应触发预览之类依赖完整代码的功能 */
    @Immutable
    data class CodeBlock(
        val code: String,
        val language: String? = null,
        val closed: Boolean = true,
    ) : MarkdownBlock

    @Immutable
    data class Quote(val children: List<MarkdownBlock>) : MarkdownBlock

    /** [tight] 为 true 表示列表项之间没有空行，渲染时使用更紧凑的间距 */
    @Immutable
    data class ListBlock(
        val ordered: Boolean,
        val items: List<ListItem>,
        val start: Int = 1,
        val tight: Boolean = true,
    ) : MarkdownBlock

    /**
     * [alignments] 是各列的对齐方式，取自表头单元格。
     * [source] 是表格的原始 Markdown 文本，由 HTML `<table>` 标签写出的表格没有。
     */
    @Immutable
    data class Table(
        val header: List<TableCell>,
        val rows: List<List<TableCell>>,
        val alignments: List<TextAlign?> = emptyList(),
        val source: String? = null,
    ) : MarkdownBlock

    @Immutable
    data object Divider : MarkdownBlock

    /** `<details>` 折叠块 */
    @Immutable
    data class Details(
        val summary: List<MarkdownInline>,
        val children: List<MarkdownBlock>,
        val open: Boolean = false,
    ) : MarkdownBlock

    /** 带样式的 HTML 块级容器，例如 `<div style="text-align: center">` */
    @Immutable
    data class Container(
        val children: List<MarkdownBlock>,
        val style: TextStyle,
    ) : MarkdownBlock
}

/** [checked] 非 null 时是任务列表项 */
@Immutable
data class ListItem(
    val children: List<MarkdownBlock>,
    val checked: Boolean? = null,
)

/** [style] 是单元格自己的样式（含从表格和所在行继承来的），其中的对齐方式优先于所在列的 */
@Immutable
data class TableCell(
    val content: List<MarkdownInline>,
    val style: TextStyle? = null,
)

/** 行内节点 */
@Immutable
sealed interface MarkdownInline {
    @Immutable
    data class Text(val text: String) : MarkdownInline

    /** 加粗、斜体、删除线以及 HTML 行内样式都归一成 [SpanStyle] */
    @Immutable
    data class Styled(
        val style: SpanStyle,
        val children: List<MarkdownInline>,
    ) : MarkdownInline

    @Immutable
    data class Code(val code: String) : MarkdownInline

    @Immutable
    data class Link(
        val destination: String,
        val children: List<MarkdownInline>,
        val title: String? = null,
    ) : MarkdownInline

    @Immutable
    data class Image(
        val url: String,
        val alt: String? = null,
        val title: String? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : MarkdownInline

    /** [display] 为 true 是独占一行的块级公式 */
    @Immutable
    data class Math(
        val formula: String,
        val display: Boolean = false,
    ) : MarkdownInline
}

/** 提取纯文本，可用于复制、导出 CSV 或无障碍描述 */
fun List<MarkdownInline>.plainText(): String = buildString { appendPlainText(this@plainText) }

private fun StringBuilder.appendPlainText(nodes: List<MarkdownInline>) {
    for (node in nodes) {
        when (node) {
            is MarkdownInline.Text -> append(node.text)
            is MarkdownInline.Styled -> appendPlainText(node.children)
            is MarkdownInline.Code -> append(node.code)
            is MarkdownInline.Link -> appendPlainText(node.children)
            is MarkdownInline.Image -> append(node.alt.orEmpty())
            is MarkdownInline.Math -> append(node.formula)
        }
    }
}

/**
 * 段落内容按渲染方式切出的片段。
 *
 * 图片和块级公式没法放进一个 Text 里，所以把段落在这些位置切开，各自作为独立的 Composable 排布。
 */
internal sealed interface InlineSegment {
    data class Text(val content: List<MarkdownInline>) : InlineSegment
    data class Image(val image: MarkdownInline.Image, val link: String?) : InlineSegment
    data class Math(val formula: String) : InlineSegment
}

internal fun List<MarkdownInline>.toSegments(): List<InlineSegment> {
    if (none { it.hasEmbedded() }) return listOf(InlineSegment.Text(this))

    val segments = mutableListOf<InlineSegment>()
    val run = mutableListOf<MarkdownInline>()

    fun flush() {
        if (run.any { it !is MarkdownInline.Text || it.text.isNotBlank() }) {
            segments += InlineSegment.Text(run.toList())
        }
        run.clear()
    }

    // 图片可能嵌在链接或样式节点里，提出来的同时用 wrap 把剩下的文字包回原来的节点
    fun visit(node: MarkdownInline, link: String?, wrap: (MarkdownInline) -> MarkdownInline) {
        when {
            node is MarkdownInline.Image -> {
                flush()
                segments += InlineSegment.Image(node, link)
            }

            node is MarkdownInline.Math && node.display -> {
                flush()
                segments += InlineSegment.Math(node.formula)
            }

            node is MarkdownInline.Styled && node.hasEmbedded() -> node.children.forEach { child ->
                visit(child, link) { wrap(node.copy(children = listOf(it))) }
            }

            node is MarkdownInline.Link && node.hasEmbedded() -> node.children.forEach { child ->
                visit(child, node.destination) { wrap(node.copy(children = listOf(it))) }
            }

            else -> run += wrap(node)
        }
    }

    forEach { node -> visit(node, null) { it } }
    flush()
    return segments
}

private fun MarkdownInline.hasEmbedded(): Boolean = when (this) {
    is MarkdownInline.Image -> true
    is MarkdownInline.Math -> display
    is MarkdownInline.Styled -> children.any { it.hasEmbedded() }
    is MarkdownInline.Link -> children.any { it.hasEmbedded() }
    else -> false
}

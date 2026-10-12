package me.rerere.ui.markdown

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.ui.table.DataTable
import kotlin.math.max

@Composable
internal fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    spacing: Dp = LocalMarkdownContext.current.theme.blockSpacing,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        blocks.fastForEachIndexed { index, block ->
            MarkdownBlockNode(block = block, first = index == 0)
        }
    }
}

// 节点都是不可变的 data class，block 与上次相等时这个函数整个被跳过，流式输出时只有变化的块会重组
@Composable
private fun MarkdownBlockNode(block: MarkdownBlock, first: Boolean) {
    val context = LocalMarkdownContext.current
    when (block) {
        is MarkdownBlock.Paragraph -> WithTextStyle(block.style) { InlineContent(block.content) }
        is MarkdownBlock.Heading -> HeadingBlock(block, first)
        is MarkdownBlock.CodeBlock -> context.components.codeBlock(block)
        is MarkdownBlock.Quote -> QuoteBlock(block)
        is MarkdownBlock.ListBlock -> ListBlock(block)
        is MarkdownBlock.Table -> context.components.table(block)
        is MarkdownBlock.Divider -> HorizontalDivider(color = context.theme.dividerColor)
        is MarkdownBlock.Details -> DetailsBlock(block)
        is MarkdownBlock.Container -> WithTextStyle(block.style) { MarkdownBlocks(block.children) }
    }
}

@Composable
private fun WithTextStyle(style: TextStyle?, content: @Composable () -> Unit) {
    if (style != null) {
        ProvideTextStyle(style.resolveFontSize(LocalTextStyle.current.fontSize), content)
    } else {
        content()
    }
}

// HTML 样式里的相对字号解析成了 em，但段落级的样式没有父级可以参照，这里按当前字号换算成具体的值
private fun TextStyle.resolveFontSize(base: TextUnit): TextStyle = when {
    !fontSize.isEm -> this
    base.isSp -> copy(fontSize = base * fontSize.value)
    else -> copy(fontSize = TextUnit.Unspecified)
}

/**
 * 渲染一段行内内容。纯文本是一个 Text；夹着图片或块级公式时，按片段放进 FlowRow 里流式排布。
 */
@Composable
private fun InlineContent(
    content: List<MarkdownInline>,
    modifier: Modifier = Modifier,
    fillWidthToAlign: Boolean = true,
) {
    val components = LocalMarkdownContext.current.components
    val segments = remember(content) { content.toSegments() }
    val textAlign = LocalTextStyle.current.textAlign
    // 默认按内容宽度布局，放进聊天气泡时不会把气泡撑满；只有非起始对齐才需要占满整行
    val alignedModifier = if (fillWidthToAlign && textAlign.needsFullWidth()) modifier.fillMaxWidth() else modifier

    when (val single = segments.singleOrNull()) {
        is InlineSegment.Text -> InlineText(single.content, alignedModifier)
        is InlineSegment.Math -> Box(modifier) { components.mathBlock(single.formula) }
        else -> FlowRow(
            modifier = alignedModifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp, textAlign.toHorizontalAlignment()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            segments.fastForEach { segment ->
                when (segment) {
                    is InlineSegment.Text -> InlineText(segment.content)
                    is InlineSegment.Image -> components.image(segment.image, segment.link)
                    is InlineSegment.Math -> Box(Modifier.fillMaxWidth()) { components.mathBlock(segment.formula) }
                }
            }
        }
    }
}

private fun TextAlign.needsFullWidth() =
    this == TextAlign.Center || this == TextAlign.End || this == TextAlign.Right || this == TextAlign.Justify

private fun TextAlign.toHorizontalAlignment() = when (this) {
    TextAlign.Center -> Alignment.CenterHorizontally
    TextAlign.End, TextAlign.Right -> Alignment.End
    else -> Alignment.Start
}

@Composable
private fun HeadingBlock(block: MarkdownBlock.Heading, first: Boolean) {
    val theme = LocalMarkdownContext.current.theme
    val style = theme.headings.getOrNull(block.level - 1) ?: TextStyle.Default
    val baseFontSize = style.fontSize.takeOrElse { LocalTextStyle.current.fontSize }
    ProvideTextStyle(style.merge(block.style?.resolveFontSize(baseFontSize))) {
        InlineContent(
            content = block.content,
            // 标题和上文之间多留一点距离，让它更靠近自己引出的内容
            modifier = Modifier
                .padding(top = if (first) 0.dp else theme.blockSpacing / 2)
                .semantics { heading() },
        )
    }
}

@Composable
private fun QuoteBlock(block: MarkdownBlock.Quote) {
    val barColor = LocalMarkdownContext.current.theme.quoteColor
    val textColor = LocalTextStyle.current.color.takeOrElse { LocalContentColor.current }
    ProvideTextStyle(TextStyle(color = textColor.copy(alpha = textColor.alpha * 0.75f))) {
        MarkdownBlocks(
            blocks = block.children,
            modifier = Modifier
                .drawBehind {
                    val barWidth = 3.dp.toPx()
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
                .padding(start = 12.dp),
        )
    }
}

private val LocalListDepth = compositionLocalOf { 0 }
private val BULLETS = listOf("•", "◦", "▪")

@Composable
private fun ListBlock(block: MarkdownBlock.ListBlock) {
    val theme = LocalMarkdownContext.current.theme
    val depth = LocalListDepth.current
    val spacing = if (block.tight) theme.blockSpacing / 3 else theme.blockSpacing
    CompositionLocalProvider(LocalListDepth provides depth + 1) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            block.items.fastForEachIndexed { index, item ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        item.checked != null -> TaskCheckbox(item.checked)
                        block.ordered -> Text(text = "${block.start + index}.", color = theme.markerColor)
                        else -> Text(text = BULLETS[depth % BULLETS.size], color = theme.markerColor)
                    }
                    MarkdownBlocks(
                        blocks = item.children,
                        modifier = Modifier.weight(1f, fill = false),
                        spacing = spacing,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCheckbox(checked: Boolean) {
    val color = LocalMarkdownContext.current.theme.markerColor
    val style = LocalTextStyle.current
    val (boxSize, lineHeight) = with(LocalDensity.current) {
        val fontSize = if (style.fontSize.isSpecified) style.fontSize.toDp() else 16.dp
        fontSize to if (style.lineHeight.isSpecified && style.lineHeight.isSp) style.lineHeight.toDp() else fontSize * 1.4f
    }
    // 外层占一行文字的高度，让复选框和第一行文字垂直居中对齐
    Box(
        modifier = Modifier.height(lineHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(boxSize)
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = HugeIcons.Tick01,
                    contentDescription = null,
                    modifier = Modifier.size(boxSize * 0.8f),
                    tint = color,
                )
            }
        }
    }
}

@Composable
internal fun MarkdownTable(
    block: MarkdownBlock.Table,
    modifier: Modifier,
    outerBorder: BorderStroke?,
    shape: Shape,
) {
    val columnCount = max(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)
    val headers: List<@Composable () -> Unit> = block.header.mapIndexed { index, cell ->
        @Composable {
            TableCellContent(cell, block.alignments.getOrNull(index), FontWeight.SemiBold)
        }
    }
    val rows: List<List<@Composable () -> Unit>> = block.rows.map { row ->
        row.mapIndexed { index, cell ->
            @Composable {
                TableCellContent(cell, block.alignments.getOrNull(index), null)
            }
        }
    }
    DataTable(
        headers = headers,
        rows = rows,
        modifier = modifier,
        columnMinWidths = List(columnCount) { 80.dp },
        columnMaxWidths = List(columnCount) { 200.dp },
        columnAlignments = List(columnCount) { index ->
            block.alignments.getOrNull(index)?.toCellAlignment() ?: Alignment.CenterStart
        },
        bodyCellAlignment = { row, column ->
            block.rows.getOrNull(row)?.getOrNull(column)?.style?.textAlign?.toCellAlignment()
        },
        outerBorder = outerBorder,
        shape = shape,
    )
}

private fun TextAlign.toCellAlignment(): Alignment? = when (this) {
    TextAlign.Unspecified -> null
    TextAlign.Center -> Alignment.Center
    TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

@Composable
private fun TableCellContent(cell: TableCell, columnAlign: TextAlign?, fontWeight: FontWeight?) {
    val columnStyle = TextStyle(fontWeight = fontWeight, textAlign = columnAlign ?: TextAlign.Unspecified)
    // 单元格自己的样式优先于列的对齐方式和表头的字重
    ProvideTextStyle(columnStyle.merge(cell.style?.resolveFontSize(LocalTextStyle.current.fontSize))) {
        // 列宽由内容决定，单元格在列内的对齐交给表格，这里不能为了对齐把宽度撑满
        InlineContent(cell.content, fillWidthToAlign = false)
    }
}

@Composable
private fun DetailsBlock(block: MarkdownBlock.Details) {
    val theme = LocalMarkdownContext.current.theme
    // 流式输出时标签是一点点出现的，open 属性后到的话要跟着更新
    var expanded by rememberSaveable(block.open) { mutableStateOf(block.open) }
    val rotation by animateFloatAsState(if (expanded) 90f else 0f)
    Column {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = HugeIcons.ArrowRight01,
                contentDescription = null,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(rotation),
            )
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.Medium)) {
                InlineText(block.summary, Modifier.weight(1f, fill = false))
            }
        }
        AnimatedVisibility(visible = expanded) {
            MarkdownBlocks(
                blocks = block.children,
                modifier = Modifier.padding(start = 22.dp, top = theme.blockSpacing / 2),
            )
        }
    }
}

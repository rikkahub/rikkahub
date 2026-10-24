package me.rerere.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Tag

// 解析流程：Markdown --(JetBrains Markdown)--> HTML --(Jsoup)--> DOM --> MarkdownDocument
//
// Markdown 解析器会把内嵌的 HTML 拆成零散的节点（行内标签是一个个独立的 token，块级 HTML 遇到空行就断开），
// 直接在 AST 上渲染没法还原标签的嵌套关系。这里统一先生成 HTML 再交给 Jsoup，
// 由它把 Markdown 生成的标签和原文里手写的标签拼成同一棵 DOM 树，于是只需要维护 DOM -> 节点这一套转换。

private val flavour = GFMFlavourDescriptor(useSafeLinks = true, makeHttpsAutoLinks = true)
private val markdownParser = MarkdownParser(flavour)

/**
 * 把 Markdown 文本解析成 [MarkdownDocument]。纯计算、线程安全，长文本建议放在后台线程调用。
 *
 * @param softBreakAsLineBreak 段落内的单个换行是否渲染为换行（聊天场景的习惯）；为 false 时按 CommonMark 当作空格
 */
fun parseMarkdown(content: String, softBreakAsLineBreak: Boolean = true): MarkdownDocument {
    // 解析器只认 \n 作为换行，CRLF 里残留的 \r 会让围栏行匹配不上（比如结尾的 ``` 不被当作闭合）
    val text = normalizeLatexDelimiters(content.replace(LINE_ENDING_REGEX, "\n"))
    val tree = markdownParser.buildMarkdownTreeFromString(text)
    val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, text), null) + mapOf(
        MarkdownTokenTypes.HTML_TAG to InlineHtmlTagProvider,
        GFMElementTypes.INLINE_MATH to MathProvider(inline = true),
        GFMElementTypes.BLOCK_MATH to MathProvider(inline = false),
    )
    val html = HtmlGenerator(text, tree, providers, false).generateHtml(tagRenderer)
    val body = Jsoup.parse(html).body()
    return MarkdownDocument(
        source = content,
        blocks = DomConverter(text, softBreakAsLineBreak).flow(body.childNodes(), markdown = false),
    )
}

// ---- 预处理 ----

private val LINE_ENDING_REGEX = Regex("""\r\n?""")

// 第 1 组匹配代码（围栏未闭合时一直算到结尾）和公式里的换行 \\，用来跳过这些内容，
// 不然 \\[4pt] 里的 \[ 会被当成块级公式的开头；第 2、3 组分别是 \( \) 和 \[ \]
private val LATEX_DELIMITER_REGEX =
    Regex("""(```[\s\S]*?(?:```|\z)|`[^`\n]*`|\\\\)|\\\((.+?)\\\)|\\\[([\s\S]+?)\\]""")
private val LATEX_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

/** 把 `\( \)`、`\[ \]` 形式的公式定界符换成解析器认识的 `$ $`、`$$ $$` */
internal fun normalizeLatexDelimiters(content: String): String {
    if ('\\' !in content) return content
    return LATEX_DELIMITER_REGEX.replace(content) { match ->
        val inline = match.groups[2]?.value?.trim()
        when {
            match.groups[1] != null -> match.value
            // 解析器要求 $ 内侧不能有空白，而 \( x \) 这种带空格的写法很常见，所以要去掉
            inline != null -> if (inline.isEmpty()) match.value else "$$inline$"
            // 块级公式里的换行会打断解析，合并成一行
            else -> "$$" + match.groupValues[3].trim().replace(LATEX_LINE_BREAK_REGEX, " ") + "$$"
        }
    }
}

// ---- 生成 HTML ----

// Markdown 生成的这些标签会带上标记，转换时据此区分文本来自 Markdown 还是手写的 HTML（两者的空白处理规则不同）
private const val MARKDOWN_ATTR = "data-md"
private val MARKDOWN_TEXT_TAGS = setOf("p", "li", "th", "td", "h1", "h2", "h3", "h4", "h5", "h6")

// HTML 里保留不了的源码信息，通过自定义属性带到 DOM 上
private val tagRenderer = HtmlGenerator.DefaultTagRenderer(
    customizer = { node, tagName, attributes ->
        val tag = tagName.toString()
        when {
            tag == "code" && node.type == MarkdownElementTypes.CODE_FENCE -> {
                val closed = node.children.any { it.type == MarkdownTokenTypes.CODE_FENCE_END }
                attributes + "data-closed=\"$closed\""
            }

            tag == "table" -> attributes + "data-src=\"${node.startOffset},${node.endOffset}\""
            tag in MARKDOWN_TEXT_TAGS -> attributes + MARKDOWN_ATTR
            else -> attributes
        }
    },
    includeSrcPositions = false,
)

private val HTML_TAG_NAME_REGEX = Regex("""^</?([a-zA-Z][a-zA-Z0-9-]*)""")

/**
 * 行内的 `<xxx>` 只有是已知的 HTML 标签时才当作 HTML 输出，否则转义成普通文本。
 * 不然正文里的 `List<String>`、`<answer>` 这类内容会被当成未知标签吞掉。
 */
private object InlineHtmlTagProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val tag = node.getTextInNode(text)
        val name = HTML_TAG_NAME_REGEX.find(tag)?.groupValues?.get(1)
        if (name != null && !Tag.isKnownTag(name.lowercase())) {
            visitor.consumeHtml(escapeHtml(tag))
        } else {
            visitor.consumeHtml(tag)
        }
    }
}

/**
 * 代替 GFM 自带的公式输出。它把公式源码原样写进 HTML，`0<x<1`、`\lambda(x)<k` 里的 `<`
 * 会被 Jsoup 当成标签的开头，公式就从那里断掉了，所以这里先转义再输出。
 */
private class MathProvider(private val inline: Boolean) : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        // 首尾两个子节点是 $ 或 $$ 定界符
        val formula = node.children.subList(1, node.children.size - 1).joinToString("") { it.getTextInNode(text) }
        visitor.consumeTagOpen(node, "span", "class=\"math\"", "inline=\"$inline\"")
        visitor.consumeHtml(escapeHtml(formula.trimSurroundingBackticks()))
        visitor.consumeTagClose("span")
    }

    // 兼容 $`x`$ 这种写法，去掉成对包住公式的反引号
    private fun String.trimSurroundingBackticks(): String {
        var n = 0
        while (length - 2 * n > 2 && this[n] == '`' && this[lastIndex - n] == '`') n++
        return substring(n, length - n).trim()
    }
}

private fun escapeHtml(text: CharSequence): String =
    text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

// ---- DOM -> 节点 ----

private val BLOCK_TAGS = setOf(
    "p", "div", "section", "article", "aside", "header", "footer", "main", "nav", "figure", "figcaption",
    "center", "address", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "dl", "dt", "dd", "pre",
    "blockquote", "table", "hr", "details", "summary", "form", "fieldset",
)

// 这些标签的内容不是给人看的正文，或者这里画不出来，整棵子树直接跳过
private val SKIPPED_TAGS = setOf(
    "script", "style", "head", "title", "meta", "link", "template", "noscript", "svg", "math", "iframe",
    "object", "embed", "canvas", "video", "audio", "input", "select", "textarea", "button",
)

private val CODE_TAGS = setOf("code", "kbd", "samp", "tt")

private val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
private val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
private val STRIKETHROUGH = SpanStyle(textDecoration = TextDecoration.LineThrough)
private val UNDERLINE = SpanStyle(textDecoration = TextDecoration.Underline)

private val TAG_STYLES = mapOf(
    "b" to BOLD,
    "strong" to BOLD,
    "i" to ITALIC,
    "em" to ITALIC,
    "cite" to ITALIC,
    "var" to ITALIC,
    "dfn" to ITALIC,
    "del" to STRIKETHROUGH,
    "s" to STRIKETHROUGH,
    "strike" to STRIKETHROUGH,
    "u" to UNDERLINE,
    "ins" to UNDERLINE,
    "sub" to SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Subscript),
    "sup" to SpanStyle(fontSize = 0.75.em, baselineShift = BaselineShift.Superscript),
    "small" to SpanStyle(fontSize = 0.85.em),
    "big" to SpanStyle(fontSize = 1.2.em),
    "mark" to SpanStyle(background = Color(0x66FFEB3B)),
)

private val HTML_WHITESPACE_REGEX = Regex("""\s+""")
private val SOFT_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

private class DomConverter(
    private val source: String,
    private val softBreakAsLineBreak: Boolean,
) {
    /**
     * 转换一组混排的子节点：块级元素各自成块，夹在中间的连续行内内容合成一个隐式段落。
     *
     * @param markdown 这组节点里的裸文本是否来自 Markdown（而不是手写的 HTML）
     */
    fun flow(nodes: List<Node>, markdown: Boolean): List<MarkdownBlock> {
        val blocks = mutableListOf<MarkdownBlock>()
        val run = mutableListOf<Node>()

        fun flush() {
            if (run.isEmpty()) return
            val content = inlines(run, markdown).trimmed()
            if (content.isNotEmpty()) blocks += MarkdownBlock.Paragraph(content)
            run.clear()
        }

        for (node in nodes) {
            if (node is Element && node.normalName() in BLOCK_TAGS) {
                flush()
                block(node, blocks)
            } else {
                run += node
            }
        }
        flush()
        return blocks
    }

    private fun block(element: Element, out: MutableList<MarkdownBlock>) {
        val markdown = element.hasAttr(MARKDOWN_ATTR)
        when (val tag = element.normalName()) {
            "p" -> {
                val content = inlines(element.childNodes(), markdown).trimmed()
                if (content.isNotEmpty()) out += MarkdownBlock.Paragraph(content, element.blockStyle())
            }

            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val content = inlines(element.childNodes(), markdown).trimmed()
                if (content.isNotEmpty()) {
                    out += MarkdownBlock.Heading(tag[1].digitToInt(), content, element.blockStyle())
                }
            }

            "ul", "ol" -> out += list(element, ordered = tag == "ol")
            "pre" -> out += codeBlock(element)
            "table" -> table(element)?.let { out += it }
            "hr" -> out += MarkdownBlock.Divider
            "details" -> out += details(element)

            "blockquote" -> {
                val children = flow(element.childNodes(), markdown = false)
                if (children.isNotEmpty()) out += MarkdownBlock.Quote(children)
            }

            else -> {
                val children = flow(element.childNodes(), markdown)
                val style = element.blockStyle()
                when {
                    children.isEmpty() -> Unit
                    style == null -> out += children
                    else -> out += MarkdownBlock.Container(children, style)
                }
            }
        }
    }

    private fun list(element: Element, ordered: Boolean): MarkdownBlock.ListBlock {
        val items = element.children().filter { it.normalName() == "li" }
        return MarkdownBlock.ListBlock(
            ordered = ordered,
            items = items.map { item ->
                // 嵌套列表里的复选框排在后面，selectFirst 取到的一定是当前项自己的
                val checkbox = if (item.hasClass("task-list-item")) item.selectFirst("input[type=checkbox]") else null
                ListItem(
                    children = flow(item.childNodes(), item.hasAttr(MARKDOWN_ATTR)),
                    checked = checkbox?.hasAttr("checked"),
                )
            },
            start = element.attr("start").toIntOrNull() ?: 1,
            // 松散列表（列表项之间有空行）的内容会被包进 <p>
            tight = items.none { item -> item.children().any { it.normalName() == "p" } },
        )
    }

    private fun codeBlock(element: Element): MarkdownBlock.CodeBlock {
        val code = element.selectFirst("code")
        return MarkdownBlock.CodeBlock(
            code = (code ?: element).wholeText().trimEnd('\n'),
            language = code?.classNames()
                ?.firstOrNull { it.startsWith("language-") }
                ?.removePrefix("language-")
                ?.takeIf { it.isNotBlank() },
            closed = code?.attr("data-closed") != "false",
        )
    }

    private fun table(element: Element): MarkdownBlock.Table? {
        // 不处理嵌套表格的行
        val rows = element.select("tr").filter { it.closest("table") === element }
        if (rows.isEmpty()) return null

        // 表格和行上的样式由单元格继承。<table align> 指的是表格自身的摆放位置，不算文字对齐
        val tableStyle = parseBlockStyle(element.attr("style"))
        fun cells(row: Element): List<TableCell> {
            val rowStyle = tableStyle.mergeWith(row.blockStyle())
            return row.children().filter { it.normalName() == "th" || it.normalName() == "td" }.map { cell ->
                TableCell(
                    content = inlines(cell.childNodes(), cell.hasAttr(MARKDOWN_ATTR)).trimmed(),
                    style = rowStyle.mergeWith(cell.blockStyle()),
                )
            }
        }

        val header = cells(rows.first())
        val sourceRange = element.attr("data-src").split(',').mapNotNull { it.toIntOrNull() }
        return MarkdownBlock.Table(
            header = header,
            rows = rows.drop(1).map(::cells),
            alignments = header.map { cell -> cell.style?.textAlign?.takeIf { it != TextAlign.Unspecified } },
            // 手写的 HTML 也可能带这个属性，范围不合法就当作没有
            source = sourceRange.takeIf { it.size == 2 && it[0] in 0..it[1] && it[1] <= source.length }
                ?.let { (start, end) -> source.substring(start, end).trim() },
        )
    }

    private fun details(element: Element): MarkdownBlock.Details {
        val summary = element.children().firstOrNull { it.normalName() == "summary" }
        return MarkdownBlock.Details(
            summary = summary?.let { inlines(it.childNodes(), markdown = false).trimmed() }.orEmpty(),
            children = flow(element.childNodes().filter { it !== summary }, markdown = false),
            open = element.hasAttr("open"),
        )
    }

    private fun inlines(nodes: List<Node>, markdown: Boolean): List<MarkdownInline> {
        val out = mutableListOf<MarkdownInline>()
        nodes.forEach { inline(it, markdown, out) }
        return out
    }

    private fun inline(node: Node, markdown: Boolean, out: MutableList<MarkdownInline>) {
        when (node) {
            is TextNode -> out.appendText(normalizeText(node.wholeText, markdown))
            is Element -> inlineElement(node, markdown, out)
        }
    }

    private fun inlineElement(element: Element, markdown: Boolean, out: MutableList<MarkdownInline>) {
        val tag = element.normalName()
        when {
            tag in SKIPPED_TAGS -> Unit
            tag == "br" -> out.appendText("\n")
            tag in CODE_TAGS -> out += MarkdownInline.Code(element.wholeText())

            tag == "img" -> element.attr("src").takeIf { it.isNotBlank() }?.let { src ->
                out += MarkdownInline.Image(
                    url = src,
                    alt = element.attr("alt").takeIf { it.isNotBlank() },
                    title = element.attr("title").takeIf { it.isNotBlank() },
                    width = element.attr("width").toIntOrNull(),
                    height = element.attr("height").toIntOrNull(),
                )
            }

            tag == "span" && element.hasClass("math") -> out += MarkdownInline.Math(
                formula = element.wholeText().trim(),
                display = element.attr("inline") != "true",
            )

            tag == "a" && element.attr("href").isNotBlank() -> {
                val children = inlines(element.childNodes(), markdown)
                if (children.isNotEmpty()) {
                    out += MarkdownInline.Link(
                        destination = element.attr("href"),
                        children = children,
                        title = element.attr("title").takeIf { it.isNotBlank() },
                    )
                }
            }

            else -> {
                // 行内上下文里遇到块级元素（例如单元格里的 <p>），退化成换行分隔的文本
                val isBlock = tag in BLOCK_TAGS
                if (isBlock && out.isNotEmpty()) out.appendText("\n")
                val children = inlines(element.childNodes(), if (isBlock) element.hasAttr(MARKDOWN_ATTR) else markdown)
                val style = element.inlineStyle()
                when {
                    children.isEmpty() -> Unit
                    style == null -> children.forEach {
                        if (it is MarkdownInline.Text) {
                            out.appendText(it.text)
                        } else {
                            out +=
                                it
                        }
                    }
                    else -> out += MarkdownInline.Styled(style, children)
                }
            }
        }
    }

    private fun normalizeText(text: String, markdown: Boolean): String = when {
        // 手写 HTML 里的缩进和换行只是排版，按 HTML 的规则折叠成一个空格
        !markdown -> text.replace(HTML_WHITESPACE_REGEX, " ")
        '\n' !in text -> text
        else -> text.replace(SOFT_BREAK_REGEX, if (softBreakAsLineBreak) "\n" else " ")
    }
}

private fun Element.blockStyle(): TextStyle? = parseBlockStyle(
    css = attr("style"),
    align = attr("align").ifBlank { if (normalName() == "center") "center" else null },
)

private fun TextStyle?.mergeWith(other: TextStyle?): TextStyle? = when {
    this == null -> other
    other == null -> this
    else -> merge(other)
}

private fun Element.inlineStyle(): SpanStyle? {
    val tag = normalName()
    val tagStyle = when {
        tag == "font" -> parseFontTag(color = attr("color"), size = attr("size"), face = attr("face"))
        // GFM 的 ~~删除线~~ 生成的是这个 class
        tag == "span" && hasClass("user-del") -> STRIKETHROUGH
        else -> TAG_STYLES[tag]
    }
    val cssStyle = parseSpanStyle(attr("style"))
    return when {
        tagStyle == null -> cssStyle
        cssStyle == null -> tagStyle
        else -> tagStyle.merge(cssStyle)
    }
}

private fun MutableList<MarkdownInline>.appendText(text: String) {
    val last = lastOrNull() as? MarkdownInline.Text
    // 换行后面紧跟的空白没有意义：<br> 后面的换行符不该再多出一个空行
    val appended = if (last != null && last.text.endsWith('\n')) text.trimStart() else text
    if (appended.isEmpty()) return
    if (last != null) {
        this[lastIndex] = MarkdownInline.Text(last.text + appended)
    } else {
        this += MarkdownInline.Text(appended)
    }
}

private fun List<MarkdownInline>.trimmed(): List<MarkdownInline> {
    if (isEmpty()) return this
    val result = toMutableList()
    (result.first() as? MarkdownInline.Text)?.let { first ->
        val text = first.text.trimStart()
        if (text.isEmpty()) result.removeAt(0) else result[0] = MarkdownInline.Text(text)
    }
    (result.lastOrNull() as? MarkdownInline.Text)?.let { last ->
        val text = last.text.trimEnd()
        if (text.isEmpty()) result.removeAt(result.lastIndex) else result[result.lastIndex] = MarkdownInline.Text(text)
    }
    return result
}

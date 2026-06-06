package me.rerere.rikkahub.ui.components.richtext

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Pure (non-Compose) Markdown parsing seam, extracted out of Markdown.kt and MarkdownNew.kt
 * (issue #110) so the parse work can be invoked off the composition thread and unit-tested
 * directly. Mirrors the #106 MarkdownCssParsing.kt extraction.
 *
 * Same GFM flavour flags (makeHttpsAutoLinks = true, useSafeLinks = true) and AST/HTML
 * generation as before. One intentional unification: master had TWO divergent block-latex
 * preprocessors — Markdown.kt (AST path) trimmed + collapsed newlines, MarkdownNew.kt (HTML
 * path) emitted the raw capture. Deleting the duplicate forces one definition; we keep the
 * trim + newline-collapse variant because that is the correct, dominant behaviour: the HTML
 * path's only block-latex consumer is HtmlMathBlock(formula = element.text()) (MarkdownNew.kt),
 * and jsoup's element.text() already collapses whitespace runs to single spaces — so the raw
 * variant produced the same rendered formula anyway. The AST path (which has no such
 * downstream collapse) genuinely depends on the trim/collapse, so it wins.
 */
private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val LATEX_BLOCK_LINE_BREAK_REGEX = Regex("""[ \t]*\r?\n[ \t]*""")

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

private val parser by lazy {
    MarkdownParser(flavour)
}

internal data class MarkdownParseResult(
    val preprocessed: String,
    val astTree: ASTNode,
    val hasHtml: Boolean,
)

// 预处理markdown内容
internal fun preProcess(content: String): String {
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            val formula = matchResult.groupValues[1]
                .trim()
                .replace(LATEX_BLOCK_LINE_BREAK_REGEX, " ")
            "$$" + formula + "$$"
        }
    }

    return result
}

private fun ASTNode.containsHtml(): Boolean {
    if (type == MarkdownElementTypes.HTML_BLOCK || type == MarkdownTokenTypes.HTML_TAG) return true
    return children.any { it.containsHtml() }
}

internal fun parseMarkdown(content: String): MarkdownParseResult {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    return MarkdownParseResult(preprocessed, astTree, astTree.containsHtml())
}

internal fun generateMarkdownHtml(content: String): String {
    val preprocessed = preProcess(content)
    val tree = parser.buildMarkdownTreeFromString(preprocessed)
    return HtmlGenerator(preprocessed, tree, flavour).generateHtml()
}

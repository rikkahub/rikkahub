package me.rerere.rikkahub.data.telegram

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Convert markdown produced by the LLM into the tiny HTML subset Telegram's Bot API
 * accepts with parse_mode=HTML. Anything outside the allowed tag set is unwrapped (its
 * children are kept as inline text). The output is safe to pass directly to sendMessage
 * / editMessageText with parseMode = "HTML".
 *
 * Telegram-supported tags (per https://core.telegram.org/bots/api#html-style):
 *   <b> <i> <u> <s>
 *   <a href="...">
 *   <code> <pre>
 *   <pre><code class="language-X">…</code></pre>
 *   <tg-spoiler>
 *   <blockquote>
 *
 * Headings render as bold + paragraph break. Lists render as "- item\n" or "N. item\n".
 * Tables are flattened to "cell | cell\n" rows. Images become "[image: alt]" since we
 * can't inline them through editMessageText.
 */
object TelegramHtmlRenderer {

    private val flavour by lazy {
        GFMFlavourDescriptor(makeHttpsAutoLinks = true, useSafeLinks = true)
    }
    private val parser by lazy { MarkdownParser(flavour) }

    /** Render markdown to Telegram-flavoured HTML. */
    fun render(markdown: String): String {
        if (markdown.isBlank()) return ""
        val html = runCatching {
            val tree = parser.buildMarkdownTreeFromString(markdown)
            HtmlGenerator(markdown, tree, flavour).generateHtml()
        }.getOrElse {
            // If the parser blows up, degrade to escaped plain text.
            return escape(markdown)
        }
        // jsoup wraps fragment input in <html><body>…</body></html>; strip back to body.
        val body = Jsoup.parseBodyFragment(html).body()
        val out = StringBuilder()
        for (child in body.childNodes()) {
            renderNode(child, out)
        }
        return out.toString()
            // Collapse runs of >2 blank lines so heavy markdown doesn't visually shred
            // the Telegram bubble.
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Best-effort fallback: strip every HTML tag and return only the text content. Used
     * by callers that catch a Telegram parse-mode error and want to retry the same
     * message as plain text without the user seeing an empty bubble.
     */
    fun stripHtml(html: String): String {
        if (html.isBlank()) return html
        return runCatching { Jsoup.parse(html).text() }.getOrElse { html }
    }

    private fun renderNode(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> out.append(escape(node.wholeText))
            is Element -> renderElement(node, out)
            else -> for (c in node.childNodes()) renderNode(c, out)
        }
    }

    private fun renderElement(el: Element, out: StringBuilder) {
        when (el.tagName().lowercase()) {
            // ---- Telegram-supported wrappers: emit as-is, recurse children. ----
            "b", "strong" -> wrap(el, out, "b")
            "i", "em" -> wrap(el, out, "i")
            "u", "ins" -> wrap(el, out, "u")
            "s", "del", "strike" -> wrap(el, out, "s")
            "code" -> {
                // Inline <code>: just escape, no nested tags. Also handle the
                // "this <code> is inside a <pre>" case so we don't double-wrap.
                val parent = el.parent()?.tagName()?.lowercase()
                if (parent == "pre") {
                    out.append(escape(el.text()))
                } else {
                    out.append("<code>").append(escape(el.text())).append("</code>")
                }
            }
            "pre" -> {
                // Code block. If a child <code class="language-xyz"> is present, hoist
                // the language so Telegram syntax-highlights it.
                val code = el.selectFirst("code")
                val lang = code?.classNames()
                    ?.firstOrNull { it.startsWith("language-") }
                    ?.removePrefix("language-")
                val body = (code ?: el).wholeText()
                out.append("<pre>")
                if (!lang.isNullOrBlank()) {
                    out.append("<code class=\"language-").append(escapeAttr(lang)).append("\">")
                    out.append(escape(body))
                    out.append("</code>")
                } else {
                    out.append(escape(body))
                }
                out.append("</pre>")
                ensureBlankLine(out)
            }
            "a" -> {
                val href = el.attr("href")
                if (href.isNullOrBlank()) {
                    // Anchor without href is meaningless; just emit children inline.
                    for (c in el.childNodes()) renderNode(c, out)
                } else {
                    out.append("<a href=\"").append(escapeAttr(href)).append("\">")
                    for (c in el.childNodes()) renderNode(c, out)
                    out.append("</a>")
                }
            }
            "blockquote" -> {
                out.append("<blockquote>")
                for (c in el.childNodes()) renderNode(c, out)
                out.append("</blockquote>")
                ensureBlankLine(out)
            }
            // ---- Block elements: render children, then a paragraph break. ----
            "p" -> {
                for (c in el.childNodes()) renderNode(c, out)
                ensureBlankLine(out)
            }
            "br" -> out.append('\n')
            "hr" -> {
                ensureBlankLine(out)
                out.append("───")
                ensureBlankLine(out)
            }
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                ensureBlankLine(out)
                out.append("<b>")
                for (c in el.childNodes()) renderNode(c, out)
                out.append("</b>")
                ensureBlankLine(out)
            }
            "ul" -> renderList(el, out, ordered = false)
            "ol" -> renderList(el, out, ordered = true)
            "li" -> {
                // <li> outside a list (rare) — render children with a leading bullet.
                out.append("• ")
                for (c in el.childNodes()) renderNode(c, out)
                if (!out.endsWith('\n')) out.append('\n')
            }
            "table" -> renderTable(el, out)
            "img" -> {
                val alt = el.attr("alt").ifBlank { el.attr("title") }.ifBlank { "image" }
                out.append("[image: ").append(escape(alt)).append("]")
            }
            // ---- Anything else: drop the wrapper, keep the content. ----
            else -> for (c in el.childNodes()) renderNode(c, out)
        }
    }

    private fun wrap(el: Element, out: StringBuilder, tag: String) {
        out.append("<").append(tag).append(">")
        for (c in el.childNodes()) renderNode(c, out)
        out.append("</").append(tag).append(">")
    }

    private fun renderList(listEl: Element, out: StringBuilder, ordered: Boolean) {
        val items = listEl.children().filter { it.tagName().equals("li", ignoreCase = true) }
        if (items.isEmpty()) return
        // Make sure there's a clean line before the list.
        if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
        items.forEachIndexed { idx, li ->
            val prefix = if (ordered) "${idx + 1}. " else "• "
            out.append(prefix)
            for (c in li.childNodes()) renderNode(c, out)
            if (!out.endsWith('\n')) out.append('\n')
        }
        ensureBlankLine(out)
    }

    private fun renderTable(table: Element, out: StringBuilder) {
        // Telegram has no table support — flatten to "cell | cell\n" rows so the data is
        // at least readable. Header rows get bolded.
        ensureBlankLine(out)
        for (row in table.select("tr")) {
            val cells = row.children().filter {
                val t = it.tagName().lowercase()
                t == "td" || t == "th"
            }
            if (cells.isEmpty()) continue
            val isHeader = cells.any { it.tagName().equals("th", ignoreCase = true) }
            cells.forEachIndexed { i, cell ->
                if (i > 0) out.append(" | ")
                if (isHeader) out.append("<b>")
                for (c in cell.childNodes()) renderNode(c, out)
                if (isHeader) out.append("</b>")
            }
            out.append('\n')
        }
        ensureBlankLine(out)
    }

    private fun ensureBlankLine(out: StringBuilder) {
        if (out.isEmpty()) return
        if (out.endsWith("\n\n")) return
        if (out.endsWith('\n')) out.append('\n') else out.append("\n\n")
    }

    /** HTML-escape text content for Telegram (only the three chars that matter). */
    fun escape(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    /** Stricter escape for attribute values (adds quote escaping). */
    private fun escapeAttr(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        for (ch in s) when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}

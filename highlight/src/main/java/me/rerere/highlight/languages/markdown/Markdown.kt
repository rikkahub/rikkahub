package me.rerere.highlight.languages.markdown

import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.mode

/** Markdown, ported from `lib/languages/markdown.js` of `highlight.js` 11.11.1. */
internal fun markdown(): Language {
    val inlineHtml = mode {
        begin = """<\/?[A-Za-z_]"""
        end = ">"
        subLanguage = "xml"
        relevance = 0.0
    }
    val horizontalRule = mode {
        begin = """^[-\*]{3,}"""
        end = "${'$'}"
    }
    val code = mode {
        scope = "code"
        variants = listOf(
            // Upstream TODO: make these work with a sub-language as well.
            { begin = """(`{3,})[^`](.|\n)*?\1`*[ ]*""" },
            { begin = """(~{3,})[^~](.|\n)*?\1~*[ ]*""" },
            // Needed so that markdown works when used as a sub-language itself.
            {
                begin = "```"
                end = """```+[ ]*${'$'}"""
            },
            {
                begin = "~~~"
                end = """~~~+[ ]*${'$'}"""
            },
            { begin = """`.+?`""" },
            {
                begin = """(?=^( {4}|\t))"""
                // The nested mode gobbles up line after line, so that a block of any size still
                // produces a single token instead of one per line.
                contains = listOf(
                    mode {
                        begin = """^( {4}|\t)"""
                        end = """(\n)${'$'}"""
                    },
                )
                relevance = 0.0
            },
        )
    }
    val list = mode {
        scope = "bullet"
        begin = """^[ \t]*([*+-]|(\d+\.))(?=\s+)"""
        end = """\s+"""
        excludeEnd = true
    }
    val linkReference = mode {
        begin = """^\[[^\n]+\]:"""
        returnBegin = true
        contains = listOf(
            mode {
                scope = "symbol"
                begin = """\["""
                end = """\]"""
                excludeBegin = true
                excludeEnd = true
            },
            mode {
                scope = "link"
                begin = """:\s*"""
                end = "${'$'}"
                excludeBegin = true
            },
        )
    }
    val urlScheme = """[A-Za-z][A-Za-z0-9+.-]*"""
    val link = mode {
        variants = listOf(
            // Too much like nested array access in too many languages to carry any real relevance.
            {
                begin = """\[.+?\]\[.*?\]"""
                relevance = 0.0
            },
            // Popular internet URLs.
            {
                begin = """\[.+?\]\(((data|javascript|mailto):|(?:http|ftp)s?:\/\/).*?\)"""
                relevance = 2.0
            },
            {
                begin = concat("""\[.+?\]\(""", urlScheme, """:\/\/.*?\)""")
                relevance = 2.0
            },
            // Relative URLs.
            {
                begin = """\[.+?\]\([./?&#].*?\)"""
                relevance = 1.0
            },
            // Whatever else, at a lower relevance — it might not be a link at all.
            {
                begin = """\[.*?\]\(.*?\)"""
                relevance = 0.0
            },
        )
        returnBegin = true
        contains = listOf(
            // Empty alt or link text.
            mode { match = """\[(?=\])""" },
            mode {
                scope = "string"
                relevance = 0.0
                begin = """\["""
                end = """\]"""
                excludeBegin = true
                returnEnd = true
            },
            mode {
                scope = "link"
                relevance = 0.0
                begin = """\]\("""
                end = """\)"""
                excludeBegin = true
                excludeEnd = true
            },
            mode {
                scope = "symbol"
                relevance = 0.0
                begin = """\]\["""
                end = """\]"""
                excludeBegin = true
                excludeEnd = true
            },
        )
    }
    val bold = mode {
        scope = "strong"
        // Filled in below.
        contains = emptyList()
        variants = listOf(
            {
                begin = """_{2}(?!\s)"""
                end = """_{2}"""
            },
            {
                begin = """\*{2}(?!\s)"""
                end = """\*{2}"""
            },
        )
    }
    val italic = mode {
        scope = "emphasis"
        // Filled in below.
        contains = emptyList()
        variants = listOf(
            {
                begin = """\*(?![*\s])"""
                end = """\*"""
            },
            {
                begin = """_(?![_\s])"""
                end = "_"
                relevance = 0.0
            },
        )
    }

    // Three levels of nesting are not allowed, because `***testing***` would then be ambiguous:
    // there would be no telling whether the trailing `***` opens a new bold/italic or closes the
    // previous one.
    val boldWithoutItalic = bold.inherit { contains = emptyList() }
    val italicWithoutBold = italic.inherit { contains = emptyList() }
    bold.contains = listOf(italicWithoutBold)
    italic.contains = listOf(boldWithoutItalic)

    var containable: List<Mode> = listOf(inlineHtml, link)
    listOf(bold, italic, boldWithoutItalic, italicWithoutBold).forEach { mode ->
        mode.contains = mode.contains + containable
    }
    containable = containable + bold + italic

    val header = mode {
        scope = "section"
        variants = listOf(
            {
                begin = """^#{1,6}"""
                end = "${'$'}"
                contains = containable
            },
            {
                // A setext header, recognised by the `===` or `---` on the line below it.
                begin = """(?=^.+?\n[=-]{2,}${'$'})"""
                contains = listOf(
                    mode { begin = """^[=-]*${'$'}""" },
                    mode {
                        begin = "^"
                        end = """\n"""
                        contains = containable
                    },
                )
            },
        )
    }
    val blockquote = mode {
        scope = "quote"
        begin = """^>\s+"""
        contains = containable
        end = "${'$'}"
    }
    // https://spec.commonmark.org/0.31.2/#entity-references
    val entity = mode {
        scope = "literal"
        match = """&([a-zA-Z0-9]+|#[0-9]{1,7}|#[Xx][0-9a-fA-F]{1,6});"""
    }

    return Language(
        name = "Markdown",
        aliases = setOf("markdown", "md", "mkdown", "mkd"),
        root = mode {
            contains = listOf(
                header,
                inlineHtml,
                list,
                bold,
                italic,
                blockquote,
                code,
                horizontalRule,
                link,
                linkReference,
                entity,
            )
        },
    )
}

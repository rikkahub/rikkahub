package me.rerere.highlight.languages.java

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/** Java, ported from `lib/languages/java.js` of `highlight.js` 11.11.1. */
internal fun java(): Language {
    // The `\uXXXX` escapes are left for `java.util.regex` to resolve, exactly as upstream leaves
    // them to the JavaScript engine.
    val identRe = """[\u00C0-\u02B8a-zA-Z_${'$'}][\u00C0-\u02B8a-zA-Z_${'$'}0-9]*"""
    val genericIdentRe = identRe + recurRegex(
        re = """(?:<$identRe~~~(?:\s*,\s*$identRe~~~)*>)?""",
        depth = 2,
    )

    val mainKeywords = listOf(
        "synchronized", "abstract", "private", "var", "static", "if", "const ", "for", "while",
        "strictfp", "finally", "protected", "import", "native", "final", "void", "enum", "else",
        "break", "transient", "catch", "instanceof", "volatile", "case", "assert", "package",
        "default", "public", "try", "switch", "continue", "throws", "protected", "public",
        "private", "module", "requires", "exports", "do", "sealed", "yield", "permits", "goto",
        "when",
    )
    val javaKeywords = keywords {
        keyword(mainKeywords)
        literal(listOf("false", "true", "null"))
        type(listOf("char", "boolean", "long", "float", "int", "byte", "short", "double"))
        builtIn(listOf("super", "this"))
    }

    val annotation = mode {
        scope = "meta"
        begin = "@$identRe"
        contains = listOf(
            mode {
                begin = """\("""
                end = """\)"""
                // Allow nested parentheses inside the annotation.
                contains = listOf(Mode.SELF)
            },
        )
    }
    val recordParams = mode {
        scope = "params"
        begin = """\("""
        end = """\)"""
        keywords = javaKeywords
        relevance = 0.0
        contains = listOf(C_BLOCK_COMMENT_MODE)
        endsParent = true
    }
    val numeric = javaNumeric()

    return Language(
        name = "Java",
        aliases = setOf("java", "jsp"),
        root = mode {
            keywords = javaKeywords
            illegal = """</|#"""
            contains = listOf(
                comment("""/\*\*""", """\*/""") {
                    relevance = 0.0
                    contains = listOf(
                        // Eat up the `@` of an email address so it is not taken for a doctag.
                        mode {
                            begin = """\w+@"""
                            relevance = 0.0
                        },
                        mode {
                            scope = "doctag"
                            begin = """@[A-Za-z]+"""
                        },
                    )
                },
                // Relevance boost.
                mode {
                    begin = """import java\.[a-z]+\."""
                    keywords = keywords("import")
                    relevance = 2.0
                },
                C_LINE_COMMENT_MODE,
                C_BLOCK_COMMENT_MODE,
                mode {
                    scope = "string"
                    begin = TRIPLE_QUOTE
                    end = TRIPLE_QUOTE
                    contains = listOf(BACKSLASH_ESCAPE)
                },
                APOS_STRING_MODE,
                QUOTE_STRING_MODE,
                mode {
                    matchList = listOf(
                        """\b(?:class|interface|enum|extends|implements|new)""",
                        """\s+""",
                        identRe,
                    )
                    scopes = mapOf(1 to "keyword", 3 to "title.class")
                },
                mode {
                    // Exception for hyphenated keywords.
                    match = """non-sealed"""
                    scope = "keyword"
                },
                mode {
                    beginList = listOf(
                        concat("""(?!else)""", identRe),
                        """\s+""",
                        identRe,
                        """\s+""",
                        """=(?!=)""",
                    )
                    beginScopes = mapOf(1 to "type", 3 to "variable", 5 to "operator")
                },
                mode {
                    beginList = listOf("""record""", """\s+""", identRe)
                    beginScopes = mapOf(1 to "keyword", 3 to "title.class")
                    contains = listOf(recordParams, C_LINE_COMMENT_MODE, C_BLOCK_COMMENT_MODE)
                },
                mode {
                    // Expression keywords keep `keyword Name(...)` from reading as a definition.
                    beginKeywords = "new throw return else"
                    relevance = 0.0
                },
                mode {
                    beginList = listOf(
                        "(?:$genericIdentRe\\s+)",
                        UNDERSCORE_IDENT_RE,
                        """\s*(?=\()""",
                    )
                    beginScopes = mapOf(2 to "title.function")
                    keywords = javaKeywords
                    contains = listOf(
                        mode {
                            scope = "params"
                            begin = """\("""
                            end = """\)"""
                            keywords = javaKeywords
                            relevance = 0.0
                            contains = listOf(
                                annotation,
                                APOS_STRING_MODE,
                                QUOTE_STRING_MODE,
                                numeric,
                                C_BLOCK_COMMENT_MODE,
                            )
                        },
                        C_LINE_COMMENT_MODE,
                        C_BLOCK_COMMENT_MODE,
                    )
                },
                numeric,
                annotation,
            )
        },
    )
}

private const val TRIPLE_QUOTE = "\"\"\""

/**
 * Replaces every `~~~` in [re] with [depth] further copies of [re], mirroring `recurRegex()`
 * upstream. It is how a grammar spells a bounded recursion — nested generics, here.
 *
 * `recurRegex("(abc~~~)", depth = 2)` becomes `(abc(abc(abc)))`.
 */
private fun recurRegex(re: String, depth: Int): String {
    if (depth == -1) return ""
    return RECURSION_PLACEHOLDER.replace(re) { recurRegex(re, depth - 1) }
}

private val RECURSION_PLACEHOLDER = Regex("~~~")

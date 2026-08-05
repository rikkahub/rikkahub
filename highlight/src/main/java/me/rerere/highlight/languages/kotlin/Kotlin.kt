package me.rerere.highlight.languages.kotlin

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.C_NUMBER_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.UNDERSCORE_TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode
import me.rerere.highlight.languages.java.javaNumeric

/** Kotlin, ported from `lib/languages/kotlin.js` of `highlight.js` 11.11.1. */
internal fun kotlin(): Language {
    val kotlinKeywords = keywords {
        keyword(
            "abstract as val var vararg get set class object open private protected public " +
                "noinline crossinline dynamic final enum if else do while for when throw try " +
                "catch finally import package is in fun override companion reified inline " +
                "lateinit init interface annotation data sealed internal infix operator out by " +
                "constructor super tailrec where const inner suspend typealias external expect " +
                "actual"
        )
        builtIn("Byte Short Char Int Long Boolean Float Double Void Unit Nothing")
        literal("true false null")
    }

    val keywordsWithLabel = mode {
        scope = "keyword"
        begin = """\b(break|continue|return|this)\b"""
        starts = mode {
            contains = listOf(
                mode {
                    scope = "symbol"
                    begin = """@\w+"""
                },
            )
        }
    }
    val label = mode {
        scope = "symbol"
        begin = UNDERSCORE_IDENT_RE + "@"
    }

    // String templates.
    val subst = mode {
        scope = "subst"
        begin = """\${'$'}\{"""
        end = """\}"""
        contains = listOf(C_NUMBER_MODE)
    }
    val variable = mode {
        scope = "variable"
        begin = """\${'$'}""" + UNDERSCORE_IDENT_RE
    }
    val string = mode {
        scope = "string"
        variants = listOf(
            {
                begin = TRIPLE_QUOTE
                end = TRIPLE_QUOTE + """(?=[^"])"""
                contains = listOf(variable, subst)
            },
            // The built in string modes cannot be reused here: a string nested in the meta context
            // has to lose its scope, and there is no syntax for unsetting one.
            {
                begin = "'"
                end = "'"
                illegal = """\n"""
                contains = listOf(BACKSLASH_ESCAPE)
            },
            {
                begin = "\""
                end = "\""
                illegal = """\n"""
                contains = listOf(BACKSLASH_ESCAPE, variable, subst)
            },
        )
    }
    subst.contains = subst.contains + string

    val annotationUseSite = mode {
        scope = "meta"
        begin = """@(?:file|property|field|get|set|receiver|param|setparam|delegate)\s*""" +
            """:(?:\s*$UNDERSCORE_IDENT_RE)?"""
    }
    val annotation = mode {
        scope = "meta"
        begin = "@$UNDERSCORE_IDENT_RE"
        contains = listOf(
            mode {
                begin = """\("""
                end = """\)"""
                contains = listOf(string.inherit { scope = "string" }, Mode.SELF)
            },
        )
    }

    // Kotlin allows the same underscore separated numeric literals as Java 8, so the number mode is
    // shared with that grammar rather than restated here.
    val numberMode = javaNumeric()
    val nestedComment = comment("""/\*""", """\*/""") {
        contains = listOf(C_BLOCK_COMMENT_MODE)
    }

    // A parenthesised type contains parenthesised types; the two mutually recursive definitions
    // upstream are one self referencing mode, because both names point at the same object there.
    val parenType = Mode()
    parenType.variants = listOf(
        {
            scope = "type"
            begin = UNDERSCORE_IDENT_RE
        },
        {
            begin = """\("""
            end = """\)"""
            contains = listOf(parenType)
        },
    )

    return Language(
        name = "Kotlin",
        aliases = setOf("kotlin", "kt", "kts"),
        root = mode {
            keywords = kotlinKeywords
            contains = listOf(
                comment("""/\*\*""", """\*/""") {
                    relevance = 0.0
                    contains = listOf(
                        mode {
                            scope = "doctag"
                            begin = """@[A-Za-z]+"""
                        },
                    )
                },
                C_LINE_COMMENT_MODE,
                nestedComment,
                keywordsWithLabel,
                label,
                annotationUseSite,
                annotation,
                mode {
                    scope = "function"
                    beginKeywords = "fun"
                    end = """[(]|${'$'}"""
                    returnBegin = true
                    excludeEnd = true
                    keywords = kotlinKeywords
                    relevance = 5.0
                    contains = listOf(
                        mode {
                            begin = UNDERSCORE_IDENT_RE + """\s*\("""
                            returnBegin = true
                            relevance = 0.0
                            contains = listOf(UNDERSCORE_TITLE_MODE)
                        },
                        mode {
                            scope = "type"
                            begin = """<"""
                            end = """>"""
                            keywords = keywords("reified")
                            relevance = 0.0
                        },
                        mode {
                            scope = "params"
                            begin = """\("""
                            end = """\)"""
                            endsParent = true
                            keywords = kotlinKeywords
                            relevance = 0.0
                            contains = listOf(
                                mode {
                                    begin = """:"""
                                    end = """[=,\/]"""
                                    endsWithParent = true
                                    contains = listOf(
                                        parenType,
                                        C_LINE_COMMENT_MODE,
                                        nestedComment,
                                    )
                                    relevance = 0.0
                                },
                                C_LINE_COMMENT_MODE,
                                nestedComment,
                                annotationUseSite,
                                annotation,
                                string,
                                C_NUMBER_MODE,
                            )
                        },
                        nestedComment,
                    )
                },
                mode {
                    beginList = listOf("""class|interface|trait""", """\s+""", UNDERSCORE_IDENT_RE)
                    beginScopes = mapOf(3 to "title.class")
                    keywords = keywords("class interface trait")
                    end = """[:\{(]|${'$'}"""
                    excludeEnd = true
                    illegal = "extends implements"
                    contains = listOf(
                        mode { beginKeywords = "public protected internal private constructor" },
                        UNDERSCORE_TITLE_MODE,
                        mode {
                            scope = "type"
                            begin = """<"""
                            end = """>"""
                            excludeBegin = true
                            excludeEnd = true
                            relevance = 0.0
                        },
                        mode {
                            scope = "type"
                            begin = """[,:]\s*"""
                            end = """[<\(,){\s]|${'$'}"""
                            excludeBegin = true
                            returnEnd = true
                        },
                        annotationUseSite,
                        annotation,
                    )
                },
                string,
                mode {
                    scope = "meta"
                    begin = "^#!/usr/bin/env"
                    end = """${'$'}"""
                    illegal = "\n"
                },
                numberMode,
            )
        },
    )
}

private const val TRIPLE_QUOTE = "\"\"\""

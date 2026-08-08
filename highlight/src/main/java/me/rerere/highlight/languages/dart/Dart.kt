package me.rerere.highlight.languages.dart

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.UNDERSCORE_TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/** Dart, ported from `lib/languages/dart.js` of `highlight.js` 11.11.1. */
internal fun dart(): Language {
    val subst = mode {
        scope = "subst"
        variants = listOf(
            { begin = """\$[A-Za-z0-9_]+""" },
        )
    }

    val bracedSubst = mode {
        scope = "subst"
        variants = listOf(
            {
                begin = """\$\{"""
                end = """\}"""
            },
        )
        keywords = keywords("true false null this is new super")
    }

    val number = mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            { match = """\b[0-9][0-9_]*(\.[0-9][0-9_]*)?([eE][+-]?[0-9][0-9_]*)?\b""" },
            { match = """\b0[xX][0-9A-Fa-f][0-9A-Fa-f_]*\b""" },
        )
    }

    val string = mode {
        scope = "string"
        variants = listOf(
            {
                begin = "r'''"
                end = "'''"
            },
            {
                begin = "r\"\"\""
                end = "\"\"\""
            },
            {
                begin = "r'"
                end = "'"
                illegal = """\n"""
            },
            {
                begin = "r\""
                end = "\""
                illegal = """\n"""
            },
            {
                begin = "'''"
                end = "'''"
                contains = listOf(BACKSLASH_ESCAPE, subst, bracedSubst)
            },
            {
                begin = "\"\"\""
                end = "\"\"\""
                contains = listOf(BACKSLASH_ESCAPE, subst, bracedSubst)
            },
            {
                begin = "'"
                end = "'"
                illegal = """\n"""
                contains = listOf(BACKSLASH_ESCAPE, subst, bracedSubst)
            },
            {
                begin = "\""
                end = "\""
                illegal = """\n"""
                contains = listOf(BACKSLASH_ESCAPE, subst, bracedSubst)
            },
        )
    }
    bracedSubst.contains = listOf(number, string)

    val builtInTypes = listOf(
        // dart:core
        "Comparable", "DateTime", "Duration", "Function", "Iterable", "Iterator", "List", "Map",
        "Match", "Object", "Pattern", "RegExp", "Set", "Stopwatch", "String", "StringBuffer",
        "StringSink", "Symbol", "Type", "Uri", "bool", "double", "int", "num",
        // dart:html
        "Element", "ElementList",
    )
    val nullableBuiltInTypes = builtInTypes.map { "$it?" }
    val basicKeywords = listOf(
        "abstract", "as", "assert", "async", "await", "base", "break", "case", "catch", "class",
        "const", "continue", "covariant", "default", "deferred", "do", "dynamic", "else", "enum",
        "export", "extends", "extension", "external", "factory", "false", "final", "finally", "for",
        "Function", "get", "hide", "if", "implements", "import", "in", "interface", "is", "late",
        "library", "mixin", "new", "null", "on", "operator", "part", "required", "rethrow",
        "return", "sealed", "set", "show", "static", "super", "switch", "sync", "this", "throw",
        "true", "try", "typedef", "var", "void", "when", "while", "with", "yield",
    )
    val dartKeywords = keywords {
        pattern = """[A-Za-z][A-Za-z0-9_]*\??"""
        keyword(basicKeywords)
        builtIn(
            builtInTypes + nullableBuiltInTypes + listOf(
                // dart:core
                "Never", "Null", "dynamic", "print",
                // dart:html
                "document", "querySelector", "querySelectorAll", "window",
            ),
        )
    }

    return Language(
        name = "Dart",
        aliases = setOf("dart"),
        root = mode {
            keywords = dartKeywords
            contains = listOf(
                string,
                comment("""/\*\*(?!/)""", """\*/""") {
                    subLanguage = "markdown"
                    relevance = 0.0
                },
                comment("""/{3,} ?""", "$") {
                    contains = listOf(
                        mode {
                            subLanguage = "markdown"
                            begin = "."
                            end = "$"
                            relevance = 0.0
                        },
                    )
                },
                C_LINE_COMMENT_MODE,
                C_BLOCK_COMMENT_MODE,
                mode {
                    scope = "class"
                    beginKeywords = "class interface"
                    end = """\{"""
                    excludeEnd = true
                    contains = listOf(
                        mode { beginKeywords = "extends implements" },
                        UNDERSCORE_TITLE_MODE,
                    )
                },
                number,
                mode {
                    scope = "meta"
                    begin = "@[A-Za-z]+"
                },
                // Relevance booster.
                mode { begin = "=>" },
            )
        },
    )
}

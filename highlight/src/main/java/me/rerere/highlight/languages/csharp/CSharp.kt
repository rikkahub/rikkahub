package me.rerere.highlight.languages.csharp

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.IDENT_RE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.variant

/** C#, ported from `lib/languages/csharp.js` of `highlight.js` 11.11.1. */
internal fun csharp(): Language {
    val builtInKeywords = listOf(
        "bool", "byte", "char", "decimal", "delegate", "double", "dynamic", "enum", "float", "int",
        "long", "nint", "nuint", "object", "sbyte", "short", "string", "ulong", "uint", "ushort",
    )
    val functionModifiers = listOf(
        "public", "private", "protected", "static", "internal", "protected", "abstract", "async",
        "extern", "override", "unsafe", "virtual", "new", "sealed", "partial",
    )
    val literalKeywords = listOf("default", "false", "null", "true")
    val normalKeywords = listOf(
        "abstract", "as", "base", "break", "case", "catch", "class", "const", "continue", "do",
        "else", "event", "explicit", "extern", "finally", "fixed", "for", "foreach", "goto", "if",
        "implicit", "in", "interface", "internal", "is", "lock", "namespace", "new", "operator",
        "out", "override", "params", "private", "protected", "public", "readonly", "record", "ref",
        "return", "scoped", "sealed", "sizeof", "stackalloc", "static", "struct", "switch", "this",
        "throw", "try", "typeof", "unchecked", "unsafe", "using", "virtual", "void", "volatile",
        "while",
    )
    val contextualKeywords = listOf(
        "add", "alias", "and", "ascending", "args", "async", "await", "by", "descending", "dynamic",
        "equals", "file", "from", "get", "global", "group", "init", "into", "join", "let",
        "nameof", "not", "notnull", "on", "or", "orderby", "partial", "record", "remove",
        "required", "scoped", "select", "set", "unmanaged", "value|0", "var", "when", "where",
        "with", "yield",
    )
    val csharpKeywords = keywords {
        keyword(normalKeywords + contextualKeywords)
        builtIn(builtInKeywords)
        literal(literalKeywords)
    }

    val titleMode = TITLE_MODE.inherit { begin = """[a-zA-Z](\.?\w)*""" }
    val numbers = mode {
        scope = "number"
        variants = listOf(
            { begin = """\b(0b[01']+)""" },
            {
                begin = """(-?)\b([\d']+(\.[\d']*)?|\.[\d']+)(u|U|l|L|ul|UL|f|F|b|B)"""
            },
            {
                begin =
                    """(-?)(\b0[xX][a-fA-F0-9']+|(\b[\d']+(\.[\d']*)?|\.[\d']+)([eE][-+]?[\d']+)?)"""
            },
        )
        relevance = 0.0
    }
    val rawString = mode {
        scope = "string"
        begin = "\"\"\"(\"*)(?!\")(.|\\n)*?\"\"\"\\1"
        relevance = 1.0
    }
    val verbatimString = mode {
        scope = "string"
        begin = "@\""
        end = "\""
        contains = listOf(mode { begin = "\"\"" })
    }
    val verbatimStringNoLf = verbatimString.inherit { illegal = """\n""" }
    val subst = mode {
        scope = "subst"
        begin = """\{"""
        end = """\}"""
        keywords = csharpKeywords
    }
    val substNoLf = subst.inherit { illegal = """\n""" }
    val interpolatedString = mode {
        scope = "string"
        begin = """\$""" + "\""
        end = "\""
        illegal = """\n"""
        contains = listOf(
            mode { begin = """\{\{""" },
            mode { begin = """\}\}""" },
            BACKSLASH_ESCAPE,
            substNoLf,
        )
    }
    val interpolatedVerbatimString = mode {
        scope = "string"
        begin = """\$@""" + "\""
        end = "\""
        contains = listOf(
            mode { begin = """\{\{""" },
            mode { begin = """\}\}""" },
            mode { begin = "\"\"" },
            subst,
        )
    }
    val interpolatedVerbatimStringNoLf = interpolatedVerbatimString.inherit {
        illegal = """\n"""
        contains = listOf(
            mode { begin = """\{\{""" },
            mode { begin = """\}\}""" },
            mode { begin = "\"\"" },
            substNoLf,
        )
    }
    subst.contains = listOf(
        interpolatedVerbatimString,
        interpolatedString,
        verbatimString,
        APOS_STRING_MODE,
        QUOTE_STRING_MODE,
        numbers,
        C_BLOCK_COMMENT_MODE,
    )
    substNoLf.contains = listOf(
        interpolatedVerbatimStringNoLf,
        interpolatedString,
        verbatimStringNoLf,
        APOS_STRING_MODE,
        QUOTE_STRING_MODE,
        numbers,
        C_BLOCK_COMMENT_MODE.inherit { illegal = """\n""" },
    )
    val string = mode {
        variants = listOf(
            variant(rawString),
            variant(interpolatedVerbatimString),
            variant(interpolatedString),
            variant(verbatimString),
            variant(APOS_STRING_MODE),
            variant(QUOTE_STRING_MODE),
        )
    }

    val genericModifier = mode {
        begin = "<"
        end = ">"
        contains = listOf(
            mode { beginKeywords = "in out" },
            titleMode,
        )
    }
    val typeIdentRe =
        IDENT_RE + "(<" + IDENT_RE + """(\s*,\s*""" + IDENT_RE + ")" + "*>)?" + """(\[\])?"""
    val atIdentifier = mode {
        begin = "@$IDENT_RE"
        relevance = 0.0
    }

    return Language(
        name = "C#",
        aliases = setOf("csharp", "cs", "c#"),
        root = mode {
            keywords = csharpKeywords
            illegal = "::"
            contains = listOf(
                comment("///", "$") {
                    returnBegin = true
                    contains = listOf(
                        mode {
                            scope = "doctag"
                            variants = listOf(
                                {
                                    begin = "///"
                                    relevance = 0.0
                                },
                                { begin = "<!--|-->" },
                                {
                                    begin = "</?"
                                    end = ">"
                                },
                            )
                        },
                    )
                },
                C_LINE_COMMENT_MODE,
                C_BLOCK_COMMENT_MODE,
                mode {
                    scope = "meta"
                    begin = "#"
                    end = "$"
                    keywords = keywords(
                        "if else elif endif define undef warning error line region endregion " +
                            "pragma checksum",
                    )
                },
                string,
                numbers,
                mode {
                    beginKeywords = "class interface"
                    relevance = 0.0
                    end = """[\{;=]"""
                    illegal = """[^\s:,]"""
                    contains = listOf(
                        mode { beginKeywords = "where class" },
                        titleMode,
                        genericModifier,
                        C_LINE_COMMENT_MODE,
                        C_BLOCK_COMMENT_MODE,
                    )
                },
                mode {
                    beginKeywords = "namespace"
                    relevance = 0.0
                    end = """[\{;=]"""
                    illegal = """[^\s:]"""
                    contains = listOf(
                        titleMode,
                        C_LINE_COMMENT_MODE,
                        C_BLOCK_COMMENT_MODE,
                    )
                },
                mode {
                    beginKeywords = "record"
                    relevance = 0.0
                    end = """[\{;=]"""
                    illegal = """[^\s:]"""
                    contains = listOf(
                        titleMode,
                        genericModifier,
                        C_LINE_COMMENT_MODE,
                        C_BLOCK_COMMENT_MODE,
                    )
                },
                mode {
                    scope = "meta"
                    begin = """^\s*\[(?=[\w])"""
                    excludeBegin = true
                    end = """\]"""
                    excludeEnd = true
                    contains = listOf(
                        mode {
                            scope = "string"
                            begin = "\""
                            end = "\""
                        },
                    )
                },
                mode {
                    beginKeywords = "new return throw await else"
                    relevance = 0.0
                },
                mode {
                    scope = "function"
                    begin = "($typeIdentRe\\s+)+$IDENT_RE\\s*(<[^=]+>\\s*)?\\("
                    returnBegin = true
                    end = """\s*[\{;=]"""
                    excludeEnd = true
                    keywords = csharpKeywords
                    contains = listOf(
                        mode {
                            beginKeywords = functionModifiers.joinToString(" ")
                            relevance = 0.0
                        },
                        mode {
                            begin = "$IDENT_RE\\s*(<[^=]+>\\s*)?\\("
                            returnBegin = true
                            contains = listOf(TITLE_MODE, genericModifier)
                            relevance = 0.0
                        },
                        mode { match = """\(\)""" },
                        mode {
                            scope = "params"
                            begin = """\("""
                            end = """\)"""
                            excludeBegin = true
                            excludeEnd = true
                            keywords = csharpKeywords
                            relevance = 0.0
                            contains = listOf(string, numbers, C_BLOCK_COMMENT_MODE)
                        },
                        C_LINE_COMMENT_MODE,
                        C_BLOCK_COMMENT_MODE,
                    )
                },
                atIdentifier,
            )
        },
    )
}

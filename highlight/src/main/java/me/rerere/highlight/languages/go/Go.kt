package me.rerere.highlight.languages.go

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.variant

/** Go, ported from `lib/languages/go.js` of `highlight.js` 11.11.1. */
internal fun go(): Language {
    val literals = listOf("true", "false", "iota", "nil")
    val builtIns = listOf(
        "append", "cap", "close", "complex", "copy", "imag", "len", "make", "new", "panic",
        "print", "println", "real", "recover", "delete",
    )
    val types = listOf(
        "bool", "byte", "complex64", "complex128", "error", "float32", "float64", "int8", "int16",
        "int32", "int64", "string", "uint8", "uint16", "uint32", "uint64", "int", "uint",
        "uintptr", "rune",
    )
    val kws = listOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough",
        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range",
        "return", "select", "struct", "switch", "type", "var",
    )
    val goKeywords = keywords {
        keyword(kws)
        type(types)
        literal(literals)
        builtIn(builtIns)
    }

    return Language(
        name = "Go",
        aliases = setOf("go", "golang"),
        root = mode {
            keywords = goKeywords
            illegal = "</"
            contains = listOf(
                C_LINE_COMMENT_MODE,
                C_BLOCK_COMMENT_MODE,
                mode {
                    scope = "string"
                    variants = listOf(
                        variant(QUOTE_STRING_MODE),
                        variant(APOS_STRING_MODE),
                        { begin = "`"; end = "`" },
                    )
                },
                mode {
                    scope = "number"
                    variants = listOf(
                        {
                            // Hex without a digit before the `.`, which makes one after it required.
                            match = """-?\b0[xX]\.[a-fA-F0-9](_?[a-fA-F0-9])*[pP][+-]?\d(_?\d)*i?"""
                            relevance = 0.0
                        },
                        {
                            // Hex with a digit before the `.`, which makes one after it optional.
                            match = """-?\b0[xX](_?[a-fA-F0-9])+""" +
                                """((\.([a-fA-F0-9](_?[a-fA-F0-9])*)?)?[pP][+-]?\d(_?\d)*)?i?"""
                            relevance = 0.0
                        },
                        {
                            // Leading `0o` octal.
                            match = """-?\b0[oO](_?[0-7])*i?"""
                            relevance = 0.0
                        },
                        {
                            // Decimal without a digit before the `.`.
                            match = """-?\.\d(_?\d)*([eE][+-]?\d(_?\d)*)?i?"""
                            relevance = 0.0
                        },
                        {
                            // Decimal with a digit before the `.`.
                            match = """-?\b\d(_?\d)*(\.(\d(_?\d)*)?)?([eE][+-]?\d(_?\d)*)?i?"""
                            relevance = 0.0
                        },
                    )
                },
                // Relevance booster.
                mode { begin = ":=" },
                mode {
                    scope = "function"
                    beginKeywords = "func"
                    end = """\s*(\{|$)"""
                    excludeEnd = true
                    contains = listOf(
                        TITLE_MODE,
                        mode {
                            scope = "params"
                            begin = """\("""
                            end = """\)"""
                            endsParent = true
                            keywords = goKeywords
                            illegal = """["']"""
                        },
                    )
                },
            )
        },
    )
}

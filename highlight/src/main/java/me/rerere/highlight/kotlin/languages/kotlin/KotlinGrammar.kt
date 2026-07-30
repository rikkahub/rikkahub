package me.rerere.highlight.kotlin.languages.kotlin

internal object KotlinGrammar {
    val keywords = setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch", "class",
        "companion", "const", "constructor", "continue", "crossinline", "data", "delegate",
        "do", "dynamic", "else", "enum", "expect", "external", "field", "file", "final",
        "finally", "for", "fun", "get", "if", "import", "in", "infix", "init", "inline",
        "inner", "interface", "internal", "is", "lateinit", "noinline", "object", "open",
        "operator", "out", "override", "package", "param", "private", "property", "protected",
        "public", "receiver", "reified", "return", "sealed", "set", "setparam", "super",
        "suspend", "tailrec", "this", "throw", "try", "typealias", "typeof", "val", "var",
        "vararg", "value", "when", "where", "while",
    )

    val builtInTypes = setOf(
        "Any", "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long", "Nothing",
        "Short", "String", "UByte", "UInt", "ULong", "UShort", "Unit",
    )

    val typeDeclarationKeywords = setOf(
        "as", "class", "interface", "is", "object", "typealias",
    )

    val numberPattern = Regex(
            """(?:0[xX][0-9A-Fa-f](?:_?[0-9A-Fa-f])*[uU]?[lL]?""" +
            """|0[bB][01](?:_?[01])*[uU]?[lL]?""" +
            """|(?:\d(?:_?\d)*)(?:\.\d(?:_?\d)*)?(?:[eE][+-]?\d(?:_?\d)*)?""" +
            """(?:[fF]|[uU]?[lL]?)?""" +
            """|\.\d(?:_?\d)*(?:[eE][+-]?\d(?:_?\d)*)?[fF]?)""" +
            """(?![A-Za-z0-9_$])""",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}

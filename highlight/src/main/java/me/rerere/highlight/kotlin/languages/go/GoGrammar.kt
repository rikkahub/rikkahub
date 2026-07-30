package me.rerere.highlight.kotlin.languages.go

internal object GoGrammar {
    val keywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else",
        "fallthrough", "for", "func", "go", "goto", "if", "import", "interface", "map",
        "package", "range", "return", "select", "struct", "switch", "type", "var",
    )

    val builtIns = setOf(
        "append", "cap", "clear", "close", "complex", "copy", "delete", "imag", "len",
        "make", "max", "min", "new", "panic", "print", "println", "real", "recover",
    )

    val builtInTypes = setOf(
        "any", "bool", "byte", "comparable", "complex64", "complex128", "error", "float32",
        "float64", "int", "int8", "int16", "int32", "int64", "rune", "string", "uint",
        "uint8", "uint16", "uint32", "uint64", "uintptr",
    )

    val numberPattern = Regex(
        """(?:0[xX]\.[0-9a-fA-F](?:_?[0-9a-fA-F])*""" +
            """[pP][+-]?[0-9](?:_?[0-9])*|""" +
            """0[xX](?:_?[0-9a-fA-F])+(?:\.(?:[0-9a-fA-F](?:_?[0-9a-fA-F])*)?)?""" +
            """(?:[pP][+-]?[0-9](?:_?[0-9])*)?|""" +
            """0[oO](?:_?[0-7])+|0[bB](?:_?[01])+|""" +
            """\.[0-9](?:_?[0-9])*(?:[eE][+-]?[0-9](?:_?[0-9])*)?|""" +
            """[0-9](?:_?[0-9])*(?:\.(?:[0-9](?:_?[0-9])*)?)?""" +
            """(?:[eE][+-]?[0-9](?:_?[0-9])*)?)[i]?""",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}

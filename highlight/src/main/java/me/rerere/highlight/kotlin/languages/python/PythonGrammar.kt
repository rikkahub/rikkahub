package me.rerere.highlight.kotlin.languages.python

internal object PythonGrammar {
    val keywords = setOf(
        "and", "as", "assert", "async", "await", "break", "case", "class", "continue",
        "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
        "import", "in", "is", "lambda", "lazy", "match", "nonlocal", "not", "or", "pass",
        "raise", "return", "try", "while", "with", "yield",
    )

    val builtIns = setOf(
        "__import__", "abs", "aiter", "all", "anext", "any", "ascii", "bin", "bool",
        "breakpoint", "bytearray", "bytes", "callable", "chr", "classmethod", "compile",
        "complex", "delattr", "dict", "dir", "divmod", "enumerate", "eval", "exec",
        "filter", "float", "format", "frozendict", "frozenset", "getattr", "globals",
        "hasattr", "hash", "help", "hex", "id", "input", "int", "isinstance",
        "issubclass", "iter", "len", "list", "locals", "map", "max", "memoryview", "min",
        "next", "object", "oct", "open", "ord", "pow", "print", "property", "range",
        "repr", "reversed", "round", "sentinel", "set", "setattr", "slice", "sorted",
        "staticmethod", "str", "sum", "super", "tuple", "type", "vars", "zip",
    )

    val builtInTypes = setOf(
        "Any", "Callable", "Coroutine", "Dict", "Generic", "List", "Literal", "Optional",
        "Sequence", "Set", "Tuple", "Type", "Union", "bool", "bytearray", "bytes",
        "complex", "dict", "float", "frozenset", "int", "list", "memoryview", "object",
        "range", "set", "slice", "str", "tuple", "type",
    )

    val constants = setOf(
        "__debug__", "Ellipsis", "None", "NotImplemented",
    )

    val numberPattern = Regex(
        """(?:0[xX](?:_?[0-9a-fA-F])+[lL]?|""" +
            """0[bB](?:_?[01])+[lL]?|""" +
            """0[oO](?:_?[0-7])+[lL]?|""" +
            """(?:[0-9](?:_?[0-9])*)?\.[0-9](?:_?[0-9])*""" +
            """(?:[eE][+-]?[0-9](?:_?[0-9])*)?[jJ]?|""" +
            """[0-9](?:_?[0-9])*\.(?:[0-9](?:_?[0-9])*)?""" +
            """(?:[eE][+-]?[0-9](?:_?[0-9])*)?[jJ]?|""" +
            """[0-9](?:_?[0-9])*[eE][+-]?[0-9](?:_?[0-9])*[jJ]?|""" +
            """[0-9](?:_?[0-9])*[lLjJ]?)(?![A-Za-z0-9_])""",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}

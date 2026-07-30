package me.rerere.highlight.kotlin.languages.javascript

internal object JavaScriptGrammar {
    const val NULL_CHAR = '\u0000'

    val FUNCTION_TYPE_END_CHARS = setOf('=', '{', ';', '\n', '\r')

    val OPERATORS = listOf(
        ">>>=", "===", "!==", "**=", "&&=", "||=", "??=", "<<=", ">>=", ">>>",
        "=>", "==", "!=", "<=", ">=", "++", "--", "**", "&&", "||", "??", "?.",
        "<<", ">>", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "...", "!",
        "~", "+", "-", "*", "/", "%", "&", "|", "^", "=", "<", ">", "?", ":",
    )

    val NUMBER_PATTERN = Regex(
        """(?:0[xX][0-9a-fA-F](?:_?[0-9a-fA-F])*n?""" +
            """|0[bB][01](?:_?[01])*n?""" +
            """|0[oO][0-7](?:_?[0-7])*n?""" +
            """|0[0-7]+n?""" +
            """|(?:\d(?:_?\d)*)n""" +
            """|(?:(?:\d(?:_?\d)*)(?:\.(?:\d(?:_?\d)*)?)?|\.\d(?:_?\d)*)""" +
            """(?:[eE][+-]?\d(?:_?\d)*)?)(?![A-Za-z0-9_$])""",
    )

    val UPPER_CASE_CONSTANT_PATTERN = Regex("[A-Z][A-Z_0-9]+")
    val CLASS_REFERENCE_PATTERN = Regex(
        """(?:[A-Z][a-z]+(?:[A-Z][a-z]*|\d)*""" +
            """|[A-Z]{2,}(?:[A-Z][a-z]+|\d)+(?:[A-Z][a-z]*)*""" +
            """|[A-Z]{2,}[a-z]+(?:[A-Z][a-z]+|\d)*(?:[A-Z][a-z]*)*)""",
    )

    val javaScriptKeywords = setOf(
        "as", "in", "of", "if", "for", "while", "finally", "var", "new", "function",
        "do", "return", "void", "else", "break", "catch", "instanceof", "with", "throw",
        "case", "default", "try", "switch", "continue", "typeof", "delete", "let", "yield",
        "const", "class", "debugger", "async", "await", "static", "import", "from", "export",
        "extends", "using", "get", "set",
    )

    private val typeScriptKeywords = setOf(
        "type", "interface", "public", "private", "protected", "implements", "declare",
        "abstract", "readonly", "enum", "override", "satisfies", "namespace", "keyof",
        "infer", "is", "asserts",
    )
    val allTypeScriptKeywords = javaScriptKeywords + typeScriptKeywords

    val expressionStarterKeywords = setOf(
        "return", "throw", "case", "delete", "typeof", "void", "new", "yield", "await",
        "in", "of", "instanceof",
    )

    val classDeclarationKeywords = setOf(
        "class", "extends", "interface", "implements", "enum", "namespace",
    )

    val booleanLiterals = setOf("true", "false")
    val constantLiterals = setOf("null", "undefined", "NaN", "Infinity")

    val typeScriptTypes = setOf(
        "any", "void", "number", "boolean", "string", "object", "never", "symbol", "bigint",
        "unknown",
    )

    val builtInTypes = setOf(
        "Object", "Function", "Boolean", "Symbol", "Math", "Date", "Number", "BigInt",
        "String", "RegExp", "Array", "Float32Array", "Float64Array", "Int8Array", "Uint8Array",
        "Uint8ClampedArray", "Int16Array", "Int32Array", "Uint16Array", "Uint32Array",
        "BigInt64Array", "BigUint64Array", "Set", "Map", "WeakSet", "WeakMap", "ArrayBuffer",
        "SharedArrayBuffer", "Atomics", "DataView", "JSON", "Promise", "Generator",
        "GeneratorFunction", "AsyncFunction", "Reflect", "Proxy", "Intl", "WebAssembly",
    )

    val errorTypes = setOf(
        "Error", "EvalError", "InternalError", "RangeError", "ReferenceError", "SyntaxError",
        "TypeError", "URIError",
    )

    val builtInGlobals = setOf(
        "setInterval", "setTimeout", "clearInterval", "clearTimeout", "require", "exports",
        "eval", "isFinite", "isNaN", "parseFloat", "parseInt", "decodeURI",
        "decodeURIComponent", "encodeURI", "encodeURIComponent", "escape", "unescape",
    )

    val builtInVariables = setOf(
        "arguments", "this", "super", "console", "window", "document", "localStorage",
        "sessionStorage", "module", "self", "global",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '$' || char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }

    fun isJsxNameStart(char: Char): Boolean = isIdentifierStart(char)

    fun isJsxNamePart(char: Char): Boolean {
        return isIdentifierPart(char) || char == '.' || char == ':' || char == '-'
    }

    fun isJsxAttributeStart(char: Char): Boolean {
        return isIdentifierStart(char)
    }

    fun isJsxAttributePart(char: Char): Boolean {
        return isIdentifierPart(char) || char == ':' || char == '-'
    }
}

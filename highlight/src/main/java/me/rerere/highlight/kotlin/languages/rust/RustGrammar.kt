package me.rerere.highlight.kotlin.languages.rust

internal object RustGrammar {
    val keywords = setOf(
        "abstract", "as", "async", "await", "become", "box", "break", "const", "continue",
        "crate", "do", "dyn", "else", "enum", "extern", "final", "fn", "for", "if", "impl",
        "in", "let", "loop", "macro", "match", "mod", "move", "mut", "override", "priv",
        "pub", "raw", "ref", "return", "safe", "self", "Self", "static", "struct", "super",
        "trait", "try", "type", "typeof", "union", "unsafe", "unsized", "use", "virtual",
        "where", "while", "yield",
    )

    val builtInTypes = setOf(
        "bool", "char", "f16", "f32", "f64", "f128", "i8", "i16", "i32", "i64", "i128",
        "isize", "str", "u8", "u16", "u32", "u64", "u128", "usize", "Box", "Option",
        "Result", "String", "Vec",
    )

    val builtInTraits = setOf(
        "AsMut", "AsRef", "Clone", "Copy", "Debug", "Default", "DoubleEndedIterator", "Drop",
        "Eq", "ExactSizeIterator", "Extend", "Fn", "FnMut", "FnOnce", "From", "Into",
        "IntoIterator", "Iterator", "Ord", "PartialEq", "PartialOrd", "Send", "Sized", "Sync",
        "ToOwned", "ToString",
    )

    val constants = setOf("Some", "None", "Ok", "Err")

    val builtInFunctions = setOf("drop", "size_of", "size_of_val")

    val typeDeclarationKeywords = setOf(
        "enum", "impl", "mod", "struct", "trait", "type", "union",
    )

    val numberPattern = Regex(
        """(?:0[bB][01_]+|0[oO][0-7_]+|0[xX][0-9A-Fa-f_]+|""" +
            """[0-9][0-9_]*(?:\.(?!\.)[0-9_]*)?(?:[eE][+-]?[0-9_]+)?)""" +
            """(?:[iu](?:8|16|32|64|128|size)|f(?:16|32|64|128))?""",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}

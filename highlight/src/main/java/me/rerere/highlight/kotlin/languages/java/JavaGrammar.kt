package me.rerere.highlight.kotlin.languages.java

internal object JavaGrammar {
    val keywords = setOf(
        "abstract", "assert", "break", "case", "catch", "class", "const", "continue",
        "default", "do", "else", "enum", "exports", "extends", "final", "finally", "for",
        "goto", "if", "implements", "import", "instanceof", "interface", "module", "native",
        "new", "non-sealed", "open", "opens", "package", "permits", "private", "protected",
        "provides", "public", "record", "requires", "return", "sealed", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "to", "transient",
        "transitive", "try", "uses", "var", "void", "volatile", "when", "while", "with",
        "yield",
    )

    val primitiveTypes = setOf(
        "boolean", "byte", "char", "double", "float", "int", "long", "short", "void",
    )

    val typeDeclarationKeywords = setOf(
        "class", "enum", "extends", "implements", "interface", "new", "permits", "record",
        "throws",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '$' || char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}

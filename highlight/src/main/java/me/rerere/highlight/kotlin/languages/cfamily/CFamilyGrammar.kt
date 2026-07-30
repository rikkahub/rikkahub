package me.rerere.highlight.kotlin.languages.cfamily

internal object CFamilyGrammar {
    val numberPattern = Regex(
        """(?:0[xX](?:[0-9A-Fa-f](?:'?[0-9A-Fa-f])*(?:\.(?:[0-9A-Fa-f]""" +
            """(?:'?[0-9A-Fa-f])*)?)?|\.[0-9A-Fa-f](?:'?[0-9A-Fa-f])*)""" +
            """[pP][+-]?[0-9](?:'?[0-9])*(?:[fFlL]|[fF](?:16|32|64|128))?|""" +
            """(?:[0-9](?:'?[0-9])*\.(?:[0-9](?:'?[0-9])*)?|""" +
            """\.[0-9](?:'?[0-9])*)(?:[eE][+-]?[0-9](?:'?[0-9])*)?""" +
            """(?:[fFlL]|[fF](?:16|32|64|128))?|""" +
            """[0-9](?:'?[0-9])*[eE][+-]?[0-9](?:'?[0-9])*""" +
            """(?:[fFlL]|[fF](?:16|32|64|128))?|""" +
            """0[bB][01](?:'?[01])*(?:[uU](?:ll?|LL?)?|(?:ll?|LL?)[uU]?)?|""" +
            """0[xX][0-9A-Fa-f](?:'?[0-9A-Fa-f])*""" +
            """(?:[uU](?:ll?|LL?)?|(?:ll?|LL?)[uU]?)?|""" +
            """(?:0(?:'?[0-7])*|[1-9](?:'?[0-9])*)""" +
            """(?:[uU](?:ll?|LL?)?|(?:ll?|LL?)[uU]?|[zZ][uU]?)?)""",
    )

    fun isIdentifierStart(char: Char): Boolean {
        return char == '_' || char.isLetter()
    }

    fun isIdentifierPart(char: Char): Boolean {
        return isIdentifierStart(char) || char.isDigit()
    }
}

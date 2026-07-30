package me.rerere.highlight.kotlin.languages.jvm

internal object JvmGrammar {
    val numberPattern = Regex(
        """(?:0[xX](?:[0-9A-Fa-f](?:_?[0-9A-Fa-f])*)(?:\.(?:[0-9A-Fa-f](?:_?[0-9A-Fa-f])*))?""" +
            """(?:[pP][+-]?\d(?:_?\d)*)?[fFdDlL]?""" +
            """|0[bB][01](?:_?[01])*[lL]?""" +
            """|(?:\d(?:_?\d)*)(?:\.(?:\d(?:_?\d)*)?)?(?:[eE][+-]?\d(?:_?\d)*)?[fFdDlL]?""" +
            """|\.\d(?:_?\d)*(?:[eE][+-]?\d(?:_?\d)*)?[fFdD]?)""" +
            """(?![A-Za-z0-9_$])""",
    )
}

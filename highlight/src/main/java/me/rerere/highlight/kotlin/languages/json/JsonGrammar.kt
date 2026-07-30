package me.rerere.highlight.kotlin.languages.json

internal object JsonGrammar {
    val PROPERTY_PATTERN = Regex(
        """(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*')(?=\s*:)""",
    )

    val NUMBER_PATTERN = Regex(
        """[+-]?(?:0[xX][0-9a-fA-F]+""" +
            """|0[bB][01]+""" +
            """|0[oO][0-7]+""" +
            """|(?:(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?))""" +
            """(?![A-Za-z0-9_$])""",
    )
}

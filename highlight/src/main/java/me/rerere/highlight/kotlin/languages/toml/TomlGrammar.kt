package me.rerere.highlight.kotlin.languages.toml

internal object TomlGrammar {
    val DATE_TIME_PATTERN = Regex(
        """(?:\d{4}-\d{2}-\d{2}(?:[Tt ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:[Zz]|[+-]\d{2}:\d{2})?)?""" +
            """|\d{2}:\d{2}:\d{2}(?:\.\d+)?)(?![A-Za-z0-9_])""",
    )

    val NUMBER_PATTERN = Regex(
        """[+-]?(?:0[xX][0-9A-Fa-f](?:_?[0-9A-Fa-f])*""" +
            """|0[oO][0-7](?:_?[0-7])*""" +
            """|0[bB][01](?:_?[01])*""" +
            """|(?:\d(?:_?\d)*)(?:\.(?:\d(?:_?\d)*))?(?:[eE][+-]?\d(?:_?\d)*)?""" +
            """|inf|nan)(?![A-Za-z0-9_])""",
        RegexOption.IGNORE_CASE,
    )
}

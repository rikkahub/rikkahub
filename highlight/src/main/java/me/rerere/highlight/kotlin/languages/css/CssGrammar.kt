package me.rerere.highlight.kotlin.languages.css

internal object CssGrammar {
    val globalValues = setOf(
        "inherit", "initial", "revert", "revert-layer", "unset",
    )

    val atRuleModifiers = setOf(
        "and", "not", "only", "or",
    )

    val identifierPattern = Regex("""-?[A-Za-z_][A-Za-z0-9_-]*""")
    val propertyPattern = Regex("""--?[A-Za-z_][A-Za-z0-9_-]*|[A-Za-z_][A-Za-z0-9_-]*""")
    val numberPattern = Regex(
        """[+-]?(?:(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?)""" +
            """(?:%|[A-Za-z]+)?(?![A-Za-z0-9_-])""",
    )
}

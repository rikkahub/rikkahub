package me.rerere.highlight.kotlin.languages.yaml

internal object YamlGrammar {
    val timestampPattern = Regex(
        """\b[0-9]{4}(?:-[0-9]{2}){0,2}""" +
            """(?:[Tt \t][0-9]{1,2}(?::[0-9]{2}){2})?""" +
            """(?:\.[0-9]*)?(?:[ \t]*(?:Z|[-+][0-9]{1,2}(?::[0-9]{2})?))?\b""",
    )

    val numberPattern = Regex(
        """[-+]?(?:""" +
            """0[xX][0-9A-Fa-f]+|""" +
            """0[oO][0-7]+|""" +
            """0[bB][01]+|""" +
            """(?:\d[\d_]*)(?:\.[\d_]*)?(?:[eE][-+]?\d+)?""" +
            """)\b""",
    )
}

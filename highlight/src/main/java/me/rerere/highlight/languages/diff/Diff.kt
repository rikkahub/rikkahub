package me.rerere.highlight.languages.diff

import me.rerere.highlight.core.Language
import me.rerere.highlight.core.either
import me.rerere.highlight.core.mode

/** Diff, ported from `lib/languages/diff.js` of `highlight.js` 11.11.1. */
internal fun diff(): Language = Language(
    name = "Diff",
    aliases = setOf("diff", "patch"),
    root = mode {
        contains = listOf(
            // The hunk headers of the unified and the context format.
            mode {
                scope = "meta"
                relevance = 10.0
                match = either(
                    """^@@ +-\d+,\d+ +\+\d+,\d+ +@@""",
                    """^\*\*\* +\d+,\d+ +\*\*\*\*${'$'}""",
                    """^--- +\d+,\d+ +----${'$'}""",
                )
            },
            // Everything framing the hunks: file headers, index lines and separators.
            mode {
                scope = "comment"
                variants = listOf(
                    {
                        begin = either(
                            "Index: ",
                            """^index""",
                            """={3,}""",
                            """^-{3}""",
                            """^\*{3} """,
                            """^\+{3}""",
                            """^diff --git""",
                        )
                        end = "${'$'}"
                    },
                    { match = """^\*{15}${'$'}""" },
                )
            },
            mode {
                scope = "addition"
                begin = """^\+"""
                end = "${'$'}"
            },
            mode {
                scope = "deletion"
                begin = """^-"""
                end = "${'$'}"
            },
            // The context format marks a changed — not merely added — line with `!`.
            mode {
                scope = "addition"
                begin = """^!"""
                end = "${'$'}"
            },
        )
    },
)

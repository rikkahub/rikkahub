package me.rerere.highlight.languages.css

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode

/** CSS, ported from `lib/languages/css.js` of `highlight.js` 11.11.1. */
internal fun css(): Language {
    val modes = cssModes()
    val vendorPrefix = mode { begin = """-(webkit|moz|ms|o)-(?=[a-z])""" }
    val atModifiers = "and or not only"
    val atPropertyRe = """@-?\w[\w]*(-\w+)*""" // `@-webkit-keyframes`
    val identRe = """[a-zA-Z-][a-zA-Z0-9_-]*"""
    val strings = listOf(APOS_STRING_MODE, QUOTE_STRING_MODE)

    return Language(
        name = "CSS",
        aliases = setOf("css"),
        caseInsensitive = true,
        classNameAliases = mapOf(
            // For visual continuity with `tag {}`, and because there is no better scope for it.
            "keyframePosition" to "selector-tag",
        ),
        root = mode {
            illegal = """[=|'\$]"""
            keywords = keywords { scope("keyframePosition", "from to") }
            contains = listOf(
                modes.blockComment,
                vendorPrefix,
                // Recognises keyframe positions such as `40%`, which sit outside the attribute
                // value mode.
                modes.number,
                mode {
                    scope = "selector-id"
                    begin = """#[A-Za-z0-9_-]+"""
                    relevance = 0.0
                },
                mode {
                    scope = "selector-class"
                    begin = """\.""" + identRe
                    relevance = 0.0
                },
                modes.attributeSelector,
                mode {
                    scope = "selector-pseudo"
                    variants = listOf(
                        { begin = ":(" + PSEUDO_CLASSES.joinToString("|") + ")" },
                        { begin = ":(:)?(" + PSEUDO_ELEMENTS.joinToString("|") + ")" },
                    )
                },
                modes.variable,
                mode {
                    scope = "attribute"
                    begin = """\b(""" + ATTRIBUTES.joinToString("|") + """)\b"""
                },
                // Attribute values.
                mode {
                    begin = ":"
                    end = """[;}{]"""
                    contains = listOf(
                        modes.blockComment,
                        modes.hexColor,
                        modes.important,
                        modes.number,
                    ) + strings + listOf(
                        // Needed both to highlight these as strings and to keep the characters
                        // inside a url — which would trip the language's `illegal` — out of it.
                        mode {
                            begin = """(url|data-uri)\("""
                            end = """\)"""
                            relevance = 0.0 // From the keywords.
                            keywords = keywords { builtIn("url data-uri") }
                            contains = strings + listOf(
                                mode {
                                    scope = "string"
                                    // Any character other than `)`, as in `url()`, starts a string
                                    // that ends with `)` — the end of the parent mode.
                                    begin = """[^)]"""
                                    endsWithParent = true
                                    excludeEnd = true
                                },
                            )
                        },
                        modes.functionDispatch,
                    )
                },
                mode {
                    begin = lookahead("@")
                    end = """[{;]"""
                    relevance = 0.0
                    illegal = ":" // Break on Less variables, `@var: ...`.
                    contains = listOf(
                        mode {
                            scope = "keyword"
                            begin = atPropertyRe
                        },
                        mode {
                            begin = """\s"""
                            endsWithParent = true
                            excludeEnd = true
                            relevance = 0.0
                            keywords = keywords {
                                pattern = """[a-z-]+"""
                                keyword(atModifiers)
                                scope("attribute", MEDIA_FEATURES)
                            }
                            contains = listOf(
                                mode {
                                    begin = """[a-z-]+(?=:)"""
                                    scope = "attribute"
                                },
                            ) + strings + listOf(modes.number)
                        },
                    )
                },
                mode {
                    scope = "selector-tag"
                    begin = """\b(""" + TAGS.joinToString("|") + """)\b"""
                },
            )
        },
    )
}

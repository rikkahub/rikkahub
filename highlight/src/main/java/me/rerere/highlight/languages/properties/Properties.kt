package me.rerere.highlight.languages.properties

import me.rerere.highlight.core.Language
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.mode

/**
 * `.properties`, ported from `lib/languages/properties.js` of `highlight.js` 11.11.1.
 *
 * Upstream marks the grammar `disableAutodetect`. Nothing here needs to honour that: detection only
 * runs for a `subLanguage` list, and no bundled grammar names this one.
 */
internal fun properties(): Language {
    // Whitespace: space, tab, formfeed.
    val ws0 = """[ \t\f]*"""
    val ws1 = """[ \t\f]+"""
    // Delimiter.
    val equalDelim = ws0 + "[:=]" + ws0
    val wsDelim = ws1
    val delim = "($equalDelim|$wsDelim)"
    val key = """([^\\:= \t\f\n]|\\.)+"""

    val delimAndValue = mode {
        // Skip the delimiter.
        end = delim
        relevance = 0.0
        starts = mode {
            // Value: everything until end of line (again, taking backslashes into account).
            scope = "string"
            end = "$"
            relevance = 0.0
            contains = listOf(
                mode { begin = """\\\\""" },
                // A backslash followed by a real newline: the line continuation.
                mode { begin = "\\\\\n" },
            )
        }
    }

    return Language(
        name = ".properties",
        aliases = setOf("properties"),
        caseInsensitive = true,
        root = mode {
            illegal = """\S"""
            contains = listOf(
                comment("""^\s*[!#]""", "$"),
                // Key: everything until whitespace or `=` or `:` (taking backslashes into
                // account), for a key-value pair.
                mode {
                    returnBegin = true
                    variants = listOf(
                        { begin = key + equalDelim },
                        { begin = key + wsDelim },
                    )
                    contains = listOf(
                        mode {
                            scope = "attr"
                            begin = key
                            endsParent = true
                        },
                    )
                    starts = delimAndValue
                },
                // A key with an empty value.
                mode {
                    scope = "attr"
                    begin = key + ws0 + "$"
                },
            )
        },
    )
}

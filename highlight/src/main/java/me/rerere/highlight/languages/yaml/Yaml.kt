package me.rerere.highlight.languages.yaml

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_NUMBER_RE
import me.rerere.highlight.core.HASH_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/** YAML, ported from `lib/languages/yaml.js` of `highlight.js` 11.11.1. */
internal fun yaml(): Language {
    val literals = "true false yes no null"

    // The YAML spec allows non-reserved URI characters in tags.
    val uriCharacters = """[\w#;/?:@&=+$,.~*'()[\]]+"""

    // Keys start with a word character, may contain word characters, spaces, colons, slashes,
    // hyphens and periods, and end with a colon followed by a space, tab or newline. The spec
    // allows far more than this, but this covers most real documents.
    val key = mode {
        scope = "attr"
        variants = listOf(
            { begin = """[\w*@][\w*@ :()\./-]*:(?=[ \t]|$)""" },
            // Double quoted keys.
            { begin = """"[\w*@][\w*@ :()\./-]*":(?=[ \t]|$)""" },
            // Single quoted keys.
            { begin = """'[\w*@][\w*@ :()\./-]*':(?=[ \t]|$)""" },
        )
    }

    val templateVariables = mode {
        scope = "template-variable"
        variants = listOf(
            // Jinja templates, Ansible.
            { begin = """\{\{"""; end = """\}\}""" },
            // Ruby i18n.
            { begin = """%\{"""; end = """\}""" },
        )
    }

    val singleQuoteString = mode {
        scope = "string"
        relevance = 0.0
        begin = "'"
        end = "'"
        contains = listOf(
            mode {
                match = "''"
                scope = "char.escape"
                relevance = 0.0
            },
        )
    }

    val string = mode {
        scope = "string"
        relevance = 0.0
        variants = listOf(
            { begin = "\""; end = "\"" },
            { begin = """\S+""" },
        )
        contains = listOf(BACKSLASH_ESCAPE, templateVariables)
    }

    // Strings inside value containers cannot contain braces, brackets or commas.
    val containerString = string.inherit {
        variants = listOf(
            {
                begin = "'"
                end = "'"
                contains = listOf(mode { begin = "''"; relevance = 0.0 })
            },
            { begin = "\""; end = "\"" },
            { begin = """[^\s,{}[\]]+""" },
        )
    }

    val dateRe = """[0-9]{4}(-[0-9][0-9]){0,2}"""
    val timeRe = """([Tt \t][0-9][0-9]?(:[0-9][0-9]){2})?"""
    val fractionRe = """(\.[0-9]*)?"""
    val zoneRe = """([ \t])*(Z|[-+][0-9][0-9]?(:[0-9][0-9])?)?"""
    val timestamp = mode {
        scope = "number"
        begin = """\b""" + dateRe + timeRe + fractionRe + zoneRe + """\b"""
    }

    val valueContainer = mode {
        end = ","
        endsWithParent = true
        excludeEnd = true
        keywords = keywords(literals)
        relevance = 0.0
    }
    val objectMode = mode {
        begin = """\{"""
        end = """\}"""
        contains = listOf(valueContainer)
        illegal = """\n"""
        relevance = 0.0
    }
    val arrayMode = mode {
        begin = """\["""
        end = """\]"""
        contains = listOf(valueContainer)
        illegal = """\n"""
        relevance = 0.0
    }

    val modes = listOf(
        key,
        mode {
            scope = "meta"
            begin = """^---\s*$"""
            relevance = 10.0
        },
        // Multi line strings: a block starts with `|` or `>` followed by a newline, and every
        // following line has to keep the same indentation to stay part of the block.
        mode {
            scope = "string"
            begin = """[\|>]([1-9]?[+-])?[ ]*\n( +)[^ ][^\n]*\n(\2[^\n]+\n?)*"""
        },
        // Ruby on Rails ERB.
        mode {
            begin = "<%[%=-]?"
            end = "[%-]?%>"
            subLanguage = "ruby"
            excludeBegin = true
            excludeEnd = true
            relevance = 0.0
        },
        // Named tags.
        mode {
            scope = "type"
            begin = """!\w+!$uriCharacters"""
        },
        // Verbatim tags, see https://yaml.org/spec/1.2/spec.html#id2784064
        mode {
            scope = "type"
            begin = "!<$uriCharacters>"
        },
        // Primary tags.
        mode {
            scope = "type"
            begin = "!$uriCharacters"
        },
        // Secondary tags.
        mode {
            scope = "type"
            begin = "!!$uriCharacters"
        },
        // Fragment id `&ref`.
        mode {
            scope = "meta"
            begin = "&$UNDERSCORE_IDENT_RE$"
        },
        // Fragment reference `*ref`.
        mode {
            scope = "meta"
            begin = """\*$UNDERSCORE_IDENT_RE$"""
        },
        // Array listing.
        mode {
            scope = "bullet"
            begin = """-(?=[ ]|$)"""
            relevance = 0.0
        },
        HASH_COMMENT_MODE,
        mode {
            beginKeywords = literals
            keywords = keywords { literal(literals) }
        },
        timestamp,
        // Numbers are any valid C style number that sits isolated from other words.
        mode {
            scope = "number"
            begin = C_NUMBER_RE + """\b"""
            relevance = 0.0
        },
        objectMode,
        arrayMode,
        singleQuoteString,
        string,
    )

    valueContainer.contains = modes.dropLast(1) + containerString

    return Language(
        name = "YAML",
        aliases = setOf("yaml", "yml"),
        caseInsensitive = true,
        root = mode { contains = modes },
    )
}

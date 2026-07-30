package me.rerere.highlight.languages.dockerfile

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.HASH_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.NUMBER_MODE
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/** Dockerfile, ported from `lib/languages/dockerfile.js` of `highlight.js` 11.11.1. */
internal fun dockerfile(): Language = Language(
    name = "Dockerfile",
    aliases = setOf("dockerfile", "docker"),
    caseInsensitive = true,
    root = mode {
        keywords = keywords(
            listOf("from", "maintainer", "expose", "env", "arg", "user", "onbuild", "stopsignal"),
        )
        illegal = "</"
        contains = listOf(
            HASH_COMMENT_MODE,
            APOS_STRING_MODE,
            QUOTE_STRING_MODE,
            NUMBER_MODE,
            mode {
                beginKeywords = "run cmd entrypoint volume add copy workdir label healthcheck shell"
                starts = mode {
                    end = """[^\\]$"""
                    subLanguage = "bash"
                }
            },
        )
    },
)

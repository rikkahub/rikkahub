package me.rerere.highlight.languages.xml

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.either
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.optional

/** HTML and XML, ported from `lib/languages/xml.js` of `highlight.js` 11.11.1. */
internal fun xml(): Language {
    // XML names may contain a good deal more than ASCII letters
    // (https://www.w3.org/TR/xml/#NT-NameChar); upstream spells the full ranges out in a comment
    // and then settles for the Unicode letter class, both for performance and for wider coverage.
    val tagNameRe = concat(
        """[\p{L}_]""",
        optional("""[\p{L}0-9_.-]*:"""),
        """[\p{L}0-9_.-]*""",
    )
    val xmlIdentRe = """[\p{L}0-9._:-]+"""

    val xmlEntities = mode {
        scope = "symbol"
        begin = """&[a-z]+;|&#[0-9]+;|&#x[a-f0-9]+;"""
    }
    val xmlMetaKeywords = mode {
        begin = """\s"""
        contains = listOf(
            mode {
                scope = "keyword"
                begin = """#?[a-z_][a-z1-9_-]+"""
                illegal = """\n"""
            },
        )
    }
    val xmlMetaParKeywords = xmlMetaKeywords.inherit {
        begin = """\("""
        end = """\)"""
    }
    val aposMetaString = APOS_STRING_MODE.inherit { scope = "string" }
    val quoteMetaString = QUOTE_STRING_MODE.inherit { scope = "string" }

    val tagInternals = mode {
        endsWithParent = true
        illegal = "<"
        relevance = 0.0
        contains = listOf(
            mode {
                scope = "attr"
                begin = xmlIdentRe
                relevance = 0.0
            },
            mode {
                begin = """=\s*"""
                relevance = 0.0
                contains = listOf(
                    mode {
                        scope = "string"
                        endsParent = true
                        variants = listOf(
                            {
                                begin = "\""
                                end = "\""
                                contains = listOf(xmlEntities)
                            },
                            {
                                begin = "'"
                                end = "'"
                                contains = listOf(xmlEntities)
                            },
                            { begin = """[^\s"'=<>`]+""" },
                        )
                    },
                )
            },
        )
    }

    return Language(
        name = "HTML, XML",
        aliases = setOf(
            "xml", "html", "xhtml", "rss", "atom", "xjb", "xsd", "xsl", "plist", "wsf", "svg",
        ),
        caseInsensitive = true,
        unicodeRegex = true,
        root = mode {
            contains = listOf(
                mode {
                    scope = "meta"
                    begin = """<![a-z]"""
                    end = ">"
                    relevance = 10.0
                    contains = listOf(
                        xmlMetaKeywords,
                        quoteMetaString,
                        aposMetaString,
                        xmlMetaParKeywords,
                        mode {
                            begin = """\["""
                            end = """\]"""
                            contains = listOf(
                                mode {
                                    scope = "meta"
                                    begin = """<![a-z]"""
                                    end = ">"
                                    contains = listOf(
                                        xmlMetaKeywords,
                                        xmlMetaParKeywords,
                                        quoteMetaString,
                                        aposMetaString,
                                    )
                                },
                            )
                        },
                    )
                },
                comment("<!--", "-->") { relevance = 10.0 },
                mode {
                    begin = """<!\[CDATA\["""
                    end = """\]\]>"""
                    relevance = 10.0
                },
                xmlEntities,
                // XML processing instructions.
                mode {
                    scope = "meta"
                    end = """\?>"""
                    variants = listOf(
                        {
                            begin = """<\?xml"""
                            relevance = 10.0
                            contains = listOf(quoteMetaString)
                        },
                        { begin = """<\?[a-z][a-z0-9]+""" },
                    )
                },
                mode {
                    scope = "tag"
                    // The lookahead makes `begin` match `<style` only as a whole word, followed by
                    // whitespace or by the closing bracket.
                    begin = """<style(?=\s|>)"""
                    end = ">"
                    keywords = keywords { scope("name", "style") }
                    contains = listOf(tagInternals)
                    starts = mode {
                        end = """</style>"""
                        returnEnd = true
                        subLanguageList = listOf("css", "xml")
                    }
                },
                mode {
                    scope = "tag"
                    // See the comment on the `<style` tag about the lookahead.
                    begin = """<script(?=\s|>)"""
                    end = ">"
                    keywords = keywords { scope("name", "script") }
                    contains = listOf(tagInternals)
                    starts = mode {
                        end = """</script>"""
                        returnEnd = true
                        subLanguageList = listOf("javascript", "handlebars", "xml")
                    }
                },
                // Needed for JSX, for now.
                mode {
                    scope = "tag"
                    begin = """<>|</>"""
                },
                // Open tag.
                mode {
                    scope = "tag"
                    begin = concat(
                        "<",
                        lookahead(
                            concat(
                                tagNameRe,
                                // `<tag/>`, `<tag>` or `<tag ...`
                                either("/>", ">", """\s"""),
                            ),
                        ),
                    )
                    end = """/?>"""
                    contains = listOf(
                        mode {
                            scope = "name"
                            begin = tagNameRe
                            relevance = 0.0
                            starts = tagInternals
                        },
                    )
                },
                // Close tag.
                mode {
                    scope = "tag"
                    begin = concat("</", lookahead(concat(tagNameRe, ">")))
                    contains = listOf(
                        mode {
                            scope = "name"
                            begin = tagNameRe
                            relevance = 0.0
                        },
                        mode {
                            begin = ">"
                            relevance = 0.0
                            endsParent = true
                        },
                    )
                },
            )
        },
    )
}

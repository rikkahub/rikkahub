package me.rerere.highlight.languages.ruby

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.IDENT_RE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.MATCH_NOTHING_RE
import me.rerere.highlight.core.RE_STARTERS_RE
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.either
import me.rerere.highlight.core.endSameAsBegin
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.shebang

/** Ruby, ported from `lib/languages/ruby.js` of `highlight.js` 11.11.1. */
internal fun ruby(): Language {
    val rubyMethodRe =
        """([a-zA-Z_]\w*[!?=]?|[-+~]@|<<|>>|=~|===?|<=>|[<>]=?|\*\*|[-/+%^&*~`|]|\[\]=?)"""
    val classNameRe = either(
        """\b([A-Z]+[a-z0-9]+)+""",
        """\b([A-Z]+[a-z0-9]+)+[A-Z]+""",
    )
    val classNameWithNamespaceRe = concat(classNameRe, """(::\w+)*""")
    val pseudoKeywords = listOf(
        "include", "extend", "prepend", "public", "private", "protected", "raise", "throw",
    )
    val rubyKeywords = keywords {
        scope("variable.constant", listOf("__FILE__", "__LINE__", "__ENCODING__"))
        scope("variable.language", listOf("self", "super"))
        keyword(
            listOf(
                "alias", "and", "begin", "BEGIN", "break", "case", "class", "defined", "do",
                "else", "elsif", "end", "END", "ensure", "for", "if", "in", "module", "next",
                "not", "or", "redo", "require", "rescue", "retry", "return", "then", "undef",
                "unless", "until", "when", "while", "yield",
            ) + pseudoKeywords,
        )
        builtIn(
            listOf(
                "proc", "lambda", "attr_accessor", "attr_reader", "attr_writer", "define_method",
                "private_constant", "module_function",
            ),
        )
        literal(listOf("true", "false", "nil"))
    }

    val yardocTag = mode {
        scope = "doctag"
        begin = "@[A-Za-z]+"
    }
    val irbObject = mode {
        begin = "#<"
        end = ">"
    }
    val baseCommentModes = listOf(
        comment("#", "$") { contains = listOf(yardocTag) },
        comment("^=begin", "^=end") {
            contains = listOf(yardocTag)
            relevance = 10.0
        },
        comment("^__END__", MATCH_NOTHING_RE),
    )

    val subst = mode {
        scope = "subst"
        begin = """#\{"""
        end = """\}"""
        keywords = rubyKeywords
    }
    val string = mode {
        scope = "string"
        contains = listOf(BACKSLASH_ESCAPE, subst)
        variants = listOf(
            {
                begin = "'"
                end = "'"
            },
            {
                begin = "\""
                end = "\""
            },
            {
                begin = "`"
                end = "`"
            },
            {
                begin = """%[qQwWx]?\("""
                end = """\)"""
            },
            {
                begin = """%[qQwWx]?\["""
                end = """\]"""
            },
            {
                begin = """%[qQwWx]?\{"""
                end = """\}"""
            },
            {
                begin = """%[qQwWx]?<"""
                end = ">"
            },
            {
                begin = """%[qQwWx]?/"""
                end = "/"
            },
            {
                begin = """%[qQwWx]?%"""
                end = "%"
            },
            {
                begin = """%[qQwWx]?-"""
                end = "-"
            },
            {
                begin = """%[qQwWx]?\|"""
                end = """\|"""
            },
            { begin = """\B\?(\\\d{1,3})""" },
            { begin = """\B\?(\\x[A-Fa-f0-9]{1,2})""" },
            { begin = """\B\?(\\u\{?[A-Fa-f0-9]{1,6}\}?)""" },
            { begin = """\B\?(\\M-\\C-|\\M-\\c|\\c\\M-|\\M-|\\C-\\M-)[\x20-\x7e]""" },
            { begin = """\B\?\\(c|C-)[\x20-\x7e]""" },
            { begin = """\B\?\\?\S""" },
            {
                begin = concat(
                    """<<[-~]?'?""",
                    lookahead("""(\w+)(?=\W)[^\n]*\n(?:[^\n]*\n)*?\s*\1\b"""),
                )
                contains = listOf(
                    mode {
                        begin = """(\w+)"""
                        end = """(\w+)"""
                        contains = listOf(BACKSLASH_ESCAPE, subst)
                    }.endSameAsBegin(),
                )
            },
        )
    }

    val decimal = "[1-9](_?[0-9])*|0"
    val digits = "[0-9](_?[0-9])*"
    val number = mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            {
                begin = """\b($decimal)(\.($digits))?([eE][+-]?($digits)|r)?i?\b"""
            },
            { begin = """\b0[dD][0-9](_?[0-9])*r?i?\b""" },
            { begin = """\b0[bB][0-1](_?[0-1])*r?i?\b""" },
            { begin = """\b0[oO][0-7](_?[0-7])*r?i?\b""" },
            { begin = """\b0[xX][0-9a-fA-F](_?[0-9a-fA-F])*r?i?\b""" },
            { begin = """\b0(_?[0-7])+r?i?\b""" },
        )
    }
    val params = mode {
        variants = listOf(
            { match = """\(\)""" },
            {
                scope = "params"
                begin = """\("""
                end = """(?=\))"""
                excludeBegin = true
                endsParent = true
                keywords = rubyKeywords
            },
        )
    }
    val includeExtend = mode {
        matchList = listOf(
            """(include|extend)\s+""",
            classNameWithNamespaceRe,
        )
        scopes = mapOf(2 to "title.class")
        keywords = rubyKeywords
    }
    val classDefinition = mode {
        variants = listOf(
            {
                matchList = listOf(
                    """class\s+""",
                    classNameWithNamespaceRe,
                    """\s+<\s+""",
                    classNameWithNamespaceRe,
                )
            },
            {
                matchList = listOf(
                    """\b(class|module)\s+""",
                    classNameWithNamespaceRe,
                )
            },
        )
        scopes = mapOf(
            2 to "title.class",
            4 to "title.class.inherited",
        )
        keywords = rubyKeywords
    }
    val upperCaseConstant = mode {
        relevance = 0.0
        match = """\b[A-Z][A-Z_0-9]+\b"""
        scope = "variable.constant"
    }
    val methodDefinition = mode {
        matchList = listOf("def", """\s+""", rubyMethodRe)
        scopes = mapOf(
            1 to "keyword",
            3 to "title.function",
        )
        contains = listOf(params)
    }
    val objectCreation = mode {
        relevance = 0.0
        matchList = listOf(classNameWithNamespaceRe, """\.new[. (]""")
        scopes = mapOf(1 to "title.class")
    }
    val classReference = mode {
        relevance = 0.0
        match = classNameRe
        scope = "title.class"
    }

    val regexpContainer = mode {
        begin = "($RE_STARTERS_RE|unless)\\s*"
        keywords = keywords("unless")
        contains = listOf(
            mode {
                scope = "regexp"
                contains = listOf(BACKSLASH_ESCAPE, subst)
                illegal = """\n"""
                variants = listOf(
                    {
                        begin = "/"
                        end = "/[a-z]*"
                    },
                    {
                        begin = """%r\{"""
                        end = """\}[a-z]*"""
                    },
                    {
                        begin = """%r\("""
                        end = """\)[a-z]*"""
                    },
                    {
                        begin = "%r!"
                        end = "![a-z]*"
                    },
                    {
                        begin = """%r\["""
                        end = """\][a-z]*"""
                    },
                )
            },
        ) + irbObject + baseCommentModes
        relevance = 0.0
    }

    val rubyDefaultContains = listOf(
        string,
        classDefinition,
        includeExtend,
        objectCreation,
        upperCaseConstant,
        classReference,
        methodDefinition,
        mode { begin = IDENT_RE + "::" },
        mode {
            scope = "symbol"
            begin = UNDERSCORE_IDENT_RE + """(!|\?)?:"""
            relevance = 0.0
        },
        mode {
            scope = "symbol"
            begin = """:(?!\s)"""
            contains = listOf(string, mode { begin = rubyMethodRe })
            relevance = 0.0
        },
        number,
        mode {
            scope = "variable"
            begin = """(\$\W)|((\$|@@?)(\w+))(?=[^@${'$'}?])""" +
                """(?![A-Za-z])(?![@${'$'}?'])"""
        },
        mode {
            scope = "params"
            begin = """\|(?!=)"""
            end = """\|"""
            excludeBegin = true
            excludeEnd = true
            relevance = 0.0
            keywords = rubyKeywords
        },
        regexpContainer,
    ) + irbObject + baseCommentModes
    subst.contains = rubyDefaultContains
    params.contains = rubyDefaultContains

    val simplePrompt = "[>?]>"
    val defaultPrompt = """[\w#]+\(\w+\):\d+:\d+[>*]"""
    val rvmPrompt = """(\w+-)?\d+\.\d+\.\d+(p\d+)?[^\d][^>]+>"""
    val irbDefault = listOf(
        mode {
            begin = """^\s*=>"""
            starts = mode {
                end = "$"
                contains = rubyDefaultContains
            }
        },
        mode {
            scope = "meta.prompt"
            begin = "^($simplePrompt|$defaultPrompt|$rvmPrompt)(?=[ ])"
            starts = mode {
                end = "$"
                keywords = rubyKeywords
                contains = rubyDefaultContains
            }
        },
    )
    val commentModes = listOf(irbObject) + baseCommentModes

    return Language(
        name = "Ruby",
        aliases = setOf("ruby", "rb", "gemspec", "podspec", "thor", "irb"),
        root = mode {
            keywords = rubyKeywords
            illegal = """/\*"""
            contains = listOf(shebang("ruby")) + irbDefault + commentModes + rubyDefaultContains
        },
    )
}

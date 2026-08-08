package me.rerere.highlight.languages.python

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.HASH_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.variant

/** Python, ported from `lib/languages/python.js` of `highlight.js` 11.11.1. */
internal fun python(): Language {
    // `XID_Start` / `XID_Continue` are Unicode properties, so a name may be written in any script;
    // `translateJsRegex` maps them onto the properties `java.util.regex` knows them by.
    val identRe = """[\p{XID_Start}_]\p{XID_Continue}*"""

    val reservedWords = listOf(
        "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def",
        "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import",
        "in", "is", "lambda", "match", "nonlocal|10", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield",
    )
    val builtIns = listOf(
        "__import__", "abs", "all", "any", "ascii", "bin", "bool", "breakpoint", "bytearray",
        "bytes", "callable", "chr", "classmethod", "compile", "complex", "delattr", "dict", "dir",
        "divmod", "enumerate", "eval", "exec", "filter", "float", "format", "frozenset", "getattr",
        "globals", "hasattr", "hash", "help", "hex", "id", "input", "int", "isinstance",
        "issubclass", "iter", "len", "list", "locals", "map", "max", "memoryview", "min", "next",
        "object", "oct", "open", "ord", "pow", "print", "property", "range", "repr", "reversed",
        "round", "set", "setattr", "slice", "sorted", "staticmethod", "str", "sum", "super",
        "tuple", "type", "vars", "zip",
    )
    val literals = listOf("__debug__", "Ellipsis", "False", "None", "NotImplemented", "True")
    // https://docs.python.org/3/library/typing.html
    val types = listOf(
        "Any", "Callable", "Coroutine", "Dict", "List", "Literal", "Generic", "Optional",
        "Sequence", "Set", "Tuple", "Type", "Union",
    )

    val pythonKeywords = keywords {
        pattern = """[A-Za-z]\w+|__\w+__"""
        keyword(reservedWords)
        builtIn(builtIns)
        literal(literals)
        type(types)
    }

    val prompt = mode {
        scope = "meta"
        begin = """^(>>>|\.\.\.) """
    }

    val subst = mode {
        scope = "subst"
        begin = """\{"""
        end = """\}"""
        keywords = pythonKeywords
        illegal = "#"
    }
    val literalBracket = mode {
        begin = """\{\{"""
        relevance = 0.0
    }

    val string = mode {
        scope = "string"
        contains = listOf(BACKSLASH_ESCAPE)
        variants = listOf(
            {
                begin = """([uU]|[bB]|[rR]|[bB][rR]|[rR][bB])?'''"""
                end = "'''"
                contains = listOf(BACKSLASH_ESCAPE, prompt)
                relevance = 10.0
            },
            {
                begin = """([uU]|[bB]|[rR]|[bB][rR]|[rR][bB])?$TRIPLE_QUOTE"""
                end = TRIPLE_QUOTE
                contains = listOf(BACKSLASH_ESCAPE, prompt)
                relevance = 10.0
            },
            {
                begin = """([fF][rR]|[rR][fF]|[fF])'''"""
                end = "'''"
                contains = listOf(BACKSLASH_ESCAPE, prompt, literalBracket, subst)
            },
            {
                begin = """([fF][rR]|[rR][fF]|[fF])$TRIPLE_QUOTE"""
                end = TRIPLE_QUOTE
                contains = listOf(BACKSLASH_ESCAPE, prompt, literalBracket, subst)
            },
            {
                begin = """([uU]|[rR])'"""
                end = "'"
                relevance = 10.0
            },
            {
                begin = """([uU]|[rR])""" + "\""
                end = "\""
                relevance = 10.0
            },
            {
                begin = """([bB]|[bB][rR]|[rR][bB])'"""
                end = "'"
            },
            {
                begin = """([bB]|[bB][rR]|[rR][bB])""" + "\""
                end = "\""
            },
            {
                begin = """([fF][rR]|[rR][fF]|[fF])'"""
                end = "'"
                contains = listOf(BACKSLASH_ESCAPE, literalBracket, subst)
            },
            {
                begin = """([fF][rR]|[rR][fF]|[fF])""" + "\""
                end = "\""
                contains = listOf(BACKSLASH_ESCAPE, literalBracket, subst)
            },
            variant(APOS_STRING_MODE),
            variant(QUOTE_STRING_MODE),
        )
    }

    // https://docs.python.org/3.9/reference/lexical_analysis.html#numeric-literals
    val digitPart = """[0-9](_?[0-9])*"""
    val pointFloat = """(\b($digitPart))?\.($digitPart)|\b($digitPart)\."""
    // Whitespace after a number is only needed where its absence would change the tokenization, so
    // upstream settles for a word boundary or a keyword. That keeps a *prefix* — the `0` of `0x41`,
    // of `08`, of `0__1` — from being read as a number of its own.
    val numberLookahead = """\b|""" + reservedWords.joinToString("|")

    val number = mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            // An exponent float or a point float, optionally imaginary. No leading `\b`, because a
            // float may start with the decimal point; no trailing one for a point float, because it
            // may end with one — `0..hex()` — and a decimal point cannot occur inside a name.
            { begin = """(\b($digitPart)|($pointFloat))[eE][+-]?($digitPart)[jJ]?(?=$numberLookahead)""" },
            { begin = """($pointFloat)[jJ]?""" },

            // Decimal, binary, octal and hexadecimal integers, optionally "long" in Python 2 and,
            // for the decimal one, optionally imaginary.
            { begin = """\b([1-9](_?[0-9])*|0+(_?0)*)[lLjJ]?(?=$numberLookahead)""" },
            { begin = """\b0[bB](_?[01])+[lL]?(?=$numberLookahead)""" },
            { begin = """\b0[oO](_?[0-7])+[lL]?(?=$numberLookahead)""" },
            { begin = """\b0[xX](_?[0-9a-fA-F])+[lL]?(?=$numberLookahead)""" },

            { begin = """\b($digitPart)[jJ](?=$numberLookahead)""" },
        )
    }

    val commentType = mode {
        scope = "comment"
        begin = lookahead("""# type:""")
        end = "${'$'}"
        keywords = pythonKeywords
        contains = listOf(
            // Keeps `type` itself from being coloured as a keyword.
            mode { begin = """# type:""" },
            // A comment nested in a datatype comment carries no keywords.
            mode {
                begin = "#"
                end = """\b\B"""
                endsWithParent = true
            },
        )
    }

    val params = mode {
        scope = "params"
        variants = listOf(
            // A function without parameters gets no `params` scope at all.
            {
                scope = null
                begin = """\(\s*\)"""
                skip = true
            },
            {
                begin = """\("""
                end = """\)"""
                excludeBegin = true
                excludeEnd = true
                keywords = pythonKeywords
                contains = listOf(Mode.SELF, prompt, number, string, HASH_COMMENT_MODE)
            },
        )
    }
    subst.contains = listOf(string, number, prompt)

    return Language(
        name = "Python",
        aliases = setOf("python", "py", "gyp", "ipython"),
        unicodeRegex = true,
        root = mode {
            keywords = pythonKeywords
            illegal = """(<\/|\?)|=>"""
            contains = listOf(
                prompt,
                number,
                mode {
                    // Very common convention.
                    scope = "variable.language"
                    match = """\bself\b"""
                },
                mode {
                    // Eat `if` before the string modes get to it, so that `if"…"` is not read as an
                    // f-string.
                    beginKeywords = "if"
                    relevance = 0.0
                },
                mode {
                    match = """\bor\b"""
                    scope = "keyword"
                },
                string,
                commentType,
                HASH_COMMENT_MODE,
                mode {
                    matchList = listOf("""\bdef""", """\s+""", identRe)
                    scopes = mapOf(1 to "keyword", 3 to "title.function")
                    contains = listOf(params)
                },
                mode {
                    variants = listOf(
                        {
                            matchList = listOf(
                                """\bclass""", """\s+""",
                                identRe, """\s*""",
                                """\(\s*""", identRe, """\s*\)""",
                            )
                        },
                        {
                            matchList = listOf("""\bclass""", """\s+""", identRe)
                        },
                    )
                    scopes = mapOf(
                        1 to "keyword",
                        3 to "title.class",
                        6 to "title.class.inherited",
                    )
                },
                mode {
                    scope = "meta"
                    begin = """^[\t ]*@"""
                    end = """(?=#)|${'$'}"""
                    contains = listOf(number, params, string)
                },
            )
        },
    )
}

private const val TRIPLE_QUOTE = "\"\"\""

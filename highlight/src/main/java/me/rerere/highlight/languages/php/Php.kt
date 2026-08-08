package me.rerere.highlight.languages.php

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.HASH_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.MATCH_NOTHING_RE
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.UNDERSCORE_TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.either
import me.rerere.highlight.core.endSameAsBegin
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.variant

/** PHP, ported from `lib/languages/php.js` of `highlight.js` 11.11.1. */
internal fun php(): Language {
    // The negative lookaheads avoid matching Perl-like `$ident$` and `@ident@` patterns.
    val notPerlEtc = """(?![A-Za-z0-9])(?![$])"""
    val identRe = concat("""[a-zA-Z_\x7f-\xff][a-zA-Z0-9_\x7f-\xff]*""", notPerlEtc)
    // Deliberately does not detect camelCase class names, matching upstream.
    val pascalCaseClassNameRe = concat(
        """(\\?[A-Z][a-z0-9_\x7f-\xff]+|\\?[A-Z]+(?=[A-Z][a-z0-9_\x7f-\xff])){1,}""",
        notPerlEtc,
    )
    val upcaseNameRe = concat("""[A-Z]+""", notPerlEtc)

    val variable = mode {
        scope = "variable"
        match = """\$+""" + identRe
    }
    val preprocessor = mode {
        scope = "meta"
        variants = listOf(
            {
                begin = """<\?php"""
                relevance = 10.0
            },
            { begin = """<\?=""" },
            {
                begin = """<\?"""
                relevance = 0.1
            },
            { begin = """\?>""" },
        )
    }
    val subst = mode {
        scope = "subst"
        variants = listOf(
            { begin = """\$\w+""" },
            {
                begin = """\{\$"""
                end = """\}"""
            },
        )
    }
    val singleQuoted = APOS_STRING_MODE.inherit { illegal = null }
    val doubleQuoted = QUOTE_STRING_MODE.inherit {
        illegal = null
        contains = QUOTE_STRING_MODE.contains + subst
    }
    val heredoc = mode {
        begin = """<<<[ \t]*(?:(\w+)|"(\w+)")\n"""
        end = """[ \t]*(\w+)\b"""
        contains = listOf(BACKSLASH_ESCAPE, subst)
        onBegin = { match, response -> response.data["_beginMatch"] = match[1] ?: match[2] }
        onEnd = { match, response ->
            if (response.data["_beginMatch"] != match[1]) response.ignoreMatch()
        }
    }
    val nowdoc = mode {
        begin = """<<<[ \t]*'(\w+)'\n"""
        end = """[ \t]*(\w+)\b"""
    }.endSameAsBegin()
    val string = mode {
        scope = "string"
        variants = listOf(
            variant(doubleQuoted),
            variant(singleQuoted),
            variant(heredoc),
            variant(nowdoc),
        )
    }

    val number = mode {
        scope = "number"
        variants = listOf(
            { begin = """\b0[bB][01]+(?:_[01]+)*\b""" },
            { begin = """\b0[oO][0-7]+(?:_[0-7]+)*\b""" },
            { begin = """\b0[xX][\da-fA-F]+(?:_[\da-fA-F]+)*\b""" },
            {
                begin =
                    """(?:\b\d+(?:_\d+)*(\.(?:\d+(?:_\d+)*))?|\B\.\d+)(?:[eE][+-]?\d+)?"""
            },
        )
        relevance = 0.0
    }

    val literals = listOf("false", "null", "true")
    val kws = listOf(
        // Magic constants.
        "__CLASS__", "__DIR__", "__FILE__", "__FUNCTION__", "__COMPILER_HALT_OFFSET__", "__LINE__",
        "__METHOD__", "__NAMESPACE__", "__TRAIT__",
        // Language constructs that look like functions.
        "die", "echo", "exit", "include", "include_once", "print", "require", "require_once",
        // Reserved words and type names.
        "array", "abstract", "and", "as", "binary", "bool", "boolean", "break", "callable", "case",
        "catch", "class", "clone", "const", "continue", "declare", "default", "do", "double",
        "else", "elseif", "empty", "enddeclare", "endfor", "endforeach", "endif", "endswitch",
        "endwhile", "enum", "eval", "extends", "final", "finally", "float", "for", "foreach",
        "from", "global", "goto", "if", "implements", "instanceof", "insteadof", "int", "integer",
        "interface", "isset", "iterable", "list", "match|0", "mixed", "new", "never", "object",
        "or", "private", "protected", "public", "readonly", "real", "return", "string", "switch",
        "throw", "trait", "try", "unset", "use", "var", "void", "while", "xor", "yield",
    )
    val builtIns = listOf(
        // Standard PHP library.
        "Error|0", "AppendIterator", "ArgumentCountError", "ArithmeticError", "ArrayIterator",
        "ArrayObject", "AssertionError", "BadFunctionCallException", "BadMethodCallException",
        "CachingIterator", "CallbackFilterIterator", "CompileError", "Countable",
        "DirectoryIterator", "DivisionByZeroError", "DomainException", "EmptyIterator",
        "ErrorException", "Exception", "FilesystemIterator", "FilterIterator", "GlobIterator",
        "InfiniteIterator", "InvalidArgumentException", "IteratorIterator", "LengthException",
        "LimitIterator", "LogicException", "MultipleIterator", "NoRewindIterator",
        "OutOfBoundsException", "OutOfRangeException", "OuterIterator", "OverflowException",
        "ParentIterator", "ParseError", "RangeException", "RecursiveArrayIterator",
        "RecursiveCachingIterator", "RecursiveCallbackFilterIterator", "RecursiveDirectoryIterator",
        "RecursiveFilterIterator", "RecursiveIterator", "RecursiveIteratorIterator",
        "RecursiveRegexIterator", "RecursiveTreeIterator", "RegexIterator", "RuntimeException",
        "SeekableIterator", "SplDoublyLinkedList", "SplFileInfo", "SplFileObject", "SplFixedArray",
        "SplHeap", "SplMaxHeap", "SplMinHeap", "SplObjectStorage", "SplObserver",
        "SplPriorityQueue", "SplQueue", "SplStack", "SplSubject", "SplTempFileObject", "TypeError",
        "UnderflowException", "UnexpectedValueException", "UnhandledMatchError",
        // Reserved interfaces.
        "ArrayAccess", "BackedEnum", "Closure", "Fiber", "Generator", "Iterator",
        "IteratorAggregate", "Serializable", "Stringable", "Throwable", "Traversable", "UnitEnum",
        "WeakReference", "WeakMap",
        // Reserved classes.
        "Directory", "__PHP_Incomplete_Class", "parent", "php_user_filter", "self", "static",
        "stdClass",
    )

    val phpKeywords = keywords {
        keyword(kws)
        literal(dualCase(literals))
        builtIn(builtIns)
    }
    val whitespace = """[ \t\n]"""
    val normalizedBuiltIns = normalizeKeywords(builtIns)
    val normalizedKws = normalizeKeywords(kws)

    val constructorCall = mode {
        variants = listOf(
            {
                matchList = listOf(
                    "new",
                    concat(whitespace, "+"),
                    concat("(?!", normalizedBuiltIns.joinToString("\\b|"), """\b)"""),
                    pascalCaseClassNameRe,
                )
                scopes = mapOf(
                    1 to "keyword",
                    4 to "title.class",
                )
            },
        )
    }

    val constantReference = concat(identRe, """\b(?!\()""")
    val leftAndRightSideOfDoubleColon = mode {
        variants = listOf(
            {
                matchList = listOf(
                    concat("::", lookahead("""(?!class\b)""")),
                    constantReference,
                )
                scopes = mapOf(2 to "variable.constant")
            },
            {
                matchList = listOf("::", "class")
                scopes = mapOf(2 to "variable.language")
            },
            {
                matchList = listOf(
                    pascalCaseClassNameRe,
                    concat("::", lookahead("""(?!class\b)""")),
                    constantReference,
                )
                scopes = mapOf(
                    1 to "title.class",
                    3 to "variable.constant",
                )
            },
            {
                matchList = listOf(
                    pascalCaseClassNameRe,
                    concat("::", lookahead("""(?!class\b)""")),
                )
                scopes = mapOf(1 to "title.class")
            },
            {
                matchList = listOf(pascalCaseClassNameRe, "::", "class")
                scopes = mapOf(
                    1 to "title.class",
                    3 to "variable.language",
                )
            },
        )
    }

    val namedArgument = mode {
        scope = "attr"
        match = concat(identRe, lookahead(":"), lookahead("(?!::)"))
    }
    val paramsMode = mode {
        relevance = 0.0
        begin = """\("""
        end = """\)"""
        keywords = phpKeywords
    }
    val functionInvoke = mode {
        relevance = 0.0
        matchList = listOf(
            """\b""",
            concat(
                """(?!fn\b|function\b|""",
                normalizedKws.joinToString("\\b|"),
                "|",
                normalizedBuiltIns.joinToString("\\b|"),
                """\b)""",
            ),
            identRe,
            concat(whitespace, "*"),
            lookahead("""(?=\()"""),
        )
        scopes = mapOf(3 to "title.function.invoke")
        contains = listOf(paramsMode)
    }
    paramsMode.contains = listOf(
        namedArgument,
        variable,
        leftAndRightSideOfDoubleColon,
        C_BLOCK_COMMENT_MODE,
        string,
        number,
        constructorCall,
        functionInvoke,
    )

    val attributeContains = listOf(
        namedArgument,
        leftAndRightSideOfDoubleColon,
        C_BLOCK_COMMENT_MODE,
        string,
        number,
        constructorCall,
    )
    val attributeKeywords = keywords {
        literal(literals)
        keyword(listOf("new", "array"))
    }
    val attributes = mode {
        begin = concat(
            """#\[\s*\\?""",
            either(pascalCaseClassNameRe, upcaseNameRe),
        )
        beginScope = "meta"
        end = "]"
        endScope = "meta"
        keywords = attributeKeywords
        contains = listOf(
            mode {
                begin = """\["""
                end = "]"
                keywords = attributeKeywords
                contains = listOf(Mode.SELF) + attributeContains
            },
        ) + attributeContains + mode {
            scope = "meta"
            variants = listOf(
                { match = pascalCaseClassNameRe },
                { match = upcaseNameRe },
            )
        }
    }

    return Language(
        name = "PHP",
        aliases = setOf("php"),
        root = mode {
            keywords = phpKeywords
            contains = listOf(
                attributes,
                HASH_COMMENT_MODE,
                comment("//", "$"),
                comment("""/\*""", """\*/""") {
                    contains = listOf(
                        mode {
                            scope = "doctag"
                            match = "@[A-Za-z]+"
                        },
                    )
                },
                mode {
                    match = """__halt_compiler\(\);"""
                    keywords = keywords("__halt_compiler")
                    starts = mode {
                        scope = "comment"
                        end = MATCH_NOTHING_RE
                        contains = listOf(
                            mode {
                                match = """\?>"""
                                scope = "meta"
                                endsParent = true
                            },
                        )
                    }
                },
                preprocessor,
                mode {
                    scope = "variable.language"
                    match = """\${'$'}this\b"""
                },
                variable,
                functionInvoke,
                leftAndRightSideOfDoubleColon,
                mode {
                    matchList = listOf("const", """\s""", identRe)
                    scopes = mapOf(
                        1 to "keyword",
                        3 to "variable.constant",
                    )
                },
                constructorCall,
                mode {
                    scope = "function"
                    relevance = 0.0
                    beginKeywords = "fn function"
                    end = """[;\{]"""
                    excludeEnd = true
                    illegal = """[$%\[]"""
                    contains = listOf(
                        mode { beginKeywords = "use" },
                        UNDERSCORE_TITLE_MODE,
                        mode {
                            begin = "=>"
                            endsParent = true
                        },
                        mode {
                            scope = "params"
                            begin = """\("""
                            end = """\)"""
                            excludeBegin = true
                            excludeEnd = true
                            keywords = phpKeywords
                            contains = listOf(
                                Mode.SELF,
                                attributes,
                                variable,
                                leftAndRightSideOfDoubleColon,
                                C_BLOCK_COMMENT_MODE,
                                string,
                                number,
                            )
                        },
                    )
                },
                mode {
                    scope = "class"
                    variants = listOf(
                        {
                            beginKeywords = "enum"
                            illegal = """[($"]"""
                        },
                        {
                            beginKeywords = "class interface trait"
                            illegal = """[:($"]"""
                        },
                    )
                    relevance = 0.0
                    end = """\{"""
                    excludeEnd = true
                    contains = listOf(
                        mode { beginKeywords = "extends implements" },
                        UNDERSCORE_TITLE_MODE,
                    )
                },
                mode {
                    beginKeywords = "namespace"
                    relevance = 0.0
                    end = ";"
                    illegal = """[.']"""
                    contains = listOf(
                        UNDERSCORE_TITLE_MODE.inherit { scope = "title.class" },
                    )
                },
                mode {
                    beginKeywords = "use"
                    relevance = 0.0
                    end = ";"
                    contains = listOf(
                        mode {
                            match = """\b(as|const|function)\b"""
                            scope = "keyword"
                        },
                        UNDERSCORE_TITLE_MODE,
                    )
                },
                string,
                number,
            )
        },
    )
}

/** Adds the opposite-case spelling for every PHP literal, exactly like upstream's `dualCase`. */
private fun dualCase(items: List<String>): List<String> = buildList {
    items.forEach { item ->
        add(item)
        add(if (item.lowercase() == item) item.uppercase() else item.lowercase())
    }
}

private fun normalizeKeywords(items: List<String>): List<String> =
    items.map { it.replace(Regex("""\|\d+$"""), "") }

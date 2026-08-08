package me.rerere.highlight.languages.cpp

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.IDENT_RE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.endSameAsBegin
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.optional
import me.rerere.highlight.core.variant

/** C++, ported from `lib/languages/cpp.js` of `highlight.js` 11.11.1. */
internal fun cpp(): Language {
    // A line comment that survives a backslash newline. `C_LINE_COMMENT_MODE` cannot grow that
    // ability without changing it for every grammar that shares it, so C++ keeps its own.
    val lineComment = comment("//", "${'$'}") {
        contains = listOf(mode { begin = """\\\n""" })
    }
    val declTypeAutoRe = """decltype\(auto\)"""
    val namespaceRe = """[a-zA-Z_]\w*::"""
    val templateArgumentRe = "<[^<>]+>"
    val functionTypeRe = "(?!struct)(" + declTypeAutoRe + "|" + optional(namespaceRe) +
        """[a-zA-Z_]\w*""" + optional(templateArgumentRe) + ")"

    val primitiveTypes = mode {
        scope = "type"
        begin = """\b[a-z\d_]*_t\b"""
    }

    // https://en.cppreference.com/w/cpp/language/escape — `\\`, `\x`, `\xFF`, `\u2837`,
    // `\u00323747`, `\374`.
    val characterEscapes = """\\(x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4,8}|[0-7]{3}|\S)"""
    val strings = mode {
        scope = "string"
        variants = listOf(
            {
                begin = """(u8?|U|L)?""" + "\""
                end = "\""
                illegal = """\n"""
                contains = listOf(BACKSLASH_ESCAPE)
            },
            {
                begin = """(u8?|U|L)?'($characterEscapes|.)"""
                end = "'"
                // Anything beyond the single character the `begin` already took is illegal.
                illegal = "."
            },
            variant(
                mode {
                    begin = """(?:u8?|U|L)?R"([^()\\ ]{0,16})\("""
                    end = """\)([^()\\ ]{0,16})""" + "\""
                }.endSameAsBegin(),
            ),
        )
    }

    val numbers = mode {
        scope = "number"
        variants = listOf(
            // Floating point literal.
            {
                begin = "[+-]?(?:" + // Leading sign.
                    // Decimal.
                    "(?:" +
                    """[0-9](?:'?[0-9])*\.(?:[0-9](?:'?[0-9])*)?""" +
                    """|\.[0-9](?:'?[0-9])*""" +
                    ")(?:[Ee][+-]?[0-9](?:'?[0-9])*)?" +
                    "|[0-9](?:'?[0-9])*[Ee][+-]?[0-9](?:'?[0-9])*" +
                    // Hexadecimal.
                    "|0[Xx](?:" +
                    """[0-9A-Fa-f](?:'?[0-9A-Fa-f])*(?:\.(?:[0-9A-Fa-f](?:'?[0-9A-Fa-f])*)?)?""" +
                    """|\.[0-9A-Fa-f](?:'?[0-9A-Fa-f])*""" +
                    ")[Pp][+-]?[0-9](?:'?[0-9])*" +
                    ")(?:" + // Literal suffixes.
                    "[Ff](?:16|32|64|128)?" +
                    "|(BF|bf)16" +
                    "|[Ll]" +
                    "|" + // The suffix is optional.
                    ")"
            },
            // Integer literal.
            {
                begin = """[+-]?\b(?:""" + // Leading sign.
                    "0[Bb][01](?:'?[01])*" + // Binary.
                    "|0[Xx][0-9A-Fa-f](?:'?[0-9A-Fa-f])*" + // Hexadecimal.
                    "|0(?:'?[0-7])*" + // Octal, or a lone zero.
                    "|[1-9](?:'?[0-9])*" + // Decimal.
                    ")(?:" + // Literal suffixes.
                    "[Uu](?:LL?|ll?)" +
                    "|[Uu][Zz]?" +
                    "|(?:LL?|ll?)[Uu]?" +
                    "|[Zz][Uu]" +
                    "|" + // The suffix is optional.
                    ")"
                // There are user defined literal suffixes too, but leaving them out of the
                // highlight arguably makes them stand out more.
            },
        )
        relevance = 0.0
    }

    val preprocessor = mode {
        scope = "meta"
        begin = """#\s*[a-z]+\b"""
        end = "${'$'}"
        keywords = keywords {
            keyword(
                "if else elif endif define undef warning error line " +
                    "pragma _Pragma ifdef ifndef include"
            )
        }
        contains = listOf(
            mode {
                begin = """\\\n"""
                relevance = 0.0
            },
            strings.inherit { scope = "string" },
            mode {
                scope = "string"
                begin = """<.*?>"""
            },
            lineComment,
            C_BLOCK_COMMENT_MODE,
        )
    }

    val titleMode = mode {
        scope = "title"
        begin = optional(namespaceRe) + IDENT_RE
        relevance = 0.0
    }

    val functionTitle = optional(namespaceRe) + IDENT_RE + """\s*\("""

    // https://en.cppreference.com/w/cpp/keyword
    val reservedKeywords = listOf(
        "alignas", "alignof", "and", "and_eq", "asm", "atomic_cancel", "atomic_commit",
        "atomic_noexcept", "auto", "bitand", "bitor", "break", "case", "catch", "class",
        "co_await", "co_return", "co_yield", "compl", "concept", "const_cast|10", "consteval",
        "constexpr", "constinit", "continue", "decltype", "default", "delete", "do",
        "dynamic_cast|10", "else", "enum", "explicit", "export", "extern", "false", "final",
        "for", "friend", "goto", "if", "import", "inline", "module", "mutable", "namespace",
        "new", "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "override",
        "private", "protected", "public", "reflexpr", "register", "reinterpret_cast|10",
        "requires", "return", "sizeof", "static_assert", "static_cast|10", "struct", "switch",
        "synchronized", "template", "this", "thread_local", "throw", "transaction_safe",
        "transaction_safe_dynamic", "true", "try", "typedef", "typeid", "typename", "union",
        "using", "virtual", "volatile", "while", "xor", "xor_eq",
    )

    // https://en.cppreference.com/w/cpp/keyword
    val reservedTypes = listOf(
        "bool", "char", "char16_t", "char32_t", "char8_t", "double", "float", "int", "long",
        "short", "void", "wchar_t", "unsigned", "signed", "const", "static",
    )

    val typeHints = listOf(
        "any", "auto_ptr", "barrier", "binary_semaphore", "bitset", "complex",
        "condition_variable", "condition_variable_any", "counting_semaphore", "deque",
        "false_type", "flat_map", "flat_set", "future", "imaginary", "initializer_list",
        "istringstream", "jthread", "latch", "lock_guard", "multimap", "multiset", "mutex",
        "optional", "ostringstream", "packaged_task", "pair", "promise", "priority_queue",
        "queue", "recursive_mutex", "recursive_timed_mutex", "scoped_lock", "set",
        "shared_future", "shared_lock", "shared_mutex", "shared_timed_mutex", "shared_ptr",
        "stack", "string_view", "stringstream", "timed_mutex", "thread", "true_type", "tuple",
        "unique_lock", "unique_ptr", "unordered_map", "unordered_multimap", "unordered_multiset",
        "unordered_set", "variant", "vector", "weak_ptr", "wstring", "wstring_view",
    )

    val functionHints = listOf(
        "abort", "abs", "acos", "apply", "as_const", "asin", "atan", "atan2", "calloc", "ceil",
        "cerr", "cin", "clog", "cos", "cosh", "cout", "declval", "endl", "exchange", "exit",
        "exp", "fabs", "floor", "fmod", "forward", "fprintf", "fputs", "free", "frexp", "fscanf",
        "future", "invoke", "isalnum", "isalpha", "iscntrl", "isdigit", "isgraph", "islower",
        "isprint", "ispunct", "isspace", "isupper", "isxdigit", "labs", "launder", "ldexp",
        "log", "log10", "make_pair", "make_shared", "make_shared_for_overwrite", "make_tuple",
        "make_unique", "malloc", "memchr", "memcmp", "memcpy", "memset", "modf", "move", "pow",
        "printf", "putchar", "puts", "realloc", "scanf", "sin", "sinh", "snprintf", "sprintf",
        "sqrt", "sscanf", "std", "stderr", "stdin", "stdout", "strcat", "strchr", "strcmp",
        "strcpy", "strcspn", "strlen", "strncat", "strncmp", "strncpy", "strpbrk", "strrchr",
        "strspn", "strstr", "swap", "tan", "tanh", "terminate", "to_underlying", "tolower",
        "toupper", "vfprintf", "visit", "vprintf", "vsprintf",
    )

    val cppKeywords = keywords {
        type(reservedTypes)
        keyword(reservedKeywords)
        literal(listOf("NULL", "false", "nullopt", "nullptr", "true"))
        // https://en.cppreference.com/w/cpp/keyword
        builtIn(listOf("_Pragma"))
        // A leading underscore means the words only feed relevance, never colour.
        scope("_type_hints", typeHints)
    }

    val functionDispatch = mode {
        scope = "function.dispatch"
        relevance = 0.0
        keywords = keywords { scope("_hint", functionHints) }
        begin = concat(
            """\b""",
            """(?!decltype)""",
            """(?!if)""",
            """(?!for)""",
            """(?!switch)""",
            """(?!while)""",
            IDENT_RE,
            lookahead("""(<[^<>]+>|)\s*\("""),
        )
    }

    val expressionContains = listOf(
        functionDispatch,
        preprocessor,
        primitiveTypes,
        lineComment,
        C_BLOCK_COMMENT_MODE,
        numbers,
        strings,
    )

    val expressionContext = mode {
        // Expression context, where a function definition cannot occur and nothing that merely
        // looks like one may be highlighted: `return some()`, `else if()`, `(x*sum(1, 2))`.
        variants = listOf(
            {
                begin = "="
                end = ";"
            },
            {
                begin = """\("""
                end = """\)"""
            },
            {
                beginKeywords = "new throw return else"
                end = ";"
            },
        )
        keywords = cppKeywords
        contains = expressionContains + mode {
            begin = """\("""
            end = """\)"""
            keywords = cppKeywords
            contains = expressionContains + Mode.SELF
            relevance = 0.0
        }
        relevance = 0.0
    }

    val functionDeclaration = mode {
        scope = "function"
        begin = "($functionTypeRe[\\*&\\s]+)+$functionTitle"
        returnBegin = true
        end = """[{;=]"""
        excludeEnd = true
        keywords = cppKeywords
        illegal = """[^\w\s\*&:<>.]"""
        contains = listOf(
            // Keeps `decltype(auto)` from being read as the function title.
            mode {
                begin = declTypeAutoRe
                keywords = cppKeywords
                relevance = 0.0
            },
            mode {
                begin = functionTitle
                returnBegin = true
                contains = listOf(titleMode)
                relevance = 0.0
            },
            // There is no look-behind on the initializer rule below, so the `::` pair has to be
            // eaten first or its trailing `:` would open one.
            mode {
                begin = "::"
                relevance = 0.0
            },
            // Initializers.
            mode {
                begin = ":"
                endsWithParent = true
                contains = listOf(strings, numbers)
            },
            // Several declarations may share one type: `extern void f(int), g(char);`
            mode {
                relevance = 0.0
                match = ","
            },
            mode {
                scope = "params"
                begin = """\("""
                end = """\)"""
                keywords = cppKeywords
                relevance = 0.0
                contains = listOf(
                    lineComment,
                    C_BLOCK_COMMENT_MODE,
                    strings,
                    numbers,
                    primitiveTypes,
                    // Count matching parentheses.
                    mode {
                        begin = """\("""
                        end = """\)"""
                        keywords = cppKeywords
                        relevance = 0.0
                        contains = listOf(
                            Mode.SELF,
                            lineComment,
                            C_BLOCK_COMMENT_MODE,
                            strings,
                            numbers,
                            primitiveTypes,
                        )
                    },
                )
            },
            primitiveTypes,
            lineComment,
            C_BLOCK_COMMENT_MODE,
            preprocessor,
        )
    }

    return Language(
        name = "C++",
        aliases = setOf("cpp", "cc", "c++", "h++", "hpp", "hh", "hxx", "cxx"),
        classNameAliases = mapOf("function.dispatch" to "built_in"),
        root = mode {
            keywords = cppKeywords
            illegal = "</"
            contains = listOf(expressionContext, functionDeclaration, functionDispatch) +
                expressionContains +
                listOf(
                    preprocessor,
                    // Containers, as in `vector <int> rooms (9);`
                    mode {
                        begin = """\b(deque|list|queue|priority_queue|pair|stack|vector|map|set|""" +
                            """bitset|multiset|multimap|unordered_map|unordered_set|""" +
                            """unordered_multiset|unordered_multimap|array|tuple|optional|""" +
                            """variant|function|flat_map|flat_set)\s*<(?!<)"""
                        end = ">"
                        keywords = cppKeywords
                        contains = listOf(Mode.SELF, primitiveTypes)
                    },
                    mode {
                        begin = IDENT_RE + "::"
                        keywords = cppKeywords
                    },
                    mode {
                        matchList = listOf(
                            // The extra complexity deals with `enum class` and `enum struct`.
                            """\b(?:enum(?:\s+(?:class|struct))?|class|struct|union)""",
                            """\s+""",
                            """\w+""",
                        )
                        scopes = mapOf(1 to "keyword", 3 to "title.class")
                    },
                )
        },
    )
}

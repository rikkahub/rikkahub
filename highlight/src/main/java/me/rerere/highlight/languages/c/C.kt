package me.rerere.highlight.languages.c

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.IDENT_RE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.endSameAsBegin
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.optional
import me.rerere.highlight.core.variant

/** C, ported from `lib/languages/c.js` of `highlight.js` 11.11.1. */
internal fun c(): Language {
    // A line comment that survives a backslash newline. `C_LINE_COMMENT_MODE` cannot grow that
    // ability without changing it for every grammar that shares it, so C keeps its own.
    val lineComment = comment("//", "${'$'}") {
        contains = listOf(mode { begin = """\\\n""" })
    }
    val declTypeAutoRe = """decltype\(auto\)"""
    val namespaceRe = """[a-zA-Z_]\w*::"""
    val templateArgumentRe = "<[^<>]+>"
    val functionTypeRe = "(" + declTypeAutoRe + "|" + optional(namespaceRe) +
        """[a-zA-Z_]\w*""" + optional(templateArgumentRe) + ")"

    val types = mode {
        scope = "type"
        variants = listOf(
            { begin = """\b[a-z\d_]*_t\b""" },
            { match = """\batomic_[a-z]{3,6}\b""" },
        )
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
            { match = """\b(0b[01']+)""" },
            { match = """(-?)\b([\d']+(\.[\d']*)?|\.[\d']+)((ll|LL|l|L)(u|U)?|(u|U)(ll|LL|l|L)?|f|F|b|B)""" },
            {
                match = """(-?)\b(0[xX][a-fA-F0-9]+(?:'[a-fA-F0-9]+)*""" +
                    """(?:\.[a-fA-F0-9]*(?:'[a-fA-F0-9]*)*)?(?:[pP][-+]?[0-9]+)?(l|L)?(u|U)?)"""
            },
            { match = """(-?)\b\d+(?:'\d+)*(?:\.\d*(?:'\d*)*)?(?:[eE][-+]?\d+)?""" },
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
                    "pragma _Pragma ifdef ifndef elifdef elifndef include"
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

    val cKeywords = listOf(
        "asm", "auto", "break", "case", "continue", "default", "do", "else", "enum", "extern",
        "for", "fortran", "goto", "if", "inline", "register", "restrict", "return", "sizeof",
        "typeof", "typeof_unqual", "struct", "switch", "typedef", "union", "volatile", "while",
        "_Alignas", "_Alignof", "_Atomic", "_Generic", "_Noreturn", "_Static_assert",
        "_Thread_local",
        // Aliases.
        "alignas", "alignof", "noreturn", "static_assert", "thread_local",
        // Not a C keyword, but treated exactly like one for all intents and purposes.
        "_Pragma",
    )

    val cTypes = listOf(
        "float", "double", "signed", "unsigned", "int", "short", "long", "char", "void", "_Bool",
        "_BitInt", "_Complex", "_Imaginary", "_Decimal32", "_Decimal64", "_Decimal96",
        "_Decimal128", "_Decimal64x", "_Decimal128x", "_Float16", "_Float32", "_Float64",
        "_Float128", "_Float32x", "_Float64x", "_Float128x",
        // Modifiers.
        "const", "static", "constexpr",
        // Aliases.
        "complex", "bool", "imaginary",
    )

    val cKeywordSet = keywords {
        keyword(cKeywords)
        type(cTypes)
        literal("true false NULL")
        builtIn(
            "std string wstring cin cout cerr clog stdin stdout stderr stringstream istringstream " +
                "ostringstream auto_ptr deque list queue stack vector map set pair bitset multiset " +
                "multimap unordered_set unordered_map unordered_multiset unordered_multimap " +
                "priority_queue make_pair array shared_ptr abort terminate abs acos asin atan2 " +
                "atan calloc ceil cosh cos exit exp fabs floor fmod fprintf fputs free frexp " +
                "fscanf future isalnum isalpha iscntrl isdigit isgraph islower isprint ispunct " +
                "isspace isupper isxdigit tolower toupper labs ldexp log10 log malloc realloc " +
                "memchr memcmp memcpy memset modf pow printf putchar puts scanf sinh sin snprintf " +
                "sprintf sqrt sscanf strcat strchr strcmp strcpy strcspn strlen strncat strncmp " +
                "strncpy strpbrk strrchr strspn strstr tanh tan vfprintf vprintf vsprintf endl " +
                "initializer_list unique_ptr"
        )
    }

    val expressionContains = listOf(
        preprocessor,
        types,
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
        keywords = cKeywordSet
        contains = expressionContains + mode {
            begin = """\("""
            end = """\)"""
            keywords = cKeywordSet
            contains = expressionContains + Mode.SELF
            relevance = 0.0
        }
        relevance = 0.0
    }

    val functionDeclaration = mode {
        begin = "($functionTypeRe[\\*&\\s]+)+$functionTitle"
        returnBegin = true
        end = """[{;=]"""
        excludeEnd = true
        keywords = cKeywordSet
        illegal = """[^\w\s\*&:<>.]"""
        contains = listOf(
            // Keeps `decltype(auto)` from being read as the function title.
            mode {
                begin = declTypeAutoRe
                keywords = cKeywordSet
                relevance = 0.0
            },
            mode {
                begin = functionTitle
                returnBegin = true
                contains = listOf(titleMode.inherit { scope = "title.function" })
                relevance = 0.0
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
                keywords = cKeywordSet
                relevance = 0.0
                contains = listOf(
                    lineComment,
                    C_BLOCK_COMMENT_MODE,
                    strings,
                    numbers,
                    types,
                    // Count matching parentheses.
                    mode {
                        begin = """\("""
                        end = """\)"""
                        keywords = cKeywordSet
                        relevance = 0.0
                        contains = listOf(
                            Mode.SELF,
                            lineComment,
                            C_BLOCK_COMMENT_MODE,
                            strings,
                            numbers,
                            types,
                        )
                    },
                )
            },
            types,
            lineComment,
            C_BLOCK_COMMENT_MODE,
            preprocessor,
        )
    }

    return Language(
        name = "C",
        // Upstream keeps `c` out of auto detection so that it cannot be confused with `cpp`; we
        // only ever highlight a language the caller named, so there is nothing to disable.
        aliases = setOf("c", "h"),
        root = mode {
            keywords = cKeywordSet
            illegal = "</"
            contains = listOf(expressionContext, functionDeclaration) +
                expressionContains +
                listOf(
                    preprocessor,
                    mode {
                        begin = IDENT_RE + "::"
                        keywords = cKeywordSet
                    },
                    mode {
                        scope = "class"
                        beginKeywords = "enum class struct union"
                        end = """[{;:<>=]"""
                        contains = listOf(
                            mode { beginKeywords = "final class struct" },
                            TITLE_MODE,
                        )
                    },
                )
        },
    )
}

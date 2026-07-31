package me.rerere.highlight.languages.javascript

import me.rerere.highlight.core.APOS_STRING_MODE
import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_BLOCK_COMMENT_MODE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.Keywords
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.MatchData
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.ModeCallback
import me.rerere.highlight.core.QUOTE_STRING_MODE
import me.rerere.highlight.core.REGEXP_MODE
import me.rerere.highlight.core.RE_STARTERS_RE
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.UNDERSCORE_IDENT_RE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.either
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.shebang
import me.rerere.highlight.core.value
import me.rerere.highlight.core.variant

/** `IDENT_RE` of `lib/languages/javascript.js`, which is wider than the shared one. */
internal const val JS_IDENT_RE = "[A-Za-z\$_][0-9A-Za-z\$_]*"

internal val JS_KEYWORDS = listOf(
    "as", // for exports
    "in", "of", "if", "for", "while", "finally", "var", "new", "function", "do", "return", "void",
    "else", "break", "catch", "instanceof", "with", "throw", "case", "default", "try", "switch",
    "continue", "typeof", "delete", "let", "yield", "const", "class",
    // `get` and `set` are handled by a special rule instead.
    "debugger", "async", "await", "static", "import", "from", "export", "extends",
    // Stage 3, "recommended for implementation".
    "using",
)

internal val JS_LITERALS = listOf("true", "false", "null", "undefined", "NaN", "Infinity")

/** https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects */
internal val JS_TYPES = listOf(
    // Fundamental objects.
    "Object", "Function", "Boolean", "Symbol",
    // Numbers and dates.
    "Math", "Date", "Number", "BigInt",
    // Text.
    "String", "RegExp",
    // Indexed collections.
    "Array", "Float32Array", "Float64Array", "Int8Array", "Uint8Array", "Uint8ClampedArray",
    "Int16Array", "Int32Array", "Uint16Array", "Uint32Array", "BigInt64Array", "BigUint64Array",
    // Keyed collections.
    "Set", "Map", "WeakSet", "WeakMap",
    // Structured data.
    "ArrayBuffer", "SharedArrayBuffer", "Atomics", "DataView", "JSON",
    // Control abstraction objects.
    "Promise", "Generator", "GeneratorFunction", "AsyncFunction",
    // Reflection.
    "Reflect", "Proxy",
    // Internationalization.
    "Intl",
    // WebAssembly.
    "WebAssembly",
)

internal val JS_ERROR_TYPES = listOf(
    "Error", "EvalError", "InternalError", "RangeError", "ReferenceError", "SyntaxError",
    "TypeError", "URIError",
)

internal val JS_BUILT_IN_GLOBALS = listOf(
    "setInterval", "setTimeout", "clearInterval", "clearTimeout",
    "require", "exports",
    "eval", "isFinite", "isNaN", "parseFloat", "parseInt", "decodeURI", "decodeURIComponent",
    "encodeURI", "encodeURIComponent", "escape", "unescape",
)

internal val JS_BUILT_IN_VARIABLES = listOf(
    "arguments", "this", "super", "console", "window", "document", "localStorage",
    "sessionStorage", "module",
    "global", // Node.js
)

internal val JS_BUILT_INS = JS_BUILT_IN_GLOBALS + JS_TYPES + JS_ERROR_TYPES

internal fun javascriptKeywords(): Keywords = keywords {
    pattern = JS_IDENT_RE
    keyword(JS_KEYWORDS)
    literal(JS_LITERALS)
    builtIn(JS_BUILT_INS)
    scope("variable.language", JS_BUILT_IN_VARIABLES)
}

/**
 * The JavaScript grammar together with the parts `typescript` reaches into.
 *
 * Upstream hangs those on `language.exports` and mutates them from `typescript.js`; [contains] and
 * [paramsContains] are the very lists the modes were built with, so appending to them here has the
 * same effect as pushing onto the shared arrays there.
 */
internal class JavaScriptGrammar(
    val root: Mode,
    val contains: MutableList<Mode>,
    val paramsContains: MutableList<Mode>,
    val classReference: Mode,
)

/** JavaScript, ported from `lib/languages/javascript.js` of `highlight.js` 11.11.1. */
internal fun javascript(): Language = Language(
    name = "JavaScript",
    aliases = setOf("javascript", "js", "jsx", "mjs", "cjs"),
    root = javascriptGrammar(javascriptKeywords()).root,
)

/**
 * Builds the JavaScript mode tree.
 *
 * Upstream lets `typescript` replace the keyword object in place after the fact; here the keywords
 * are handed in instead, which reaches every mode that shares them just the same.
 */
internal fun javascriptGrammar(jsKeywords: Keywords): JavaScriptGrammar {
    // ---- JSX ----------------------------------------------------------------------------------

    val fragmentBegin = "<>"
    val fragmentEnd = "</>"

    // Kept out of `isTrulyOpeningTag` by matching self closing tags with their own rule.
    val xmlSelfClosing = """<[A-Za-z0-9\\._:-]+\s*/>"""
    val xmlTagBegin = """<[A-Za-z0-9\\._:-]+"""
    val xmlTagEnd = """/[A-Za-z0-9\\._:-]+>|/>"""

    /** Is there a `</Booger` for this `<Booger` further down the source? */
    fun hasClosingTag(match: MatchData, after: Int): Boolean {
        val tag = "</" + match.value.substring(1)
        return match.input.indexOf(tag, after) != -1
    }

    val isTrulyOpeningTag: ModeCallback = callback@{ match, response ->
        val afterMatchIndex = match.value.length + match.index
        val nextChar = match.input.getOrNull(afterMatchIndex)

        // HTML never has a raw `<` inside a tag (`<Array<Array<number>>`), and the `,` of
        // `<T, A extends keyof T, V>` gives away that this is a type argument list.
        if (nextChar == '<' || nextChar == ',') {
            response.ignoreMatch()
            return@callback
        }

        // `<something>` is quite possibly a tag; only treat it as one when it is ever closed.
        // `<blah />` is handled by the self closing rule instead.
        if (nextChar == '>' && !hasClosingTag(match, afterMatchIndex)) {
            response.ignoreMatch()
        }

        val afterMatch = match.input.substring(afterMatchIndex)

        // Some more template typing stuff: `<T = any>(key?: string) => Modify<`.
        if (EQUALS_AHEAD.containsMatchIn(afterMatch)) {
            response.ignoreMatch()
            return@callback
        }

        // `<From extends string>` technically could be HTML, but it smells like a type.
        if (EXTENDS_AHEAD.containsMatchIn(afterMatch)) {
            response.ignoreMatch()
        }
    }

    // ---- numbers ------------------------------------------------------------------------------

    // https://tc39.es/ecma262/#sec-literals-numeric-literals
    val decimalDigits = "[0-9](_?[0-9])*"
    val frac = """\.($decimalDigits)"""

    // DecimalIntegerLiteral, including Annex B NonOctalDecimalIntegerLiteral.
    // https://tc39.es/ecma262/#sec-additional-syntax-numeric-literals
    val decimalInteger = """0|[1-9](_?[0-9])*|0[0-7]*[89][0-9]*"""

    val number = mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            // DecimalLiteral.
            {
                begin = """(\b($decimalInteger)(($frac)|\.)?|($frac))""" +
                    """[eE][+-]?($decimalDigits)\b"""
            },
            { begin = """\b($decimalInteger)\b(($frac)\b|\.)?|($frac)\b""" },
            // DecimalBigIntegerLiteral.
            { begin = """\b(0|[1-9](_?[0-9])*)n\b""" },
            // NonDecimalIntegerLiteral.
            { begin = """\b0[xX][0-9a-fA-F](_?[0-9a-fA-F])*n?\b""" },
            { begin = """\b0[bB][0-1](_?[0-1])*n?\b""" },
            { begin = """\b0[oO][0-7](_?[0-7])*n?\b""" },
            // LegacyOctalIntegerLiteral, which does not allow underscore separators.
            { begin = """\b0[0-7]+n?\b""" },
        )
    }

    // ---- strings and comments -----------------------------------------------------------------

    val subst = mode {
        scope = "subst"
        begin = """\$\{"""
        end = """\}"""
        keywords = jsKeywords
        // `contains` is filled in below, once the template modes exist.
    }

    /** A tagged template literal whose content is handed to [language]. */
    fun taggedTemplate(tag: String, language: String): Mode = mode {
        begin = "$tag`"
        starts = mode {
            end = "`"
            contains = listOf(BACKSLASH_ESCAPE, subst)
            subLanguage = language
        }
    }

    val htmlTemplate = taggedTemplate(".?html", "xml")
    val cssTemplate = taggedTemplate(".?css", "css")
    val graphqlTemplate = taggedTemplate(".?gql", "graphql")

    val templateString = mode {
        scope = "string"
        begin = "`"
        end = "`"
        contains = listOf(BACKSLASH_ESCAPE, subst)
    }

    val jsdocComment = comment("""/\*\*(?!/)""", """\*/""") {
        relevance = 0.0
        contains = listOf(
            mode {
                begin = "(?=@[A-Za-z]+)"
                relevance = 0.0
                contains = listOf(
                    mode {
                        scope = "doctag"
                        begin = "@[A-Za-z]+"
                    },
                    mode {
                        scope = "type"
                        begin = """\{"""
                        end = """\}"""
                        excludeEnd = true
                        excludeBegin = true
                        relevance = 0.0
                    },
                    mode {
                        scope = "variable"
                        begin = JS_IDENT_RE + """(?=\s*(-)|$)"""
                        endsParent = true
                        relevance = 0.0
                    },
                    // Eat spaces, but not newlines, so types or variables can be found.
                    mode {
                        begin = """(?=[^\n])\s"""
                        relevance = 0.0
                    },
                )
            },
        )
    }

    val commentMode = mode {
        scope = "comment"
        variants = listOf(
            variant(jsdocComment),
            variant(C_BLOCK_COMMENT_MODE),
            variant(C_LINE_COMMENT_MODE),
        )
    }

    val substInternals = listOf(
        APOS_STRING_MODE,
        QUOTE_STRING_MODE,
        htmlTemplate,
        cssTemplate,
        graphqlTemplate,
        templateString,
        // Skip numbers when they are part of a variable name.
        mode { match = """\$\d+""" },
        number,
        // `hljs.REGEXP_MODE` is intentionally left out here.
        // See https://github.com/highlightjs/highlight.js/issues/3288
    )

    subst.contains = substInternals + mode {
        // Braces inside a substitution have to be paired up, or the substitution ends too early.
        begin = """\{"""
        end = """\}"""
        keywords = jsKeywords
        contains = listOf(Mode.SELF) + substInternals
    }

    val substAndComments = listOf(commentMode) + subst.contains

    val paramsContains: MutableList<Mode> = (
        substAndComments + mode {
            // Eat recursive parens in sub expressions.
            begin = """(\s*)\("""
            end = """\)"""
            keywords = jsKeywords
            contains = listOf(Mode.SELF) + substAndComments
        }
        ).toMutableList()

    val params = mode {
        scope = "params"
        begin = """(\s*)\("""
        end = """\)"""
        excludeBegin = true
        excludeEnd = true
        keywords = jsKeywords
        contains = paramsContains
    }

    // ---- classes and functions ------------------------------------------------------------------

    // ES6 classes.
    val classOrExtends = mode {
        variants = listOf(
            // class Car extends vehicle
            {
                matchList = listOf(
                    "class",
                    """\s+""",
                    JS_IDENT_RE,
                    """\s+""",
                    "extends",
                    """\s+""",
                    concat(JS_IDENT_RE, "(", concat("""\.""", JS_IDENT_RE), ")*"),
                )
                scopes = mapOf(
                    1 to "keyword",
                    3 to "title.class",
                    5 to "keyword",
                    7 to "title.class.inherited",
                )
            },
            // class Car
            {
                matchList = listOf("class", """\s+""", JS_IDENT_RE)
                scopes = mapOf(1 to "keyword", 3 to "title.class")
            },
        )
    }

    val classReference = mode {
        relevance = 0.0
        match = either(
            // Hard coded exceptions.
            """\bJSON""",
            // Float32Array, OutT
            """\b[A-Z][a-z]+([A-Z][a-z]*|\d)*""",
            // CSSFactory, CSSFactoryT
            """\b[A-Z]{2,}([A-Z][a-z]+|\d)+([A-Z][a-z]*)*""",
            // FPs, FPsT
            """\b[A-Z]{2,}[a-z]+([A-Z][a-z]+|\d)*([A-Z][a-z]*)*""",
            // A single letter is not highlighted, and `BLAH` is flagged as an upper case constant.
        )
        scope = "title.class"
        // The underscore keeps the relevance credit for JS library classes without colouring them.
        keywords = keywords { scope("_", JS_TYPES + JS_ERROR_TYPES) }
    }

    val useStrict = mode {
        label = "use_strict"
        scope = "meta"
        relevance = 10.0
        begin = """^\s*['"]use (strict|asm)['"]"""
    }

    val functionDefinition = mode {
        label = "func.def"
        scopes = mapOf(1 to "keyword", 3 to "title.function")
        contains = listOf(params)
        illegal = "%"
        variants = listOf(
            { matchList = listOf("function", """\s+""", JS_IDENT_RE, """(?=\s*\()""") },
            // Anonymous function.
            { matchList = listOf("function", """\s*(?=\()""") },
        )
    }

    val upperCaseConstant = mode {
        relevance = 0.0
        match = """\b[A-Z][A-Z_0-9]+\b"""
        scope = "variable.constant"
    }

    fun noneOf(list: List<String>): String = concat("(?!", list.joinToString("|"), ")")

    val functionCall = mode {
        match = concat(
            """\b""",
            noneOf((JS_BUILT_IN_GLOBALS + listOf("super", "import")).map { """$it\s*\(""" }),
            JS_IDENT_RE,
            lookahead("""\s*\("""),
        )
        scope = "title.function"
        relevance = 0.0
    }

    val propertyAccess = mode {
        begin = concat("""\.""", lookahead(concat(JS_IDENT_RE, "(?![0-9A-Za-z\$_(])")))
        end = JS_IDENT_RE
        excludeBegin = true
        keywords = keywords("prototype")
        scope = "property"
        relevance = 0.0
    }

    val getterOrSetter = mode {
        matchList = listOf("get|set", """\s+""", JS_IDENT_RE, """(?=\()""")
        scopes = mapOf(1 to "keyword", 3 to "title.function")
        contains = listOf(
            // Eat `()` to avoid empty params.
            mode { begin = """\(\)""" },
            params,
        )
    }

    // The parens have to be counted, so that the `( )` really is the one bounding the `=>`. There
    // could be any number of sub expressions surrounded by parens inside.
    val funcLeadInRe = """(\([^()]*(\([^()]*(\([^()]*\)[^()]*)*\)[^()]*)*\)|""" +
        UNDERSCORE_IDENT_RE + """)\s*=>"""

    val functionVariable = mode {
        matchList = listOf(
            "const|var|let", """\s+""",
            JS_IDENT_RE, """\s*""",
            """=\s*""",
            """(async\s*)?""", // `async` is optional
            lookahead(funcLeadInRe),
        )
        keywords = keywords("async")
        scopes = mapOf(1 to "keyword", 3 to "title.function")
        contains = listOf(params)
    }

    // ---- root ---------------------------------------------------------------------------------

    val valueContainer = mode {
        begin = "(" + RE_STARTERS_RE + """|\b(case|return|throw)\b)\s*"""
        keywords = keywords("return throw case")
        relevance = 0.0
        contains = listOf(
            commentMode,
            REGEXP_MODE,
            mode {
                scope = "function"
                begin = funcLeadInRe
                returnBegin = true
                end = """\s*=>"""
                contains = listOf(
                    mode {
                        scope = "params"
                        variants = listOf(
                            {
                                begin = UNDERSCORE_IDENT_RE
                                relevance = 0.0
                            },
                            {
                                scope = null
                                begin = """\(\s*\)"""
                                skip = true
                            },
                            {
                                begin = """(\s*)\("""
                                end = """\)"""
                                excludeBegin = true
                                excludeEnd = true
                                keywords = jsKeywords
                                contains = paramsContains
                            },
                        )
                    },
                )
            },
            // Could be a comma delimited list of params to a function call.
            mode {
                begin = ","
                relevance = 0.0
            },
            mode {
                match = """\s+"""
                relevance = 0.0
            },
            // JSX.
            mode {
                variants = listOf(
                    {
                        begin = fragmentBegin
                        end = fragmentEnd
                    },
                    { match = xmlSelfClosing },
                    {
                        // The opening tag is checked carefully, as it may be a false positive.
                        begin = xmlTagBegin
                        onBegin = isTrulyOpeningTag
                        end = xmlTagEnd
                    },
                )
                subLanguage = "xml"
                contains = listOf(
                    mode {
                        begin = xmlTagBegin
                        end = xmlTagEnd
                        skip = true
                        contains = listOf(Mode.SELF)
                    },
                )
            },
        )
    }

    val contains: MutableList<Mode> = mutableListOf(
        shebang(binary = "node") {
            label = "shebang"
            relevance = 5.0
        },
        useStrict,
        APOS_STRING_MODE,
        QUOTE_STRING_MODE,
        htmlTemplate,
        cssTemplate,
        graphqlTemplate,
        templateString,
        commentMode,
        // Skip numbers when they are part of a variable name.
        mode { match = """\$\d+""" },
        number,
        classReference,
        mode {
            scope = "attr"
            match = JS_IDENT_RE + lookahead(":")
            relevance = 0.0
        },
        functionVariable,
        valueContainer,
        functionDefinition,
        // Prevent these from being swallowed by the function rules, as they look function like.
        mode { beginKeywords = "while if switch catch for" },
        mode {
            // Again the parens are counted so that the `( )` really is the bounding one.
            label = "func.def"
            begin = """\b(?!function)""" + UNDERSCORE_IDENT_RE +
                """\([^()]*(\([^()]*(\([^()]*\)[^()]*)*\)[^()]*)*\)\s*\{"""
            returnBegin = true
            contains = listOf(
                params,
                TITLE_MODE.inherit {
                    begin = JS_IDENT_RE
                    scope = "title.function"
                },
            )
        },
        // Catch `...` so it does not trigger the property rule below.
        mode {
            match = """\.\.\."""
            relevance = 0.0
        },
        propertyAccess,
        // Prevents detection of keywords in `.keyword()` and `$keyword = x`.
        mode {
            match = "\\$" + JS_IDENT_RE
            relevance = 0.0
        },
        mode {
            matchList = listOf("""\bconstructor(?=\s*\()""")
            scopes = mapOf(1 to "title.function")
            contains = listOf(params)
        },
        functionCall,
        upperCaseConstant,
        classOrExtends,
        getterOrSetter,
        // Relevance booster for a pattern common to JS libraries: `$(something)` and `$.something`.
        mode { match = """\$[(.]""" },
    )

    val root = mode {
        keywords = jsKeywords
        illegal = "#(?![\$_A-z])"
        this.contains = contains
    }

    return JavaScriptGrammar(
        root = root,
        contains = contains,
        paramsContains = paramsContains,
        classReference = classReference,
    )
}

private val EQUALS_AHEAD = Regex("""^\s*=""")

private val EXTENDS_AHEAD = Regex("""^\s+extends\s+""")

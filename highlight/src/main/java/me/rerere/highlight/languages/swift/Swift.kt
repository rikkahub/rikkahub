package me.rerere.highlight.languages.swift

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.C_LINE_COMMENT_MODE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.either
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.lookahead
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.variant

/** Swift, ported from `lib/languages/swift.js` of `highlight.js` 11.11.1. */
internal fun swift(): Language {
    val whitespace = mode {
        match = """\s+"""
        relevance = 0.0
    }
    val blockComment = comment("""/\*""", """\*/""") { contains = listOf(Mode.SELF) }
    val comments = listOf(C_LINE_COMMENT_MODE, blockComment)

    val dotKeyword = mode {
        matchList = listOf("""\.""", either(DOT_KEYWORDS + OPTIONAL_DOT_KEYWORDS))
        scopes = mapOf(2 to "keyword")
    }
    val keywordGuard = mode {
        match = concat("""\.""", either(ALL_KEYWORD_SOURCES))
        relevance = 0.0
    }
    val keywordMode = mode {
        variants = listOf(
            {
                scope = "keyword"
                match = either(REGEX_KEYWORDS.map(::keywordWrapper) + OPTIONAL_DOT_KEYWORDS)
            },
        )
    }
    val swiftKeywords = keywords {
        pattern = either("""\b\w+""", """#\w+""")
        keyword(PLAIN_KEYWORDS + "_|0" + NUMBER_SIGN_KEYWORDS)
        literal(LITERALS)
    }
    val keywordModes = listOf(dotKeyword, keywordGuard, keywordMode)

    val builtInGuard = mode {
        match = concat("""\.""", either(BUILT_INS))
        relevance = 0.0
    }
    val builtIn = mode {
        scope = "built_in"
        match = concat("""\b""", either(BUILT_INS), """(?=\()""")
    }
    val builtIns = listOf(builtInGuard, builtIn)

    val operatorGuard = mode {
        match = "->"
        relevance = 0.0
    }
    val operatorMode = mode {
        scope = "operator"
        relevance = 0.0
        variants = listOf(
            { match = OPERATOR },
            { match = """\.(\.|$OPERATOR_CHARACTER)+""" },
        )
    }
    val operators = listOf(operatorGuard, operatorMode)

    val decimalDigits = "([0-9]_*)+"
    val hexDigits = "([0-9a-fA-F]_*)+"
    val number = mode {
        scope = "number"
        relevance = 0.0
        variants = listOf(
            {
                match = """\b($decimalDigits)(\.($decimalDigits))?""" +
                    """([eE][+-]?($decimalDigits))?\b"""
            },
            {
                match = """\b0x($hexDigits)(\.($hexDigits))?""" +
                    """([pP][+-]?($decimalDigits))?\b"""
            },
            { match = """\b0o([0-7]_*)+\b""" },
            { match = """\b0b([01]_*)+\b""" },
        )
    }

    fun escapedCharacter(rawDelimiter: String = "") = mode {
        scope = "subst"
        variants = listOf(
            { match = concat("""\\""", rawDelimiter, """[0\\tnr"']""") },
            { match = concat("""\\""", rawDelimiter, """u\{[0-9a-fA-F]{1,8}\}""") },
        )
    }

    fun escapedNewline(rawDelimiter: String = "") = mode {
        scope = "subst"
        match = concat("""\\""", rawDelimiter, """[\t ]*(?:[\r\n]|\r\n)""")
    }

    fun interpolation(rawDelimiter: String = "") = mode {
        scope = "subst"
        label = "interpol"
        begin = concat("""\\""", rawDelimiter, """\(""")
        end = """\)"""
    }

    fun multilineString(rawDelimiter: String = "") = mode {
        begin = concat(rawDelimiter, "\"\"\"")
        end = concat("\"\"\"", rawDelimiter)
        contains = listOf(
            escapedCharacter(rawDelimiter),
            escapedNewline(rawDelimiter),
            interpolation(rawDelimiter),
        )
    }

    fun singleLineString(rawDelimiter: String = "") = mode {
        begin = concat(rawDelimiter, "\"")
        end = concat("\"", rawDelimiter)
        contains = listOf(
            escapedCharacter(rawDelimiter),
            interpolation(rawDelimiter),
        )
    }

    val stringVariants = listOf(
        multilineString(),
        multilineString("#"),
        multilineString("##"),
        multilineString("###"),
        singleLineString(),
        singleLineString("#"),
        singleLineString("##"),
        singleLineString("###"),
    )
    val string = mode {
        scope = "string"
        variants = stringVariants.map(::variant)
    }

    val regexpContents = listOf(
        BACKSLASH_ESCAPE,
        mode {
            begin = """\["""
            end = """\]"""
            relevance = 0.0
            contains = listOf(BACKSLASH_ESCAPE)
        },
    )
    val bareRegexpLiteral = mode {
        begin = """/[^\s](?=[^/\n]*/)"""
        end = "/"
        contains = regexpContents
    }

    fun extendedRegexpLiteral(rawDelimiter: String): Mode {
        val endPattern = concat("/", rawDelimiter)
        return mode {
            begin = concat(rawDelimiter, "/")
            end = endPattern
            contains = regexpContents + mode {
                scope = "comment"
                begin = "#(?!.*$endPattern)"
                end = "$"
            }
        }
    }

    val regexp = mode {
        scope = "regexp"
        variants = listOf(
            variant(extendedRegexpLiteral("###")),
            variant(extendedRegexpLiteral("##")),
            variant(extendedRegexpLiteral("#")),
            variant(bareRegexpLiteral),
        )
    }

    val quotedIdentifier = mode { match = concat("`", IDENTIFIER, "`") }
    val implicitParameter = mode {
        scope = "variable"
        match = """\${'$'}\d+"""
    }
    val propertyWrapperProjection = mode {
        scope = "variable"
        match = concat("""\${'$'}""", OPERATOR_IDENTIFIER_CHARACTER, "+")
    }
    val identifiers = listOf(quotedIdentifier, implicitParameter, propertyWrapperProjection)

    val availableAttribute = mode {
        match = """(@|#(un)?)available"""
        scope = "keyword"
        starts = mode {
            contains = listOf(
                mode {
                    begin = """\("""
                    end = """\)"""
                    keywords = keywords(AVAILABILITY_KEYWORDS)
                    contains = operators + number + string
                },
            )
        }
    }
    val keywordAttribute = mode {
        scope = "keyword"
        match = concat(
            "@",
            either(KEYWORD_ATTRIBUTES),
            lookahead(either("""\(""", """\s+""")),
        )
    }
    val userDefinedAttribute = mode {
        scope = "meta"
        match = concat("@", IDENTIFIER)
    }
    val attributes = listOf(availableAttribute, keywordAttribute, userDefinedAttribute)

    val type = mode {
        match = lookahead("""\b[A-Z]""")
        relevance = 0.0
        contains = listOf(
            mode {
                scope = "type"
                match = concat(
                    """(AV|CA|CF|CG|CI|CL|CM|CN|CT|MK|MP|MTK|MTL|NS|SCN|SK|UI|WK|XC)""",
                    OPERATOR_IDENTIFIER_CHARACTER,
                    "+",
                )
            },
            mode {
                scope = "type"
                match = TYPE_IDENTIFIER
                relevance = 0.0
            },
            mode {
                match = """[?!]+"""
                relevance = 0.0
            },
            mode {
                match = """\.\.\."""
                relevance = 0.0
            },
            mode {
                match = concat("""\s+&\s+""", lookahead(TYPE_IDENTIFIER))
                relevance = 0.0
            },
        )
    }
    val genericArguments = mode {
        begin = "<"
        end = ">"
        keywords = swiftKeywords
        contains = comments + keywordModes + attributes + operatorGuard + type
    }
    type.contains = type.contains + genericArguments

    val tupleElementName = mode {
        match = concat(IDENTIFIER, """\s*:""")
        keywords = keywords("_|0")
        relevance = 0.0
    }
    val tuple = mode {
        begin = """\("""
        end = """\)"""
        relevance = 0.0
        keywords = swiftKeywords
        contains = listOf(Mode.SELF, tupleElementName) +
            comments + regexp + keywordModes + builtIns + operators + number + string +
            identifiers + attributes + type
    }

    val genericParameters = mode {
        begin = "<"
        end = ">"
        keywords = keywords("repeat each")
        contains = comments + type
    }
    val functionParameterName = mode {
        begin = either(
            lookahead(concat(IDENTIFIER, """\s*:""")),
            lookahead(concat(IDENTIFIER, """\s+""", IDENTIFIER, """\s*:""")),
        )
        end = ":"
        relevance = 0.0
        contains = listOf(
            mode {
                scope = "keyword"
                match = """\b_\b"""
            },
            mode {
                scope = "params"
                match = IDENTIFIER
            },
        )
    }
    val functionParameters = mode {
        begin = """\("""
        end = """\)"""
        keywords = swiftKeywords
        contains = listOf(functionParameterName) + comments + keywordModes + operators +
            number + string + attributes + type + tuple
        endsParent = true
        illegal = """["']"""
    }
    val functionOrMacro = mode {
        matchList = listOf(
            """(func|macro)""",
            """\s+""",
            either(quotedIdentifier.match!!, IDENTIFIER, OPERATOR),
        )
        scopes = mapOf(
            1 to "keyword",
            3 to "title.function",
        )
        contains = listOf(genericParameters, functionParameters, whitespace)
        illegalList = listOf("""\[""", "%")
    }
    val initSubscript = mode {
        matchList = listOf(
            """\b(?:subscript|init[?!]?)""",
            """\s*(?=[<(])""",
        )
        scopes = mapOf(1 to "keyword")
        contains = listOf(genericParameters, functionParameters, whitespace)
        illegal = """\[|%"""
    }
    val operatorDeclaration = mode {
        matchList = listOf("operator", """\s+""", OPERATOR)
        scopes = mapOf(
            1 to "keyword",
            3 to "title",
        )
    }
    val precedenceGroup = mode {
        beginList = listOf("precedencegroup", """\s+""", TYPE_IDENTIFIER)
        beginScopes = mapOf(
            1 to "keyword",
            3 to "title",
        )
        contains = listOf(type)
        keywords = keywords(PRECEDENCE_GROUP_KEYWORDS + LITERALS)
        end = """\}"""
    }
    val classFunctionDeclaration = mode {
        matchList = listOf(
            """class\b""",
            """\s+""",
            """func\b""",
            """\s+""",
            """\b[A-Za-z_][A-Za-z0-9_]*\b""",
        )
        scopes = mapOf(
            1 to "keyword",
            3 to "keyword",
            5 to "title.function",
        )
    }
    val classVariableDeclaration = mode {
        matchList = listOf("""class\b""", """\s+""", """var\b""")
        scopes = mapOf(
            1 to "keyword",
            3 to "keyword",
        )
    }
    val typeDeclaration = mode {
        beginList = listOf(
            """(struct|protocol|class|extension|enum|actor)""",
            """\s+""",
            IDENTIFIER,
            """\s*""",
        )
        beginScopes = mapOf(
            1 to "keyword",
            3 to "title.class",
        )
        keywords = swiftKeywords
        contains = listOf(genericParameters) + keywordModes + mode {
            begin = ":"
            end = """\{"""
            keywords = swiftKeywords
            contains = listOf(
                mode {
                    scope = "title.class.inherited"
                    match = TYPE_IDENTIFIER
                },
            ) + keywordModes
            relevance = 0.0
        }
    }

    // String interpolation can contain Swift expressions and nested parentheses.
    stringVariants.forEach { stringVariant ->
        val interpol = stringVariant.contains.first { it.label == "interpol" }
        interpol.keywords = swiftKeywords
        val submodes = keywordModes + builtIns + operators + number + string + identifiers
        interpol.contains = submodes + mode {
            begin = """\("""
            end = """\)"""
            contains = listOf(Mode.SELF) + submodes
        }
    }

    return Language(
        name = "Swift",
        aliases = setOf("swift"),
        root = mode {
            keywords = swiftKeywords
            contains = comments +
                functionOrMacro +
                initSubscript +
                classFunctionDeclaration +
                classVariableDeclaration +
                typeDeclaration +
                operatorDeclaration +
                precedenceGroup +
                mode {
                    beginKeywords = "import"
                    end = "$"
                    contains = comments
                    relevance = 0.0
                } +
                regexp +
                keywordModes +
                builtIns +
                operators +
                number +
                string +
                identifiers +
                attributes +
                type +
                tuple
        },
    )
}

private fun keywordWrapper(keyword: String): String =
    concat("""\b""", keyword, if (Regex("""\w$""").containsMatchIn(keyword)) """\b""" else """\B""")

private val DOT_KEYWORDS = listOf("Protocol", "Type").map(::keywordWrapper)
private val OPTIONAL_DOT_KEYWORDS = listOf("init", "self").map(::keywordWrapper)
private val KEYWORD_TYPES = listOf("Any", "Self")

private val PLAIN_KEYWORDS = listOf(
    "actor", "any", "associatedtype", "async", "await", "as", "borrowing", "break", "case",
    "catch", "class", "consume", "consuming", "continue", "convenience", "copy", "default",
    "defer", "deinit", "didSet", "distributed", "do", "dynamic", "each", "else", "enum",
    "extension", "fallthrough", "fileprivate", "final", "for", "func", "get", "guard", "if",
    "import", "indirect", "infix", "inout", "internal", "in", "is", "isolated", "nonisolated",
    "lazy", "let", "macro", "mutating", "nonmutating", "open", "operator", "optional", "override",
    "package", "postfix", "precedencegroup", "prefix", "private", "protocol", "public", "repeat",
    "required", "rethrows", "return", "set", "some", "static", "struct", "subscript", "super",
    "switch", "throws", "throw", "try", "typealias", "unowned", "var", "weak", "where", "while",
    "willSet",
)

private val REGEX_KEYWORDS = listOf(
    """as\?""", "as!", """fileprivate\(set\)""", """init\?""", "init!",
    """internal\(set\)""", """open\(set\)""", """private\(set\)""", """public\(set\)""",
    """try\?""", "try!", """unowned\(safe\)""", """unowned\(unsafe\)""",
) + KEYWORD_TYPES

private val ALL_KEYWORD_SOURCES = PLAIN_KEYWORDS + REGEX_KEYWORDS
private val LITERALS = listOf("false", "nil", "true")
private val PRECEDENCE_GROUP_KEYWORDS = listOf(
    "assignment", "associativity", "higherThan", "left", "lowerThan", "none", "right",
)
private val NUMBER_SIGN_KEYWORDS = listOf(
    "#colorLiteral", "#column", "#dsohandle", "#else", "#elseif", "#endif", "#error", "#file",
    "#fileID", "#fileLiteral", "#filePath", "#function", "#if", "#imageLiteral", "#keyPath",
    "#line", "#selector", "#sourceLocation", "#warning",
)
private val BUILT_INS = listOf(
    "abs", "all", "any", "assert", "assertionFailure", "debugPrint", "dump", "fatalError",
    "getVaList", "isKnownUniquelyReferenced", "max", "min", "numericCast", "pointwiseMax",
    "pointwiseMin", "precondition", "preconditionFailure", "print", "readLine", "repeatElement",
    "sequence", "stride", "swap", "swift_unboxFromSwiftValueWithType", "transcode", "type",
    "unsafeBitCast", "unsafeDowncast", "withExtendedLifetime", "withUnsafeMutablePointer",
    "withUnsafePointer", "withVaList", "withoutActuallyEscaping", "zip",
)

private val OPERATOR_HEAD = either(
    """[/=\-+!*%<>&|^~?]""",
    """[\u00A1-\u00A7]""",
    """[\u00A9\u00AB]""",
    """[\u00AC\u00AE]""",
    """[\u00B0\u00B1]""",
    """[\u00B6\u00BB\u00BF\u00D7\u00F7]""",
    """[\u2016-\u2017]""",
    """[\u2020-\u2027]""",
    """[\u2030-\u203E]""",
    """[\u2041-\u2053]""",
    """[\u2055-\u205E]""",
    """[\u2190-\u23FF]""",
    """[\u2500-\u2775]""",
    """[\u2794-\u2BFF]""",
    """[\u2E00-\u2E7F]""",
    """[\u3001-\u3003]""",
    """[\u3008-\u3020]""",
    """[\u3030]""",
)
private val OPERATOR_CHARACTER = either(
    OPERATOR_HEAD,
    """[\u0300-\u036F]""",
    """[\u1DC0-\u1DFF]""",
    """[\u20D0-\u20FF]""",
    """[\uFE00-\uFE0F]""",
    """[\uFE20-\uFE2F]""",
)
private val OPERATOR = concat(OPERATOR_HEAD, OPERATOR_CHARACTER, "*")

private val IDENTIFIER_HEAD = either(
    """[a-zA-Z_]""",
    """[\u00A8\u00AA\u00AD\u00AF\u00B2-\u00B5\u00B7-\u00BA]""",
    """[\u00BC-\u00BE\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u00FF]""",
    """[\u0100-\u02FF\u0370-\u167F\u1681-\u180D\u180F-\u1DBF]""",
    """[\u1E00-\u1FFF]""",
    """[\u200B-\u200D\u202A-\u202E\u203F-\u2040\u2054\u2060-\u206F]""",
    """[\u2070-\u20CF\u2100-\u218F\u2460-\u24FF\u2776-\u2793]""",
    """[\u2C00-\u2DFF\u2E80-\u2FFF]""",
    """[\u3004-\u3007\u3021-\u302F\u3031-\u303F\u3040-\uD7FF]""",
    """[\uF900-\uFD3D\uFD40-\uFDCF\uFDF0-\uFE1F\uFE30-\uFE44]""",
    """[\uFE47-\uFEFE\uFF00-\uFFFD]""",
)
private val OPERATOR_IDENTIFIER_CHARACTER = either(
    IDENTIFIER_HEAD,
    """\d""",
    """[\u0300-\u036F\u1DC0-\u1DFF\u20D0-\u20FF\uFE20-\uFE2F]""",
)
private val IDENTIFIER = concat(IDENTIFIER_HEAD, OPERATOR_IDENTIFIER_CHARACTER, "*")
private val TYPE_IDENTIFIER = concat("[A-Z]", OPERATOR_IDENTIFIER_CHARACTER, "*")

private val KEYWORD_ATTRIBUTES = listOf(
    "attached",
    "autoclosure",
    concat("""convention\(""", either("swift", "block", "c"), """\)"""),
    "discardableResult",
    "dynamicCallable",
    "dynamicMemberLookup",
    "escaping",
    "freestanding",
    "frozen",
    "GKInspectable",
    "IBAction",
    "IBDesignable",
    "IBInspectable",
    "IBOutlet",
    "IBSegueAction",
    "inlinable",
    "main",
    "nonobjc",
    "NSApplicationMain",
    "NSCopying",
    "NSManaged",
    concat("""objc\(""", IDENTIFIER, """\)"""),
    "objc",
    "objcMembers",
    "propertyWrapper",
    "requires_stored_property_inits",
    "resultBuilder",
    "Sendable",
    "testable",
    "UIApplicationMain",
    "unchecked",
    "unknown",
    "usableFromInline",
    "warn_unqualified_access",
)
private val AVAILABILITY_KEYWORDS = listOf(
    "iOS", "iOSApplicationExtension", "macOS", "macOSApplicationExtension", "macCatalyst",
    "macCatalystApplicationExtension", "watchOS", "watchOSApplicationExtension", "tvOS",
    "tvOSApplicationExtension", "swift",
)

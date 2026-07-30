package me.rerere.highlight.languages.latex

import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.either
import me.rerere.highlight.core.endSameAsBegin
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.variant

/** LaTeX, ported from `lib/languages/latex.js` of `highlight.js` 11.11.1. */
internal fun latex(): Language {
    val knownControlWords = either(
        listOf(
            "(?:NeedsTeXFormat|RequirePackage|GetIdInfo)",
            "Provides(?:Expl)?(?:Package|Class|File)",
            "(?:DeclareOption|ProcessOptions)",
            "(?:documentclass|usepackage|input|include)",
            "makeat(?:letter|other)",
            "ExplSyntax(?:On|Off)",
            "(?:new|renew|provide)?command",
            "(?:re)newenvironment",
            "(?:New|Renew|Provide|Declare)(?:Expandable)?DocumentCommand",
            "(?:New|Renew|Provide|Declare)DocumentEnvironment",
            "(?:(?:e|g|x)?def|let)",
            "(?:begin|end)",
            "(?:part|chapter|(?:sub){0,2}section|(?:sub)?paragraph)",
            "caption",
            "(?:label|(?:eq|page|name)?ref|(?:paren|foot|super)?cite)",
            "(?:alpha|beta|[Gg]amma|[Dd]elta|(?:var)?epsilon|zeta|eta|[Tt]heta|vartheta)",
            "(?:iota|(?:var)?kappa|[Ll]ambda|mu|nu|[Xx]i|[Pp]i|varpi|(?:var)rho)",
            "(?:[Ss]igma|varsigma|tau|[Uu]psilon|[Pp]hi|varphi|chi|[Pp]si|[Oo]mega)",
            "(?:frac|sum|prod|lim|infty|times|sqrt|leq|geq|left|right|middle|[bB]igg?)",
            "(?:[lr]angle|q?quad|[lcvdi]?dots|d?dot|hat|tilde|bar)",
        ).map { "$it(?![a-zA-Z@:_])" },
    )
    val latex3Regex = listOf(
        "(?:__)?[a-zA-Z]{2,}_[a-zA-Z](?:_?[a-zA-Z])+:[a-zA-Z]*",
        "[lgc]__?[a-zA-Z](?:_?[a-zA-Z])*_[a-zA-Z]{2,}",
        "[qs]__?[a-zA-Z](?:_?[a-zA-Z])+",
        "use(?:_i)?:[a-zA-Z]*",
        "(?:else|fi|or):",
        "(?:if|cs|exp):w",
        "(?:hbox|vbox):n",
        "::[a-zA-Z]_unbraced",
        "::[a-zA-Z:]",
    ).joinToString("|") { "$it(?![a-zA-Z:_])" }
    val latex2Variants = listOf(
        mode { begin = "[a-zA-Z@]+" },
        mode { begin = "[^a-zA-Z@]?" },
    )
    val doubleCaretVariants = listOf(
        mode { begin = """\^{6}[0-9a-f]{6}""" },
        mode { begin = """\^{5}[0-9a-f]{5}""" },
        mode { begin = """\^{4}[0-9a-f]{4}""" },
        mode { begin = """\^{3}[0-9a-f]{3}""" },
        mode { begin = """\^{2}[0-9a-f]{2}""" },
        mode { begin = """\^{2}[\u0000-\u007f]""" },
    )
    val controlSequence = mode {
        scope = "keyword"
        begin = """\\"""
        relevance = 0.0
        contains = listOf(
            mode {
                endsParent = true
                begin = knownControlWords
            },
            mode {
                endsParent = true
                begin = latex3Regex
            },
            mode {
                endsParent = true
                variants = doubleCaretVariants.map(::variant)
            },
            mode {
                endsParent = true
                relevance = 0.0
                variants = latex2Variants.map(::variant)
            },
        )
    }
    val macroParam = mode {
        scope = "params"
        relevance = 0.0
        begin = """#+\d?"""
    }
    val doubleCaretChar = mode {
        variants = doubleCaretVariants.map(::variant)
    }
    val specialCatcode = mode {
        scope = "built_in"
        relevance = 0.0
        begin = """[${'$'}&^_]"""
    }
    val magicComment = mode {
        scope = "meta"
        begin = """% ?!(T[eE]X|tex|BIB|bib)"""
        end = "$"
        relevance = 10.0
    }
    val commentMode = comment("%", "$") { relevance = 0.0 }
    val everythingButVerbatim = listOf(
        controlSequence,
        macroParam,
        doubleCaretChar,
        specialCatcode,
        magicComment,
        commentMode,
    )
    val braceGroupNoVerbatim = mode {
        begin = """\{"""
        end = """\}"""
        relevance = 0.0
        contains = listOf(Mode.SELF) + everythingButVerbatim
    }
    val argumentBraces = braceGroupNoVerbatim.inherit {
        relevance = 0.0
        endsParent = true
        contains = listOf(braceGroupNoVerbatim) + everythingButVerbatim
    }
    val argumentBrackets = mode {
        begin = """\["""
        end = """\]"""
        endsParent = true
        relevance = 0.0
        contains = listOf(braceGroupNoVerbatim) + everythingButVerbatim
    }
    val spaceGobbler = mode {
        begin = """\s+"""
        relevance = 0.0
    }
    val argumentM = listOf(argumentBraces)
    val argumentO = listOf(argumentBrackets)

    fun argumentAndThen(arguments: List<Mode>, startsMode: Mode): Mode = mode {
        contains = listOf(spaceGobbler)
        starts = mode {
            relevance = 0.0
            contains = arguments
            starts = startsMode
        }
    }

    fun csName(name: String, startsMode: Mode): Mode = mode {
        begin = """\\$name(?![a-zA-Z@:_])"""
        keywords = keywords {
            pattern = """\\[a-zA-Z]+"""
            keyword(listOf("\\$name"))
        }
        relevance = 0.0
        contains = listOf(spaceGobbler)
        starts = startsMode
    }

    fun beginEnvironment(name: String, startsMode: Mode): Mode {
        val argumentMode = argumentAndThen(argumentM, startsMode)
        return mode {
            begin = """\\begin(?=[ \t]*(\r?\n[ \t]*)?\{$name\})"""
            keywords = keywords {
                pattern = """\\[a-zA-Z]+"""
                keyword(listOf("\\begin"))
            }
            relevance = 0.0
            contains = argumentMode.contains
            starts = argumentMode.starts
        }
    }

    fun verbatimDelimitedEqual(innerName: String = "string"): Mode = mode {
        scope = innerName
        begin = """(.|\r?\n)"""
        end = """(.|\r?\n)"""
        excludeBegin = true
        excludeEnd = true
        endsParent = true
    }.endSameAsBegin()

    fun verbatimDelimitedEnvironment(name: String): Mode = mode {
        scope = "string"
        end = """(?=\\end\{$name\})"""
    }

    fun verbatimDelimitedBraces(innerName: String = "string"): Mode = mode {
        relevance = 0.0
        begin = """\{"""
        starts = mode {
            endsParent = true
            contains = listOf(
                mode {
                    scope = innerName
                    end = """(?=\})"""
                    endsParent = true
                    contains = listOf(
                        mode {
                            begin = """\{"""
                            end = """\}"""
                            relevance = 0.0
                            contains = listOf(Mode.SELF)
                        },
                    )
                },
            )
        }
    }

    val verbatim = buildList {
        listOf("verb", "lstinline").forEach { name ->
            add(csName(name, mode { contains = listOf(verbatimDelimitedEqual()) }))
        }
        add(
            csName(
                "mint",
                argumentAndThen(
                    argumentM,
                    mode { contains = listOf(verbatimDelimitedEqual()) },
                ),
            ),
        )
        add(
            csName(
                "mintinline",
                argumentAndThen(
                    argumentM,
                    mode {
                        contains = listOf(
                            verbatimDelimitedBraces(),
                            verbatimDelimitedEqual(),
                        )
                    },
                ),
            ),
        )
        add(
            csName(
                "url",
                mode {
                    contains = listOf(
                        verbatimDelimitedBraces("link"),
                        verbatimDelimitedBraces("link"),
                    )
                },
            ),
        )
        add(
            csName(
                "hyperref",
                mode { contains = listOf(verbatimDelimitedBraces("link")) },
            ),
        )
        add(
            csName(
                "href",
                argumentAndThen(
                    argumentO,
                    mode { contains = listOf(verbatimDelimitedBraces("link")) },
                ),
            ),
        )
        listOf("", """\*""").forEach { suffix ->
            add(
                beginEnvironment(
                    "verbatim$suffix",
                    verbatimDelimitedEnvironment("verbatim$suffix"),
                ),
            )
            add(
                beginEnvironment(
                    "filecontents$suffix",
                    argumentAndThen(
                        argumentM,
                        verbatimDelimitedEnvironment("filecontents$suffix"),
                    ),
                ),
            )
            listOf("", "B", "L").forEach { prefix ->
                val name = "${prefix}Verbatim$suffix"
                add(
                    beginEnvironment(
                        name,
                        argumentAndThen(
                            argumentO,
                            verbatimDelimitedEnvironment(name),
                        ),
                    ),
                )
            }
        }
        add(
            beginEnvironment(
                "minted",
                argumentAndThen(
                    argumentO,
                    argumentAndThen(
                        argumentM,
                        verbatimDelimitedEnvironment("minted"),
                    ),
                ),
            ),
        )
    }

    return Language(
        name = "LaTeX",
        aliases = setOf("latex", "tex"),
        root = mode {
            contains = verbatim + everythingButVerbatim
        },
    )
}

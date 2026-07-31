package me.rerere.highlight.languages.powershell

import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.NUMBER_MODE
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode

/** PowerShell, ported from `lib/languages/powershell.js` of `highlight.js` 11.11.1. */
internal fun powershell(): Language {
    val types = listOf(
        "string", "char", "byte", "int", "long", "bool", "decimal", "single", "double", "DateTime",
        "xml", "array", "hashtable", "void",
    )

    // https://docs.microsoft.com/en-us/powershell/scripting/developer/cmdlet/approved-verbs-for-windows-powershell-commands
    val validVerbs =
        "Add|Clear|Close|Copy|Enter|Exit|Find|Format|Get|Hide|Join|Lock|" +
            "Move|New|Open|Optimize|Pop|Push|Redo|Remove|Rename|Reset|Resize|" +
            "Search|Select|Set|Show|Skip|Split|Step|Switch|Undo|Unlock|" +
            "Watch|Backup|Checkpoint|Compare|Compress|Convert|ConvertFrom|" +
            "ConvertTo|Dismount|Edit|Expand|Export|Group|Import|Initialize|" +
            "Limit|Merge|Mount|Out|Publish|Restore|Save|Sync|Unpublish|Update|" +
            "Approve|Assert|Build|Complete|Confirm|Deny|Deploy|Disable|Enable|Install|Invoke|" +
            "Register|Request|Restart|Resume|Start|Stop|Submit|Suspend|Uninstall|" +
            "Unregister|Wait|Debug|Measure|Ping|Repair|Resolve|Test|Trace|Connect|" +
            "Disconnect|Read|Receive|Send|Write|Block|Grant|Protect|Revoke|Unblock|" +
            "Unprotect|Use|ForEach|Sort|Tee|Where"

    val comparisonOperators =
        "-and|-as|-band|-bnot|-bor|-bxor|-casesensitive|-ccontains|-ceq|-cge|-cgt|" +
            "-cle|-clike|-clt|-cmatch|-cne|-cnotcontains|-cnotlike|-cnotmatch|-contains|" +
            "-creplace|-csplit|-eq|-exact|-f|-file|-ge|-gt|-icontains|-ieq|-ige|-igt|" +
            "-ile|-ilike|-ilt|-imatch|-in|-ine|-inotcontains|-inotlike|-inotmatch|" +
            "-ireplace|-is|-isnot|-isplit|-join|-le|-like|-lt|-match|-ne|-not|" +
            "-notcontains|-notin|-notlike|-notmatch|-or|-regex|-replace|-shl|-shr|" +
            "-split|-wildcard|-xor"

    val keywordWords =
        "if else foreach return do while until elseif begin for trap data dynamicparam " +
            "end break throw param continue finally in switch exit filter try process catch " +
            "hidden static parameter"

    val powershellKeywords = keywords {
        pattern = """-?[A-z\.\-]+\b"""
        keyword(keywordWords)
        // "echo" relevance has been set to 0 to avoid auto-detect conflicts with shell transcripts
        builtIn(
            "ac asnp cat cd CFS chdir clc clear clhy cli clp cls clv cnsn compare copy cp " +
                "cpi cpp curl cvpa dbp del diff dir dnsn ebp echo|0 epal epcsv epsn erase etsn exsn fc fhx " +
                "fl ft fw gal gbp gc gcb gci gcm gcs gdr gerr ghy gi gin gjb gl gm gmo gp gps gpv group " +
                "gsn gsnp gsv gtz gu gv gwmi h history icm iex ihy ii ipal ipcsv ipmo ipsn irm ise iwmi " +
                "iwr kill lp ls man md measure mi mount move mp mv nal ndr ni nmo npssc nsn nv ogv oh " +
                "popd ps pushd pwd r rbp rcjb rcsn rd rdr ren ri rjb rm rmdir rmo rni rnp rp rsn rsnp " +
                "rujb rv rvpa rwmi sajb sal saps sasv sbp sc scb select set shcm si sl sleep sls sort sp " +
                "spjb spps spsv start stz sujb sv swmi tee trcm type wget where wjb write",
        )
        // TODO: 'validate[A-Z]+' can't work in keywords
    }

    val titleNameRe = """\w[\w\d]*((-)[\w\d]+)*"""

    val backtickEscape = mode {
        begin = """`[\s\S]"""
        relevance = 0.0
    }

    val varMode = mode {
        scope = "variable"
        variants = listOf(
            { begin = """\$\B""" },
            {
                scope = "keyword"
                begin = """\${'$'}this"""
            },
            { begin = """\$[\w\d][\w\d_:]*""" },
        )
    }

    val literal = mode {
        scope = "literal"
        begin = """\$(null|true|false)\b"""
    }

    val quoteString = mode {
        scope = "string"
        variants = listOf(
            {
                begin = "\""
                end = "\""
            },
            {
                begin = "@\""
                end = "^\"@"
            },
        )
        contains = listOf(
            backtickEscape,
            varMode,
            mode {
                scope = "variable"
                begin = """\$[A-z]"""
                end = """[^A-z]"""
            },
        )
    }

    val aposString = mode {
        scope = "string"
        variants = listOf(
            {
                begin = "'"
                end = "'"
            },
            {
                begin = "@'"
                end = "^'@"
            },
        )
    }

    val psHelpTags = mode {
        scope = "doctag"
        variants = listOf(
            // No parameter help tags.
            {
                begin =
                    """\.(synopsis|description|example|inputs|outputs|notes|link|component|role|functionality)"""
            },
            // One parameter help tags.
            {
                begin =
                    """\.(parameter|forwardhelptargetname|forwardhelpcategory|remotehelprunspace|externalhelp)\s+\S+"""
            },
        )
    }

    val psComment = comment(null, null).inherit {
        variants = listOf(
            // Single-line comment.
            {
                begin = "#"
                end = "$"
            },
            // Multi-line comment.
            {
                begin = "<#"
                end = "#>"
            },
        )
        contains = listOf(psHelpTags)
    }

    val cmdlets = mode {
        scope = "built_in"
        variants = listOf({ begin = """($validVerbs)+(-)[\w\d]+""" })
    }

    val psClass = mode {
        scope = "class"
        beginKeywords = "class enum"
        end = """\s*[{]"""
        excludeEnd = true
        relevance = 0.0
        contains = listOf(TITLE_MODE)
    }

    val psFunction = mode {
        scope = "function"
        begin = """function\s+"""
        end = """\s*\{|$"""
        excludeEnd = true
        returnBegin = true
        relevance = 0.0
        contains = listOf(
            mode {
                begin = "function"
                relevance = 0.0
                scope = "keyword"
            },
            mode {
                scope = "title"
                begin = titleNameRe
                relevance = 0.0
            },
            mode {
                begin = """\("""
                end = """\)"""
                scope = "params"
                relevance = 0.0
                contains = listOf(varMode)
            },
            // CMDLETS
        )
    }

    // Using statement, plus type, plus assembly name.
    val psUsing = mode {
        begin = """using\s"""
        end = "$"
        returnBegin = true
        contains = listOf(
            quoteString,
            aposString,
            mode {
                scope = "keyword"
                begin = """(using|assembly|command|module|namespace|type)"""
            },
        )
    }

    // Comparison operators & function named parameters.
    val psArguments = mode {
        variants = listOf(
            // PS literals are pretty verbose so it's a good idea to accent them a bit.
            {
                scope = "operator"
                begin = """($comparisonOperators)\b"""
            },
            {
                scope = "literal"
                begin = """(-){1,2}[\w\d-]+"""
                relevance = 0.0
            },
        )
    }

    val hashSigns = mode {
        scope = "selector-tag"
        begin = """@\B"""
        relevance = 0.0
    }

    // It's a very general rule so I'll narrow it a bit with some strict boundaries
    // to avoid any possible false-positive collisions!
    val psMethods = mode {
        scope = "function"
        begin = """\[.*\]\s*[\w]+[ ]??\("""
        end = "$"
        returnBegin = true
        relevance = 0.0
        contains = listOf(
            mode {
                scope = "keyword"
                begin = """(${keywordWords.replace(Regex("""\s"""), "|")})\b"""
                endsParent = true
                relevance = 0.0
            },
            TITLE_MODE.inherit { endsParent = true },
        )
    }

    val gentlemansSet = listOf(
        // STATIC_MEMBER,
        psMethods,
        psComment,
        backtickEscape,
        NUMBER_MODE,
        quoteString,
        aposString,
        // PS_NEW_OBJECT_TYPE,
        cmdlets,
        varMode,
        literal,
        hashSigns,
    )

    val psType = mode {
        begin = """\["""
        end = """\]"""
        excludeBegin = true
        excludeEnd = true
        relevance = 0.0
        contains = listOf(Mode.SELF) + gentlemansSet + listOf(
            mode {
                begin = "(" + types.joinToString("|") + ")"
                scope = "built_in"
                relevance = 0.0
            },
            mode {
                scope = "type"
                begin = """[\.\w\d]+"""
                relevance = 0.0
            },
        )
    }

    // `PS_METHODS.contains.unshift(PS_TYPE)` upstream: the two modes reference each other, so the
    // link can only be closed once both exist.
    psMethods.contains = listOf(psType) + psMethods.contains

    return Language(
        name = "PowerShell",
        aliases = setOf("powershell", "pwsh", "ps", "ps1"),
        caseInsensitive = true,
        root = mode {
            keywords = powershellKeywords
            contains = gentlemansSet + listOf(psClass, psFunction, psUsing, psArguments, psType)
        },
    )
}

package me.rerere.highlight.languages.bash

import me.rerere.highlight.core.BACKSLASH_ESCAPE
import me.rerere.highlight.core.Language
import me.rerere.highlight.core.Mode
import me.rerere.highlight.core.NUMBER_MODE
import me.rerere.highlight.core.TITLE_MODE
import me.rerere.highlight.core.comment
import me.rerere.highlight.core.concat
import me.rerere.highlight.core.endSameAsBegin
import me.rerere.highlight.core.keywords
import me.rerere.highlight.core.mode
import me.rerere.highlight.core.shebang

/** Bash, ported from `lib/languages/bash.js` of `highlight.js` 11.11.1. */
internal fun bash(): Language {
    val variable = Mode()
    val bracedVariable = mode {
        begin = """\$\{"""
        end = """\}"""
        contains = listOf(
            Mode.SELF,
            // Default values.
            mode {
                begin = ":-"
                contains = listOf(variable)
            },
        )
    }
    variable.apply {
        scope = "variable"
        variants = listOf(
            {
                // The negative look-ahead avoids matching things that are not shell variables at
                // all, such as `$ident$` or `@ident@`.
                begin = concat("""\$[\w\d#@][\w\d_]*""", """(?![\w\d])(?![$])""")
            },
            { overwriteFrom(bracedVariable) },
        )
    }

    val subst = mode {
        scope = "subst"
        begin = """\$\("""
        end = """\)"""
        contains = listOf(BACKSLASH_ESCAPE)
    }
    val commentMode = comment().inherit {
        scope = null
        matchList = listOf("""(^|\s)""", """#.*$""")
        scopes = mapOf(2 to "comment")
    }
    val hereDoc = mode {
        begin = """<<-?\s*(?=\w+)"""
        starts = mode {
            contains = listOf(
                mode {
                    begin = """(\w+)"""
                    end = """(\w+)"""
                    scope = "string"
                }.endSameAsBegin(),
            )
        }
    }
    val quoteString = mode {
        scope = "string"
        begin = "\""
        end = "\""
        contains = listOf(BACKSLASH_ESCAPE, variable, subst)
    }
    subst.contains = subst.contains + quoteString

    val escapedQuote = mode { match = """\\"""" }
    val aposString = mode {
        scope = "string"
        begin = "'"
        end = "'"
    }
    val escapedApos = mode { match = """\\'""" }
    val arithmetic = mode {
        begin = """\$?\(\("""
        end = """\)\)"""
        contains = listOf(
            mode {
                begin = """\d+#[0-9a-f]+"""
                scope = "number"
            },
            NUMBER_MODE,
            variable,
        )
    }
    val knownShebang = shebang(binary = "(${SH_LIKE_SHELLS.joinToString("|")})") { relevance = 10.0 }
    val function = mode {
        scope = "function"
        begin = """\w[\w\d_]*\s*\(\s*\)\s*\{"""
        returnBegin = true
        contains = listOf(TITLE_MODE.inherit { begin = """\w[\w\d_]*""" })
        relevance = 0.0
    }

    // Consumes paths so that keywords inside them are not matched.
    val pathMode = mode { match = """(\/[a-z._-]+)+""" }

    return Language(
        name = "Bash",
        aliases = setOf("bash", "sh", "zsh", "shell"),
        root = mode {
            keywords = keywords {
                pattern = """\b[a-z][a-z0-9._-]+\b"""
                keyword(BASH_KEYWORDS)
                literal(listOf("true", "false"))
                builtIn(
                    SHELL_BUILT_INS + BASH_BUILT_INS +
                        // Shell modifiers.
                        listOf("set", "shopt") +
                        ZSH_BUILT_INS + GNU_CORE_UTILS,
                )
            }
            contains = listOf(
                // Catches known shells and boosts relevance.
                knownShebang,
                // Catches unknown shells but still highlights the shebang.
                shebang(),
                function,
                arithmetic,
                commentMode,
                hereDoc,
                pathMode,
                quoteString,
                escapedQuote,
                aposString,
                escapedApos,
                variable,
            )
        },
    )
}

private val SH_LIKE_SHELLS =
    listOf("fish", "bash", "zsh", "sh", "csh", "ksh", "tcsh", "dash", "scsh")

private val BASH_KEYWORDS = listOf(
    "if", "then", "else", "elif", "fi", "time", "for", "while", "until", "in", "do", "done",
    "case", "esac", "coproc", "function", "select",
)

/** See http://www.gnu.org/software/bash/manual/html_node/Shell-Builtin-Commands.html */
private val SHELL_BUILT_INS = listOf(
    "break", "cd", "continue", "eval", "exec", "exit", "export", "getopts", "hash", "pwd",
    "readonly", "return", "shift", "test", "times", "trap", "umask", "unset",
)

private val BASH_BUILT_INS = listOf(
    "alias", "bind", "builtin", "caller", "command", "declare", "echo", "enable", "help", "let",
    "local", "logout", "mapfile", "printf", "read", "readarray", "source", "sudo", "type",
    "typeset", "ulimit", "unalias",
)

private val ZSH_BUILT_INS = listOf(
    "autoload", "bg", "bindkey", "bye", "cap", "chdir", "clone", "comparguments", "compcall",
    "compctl", "compdescribe", "compfiles", "compgroups", "compquote", "comptags", "comptry",
    "compvalues", "dirs", "disable", "disown", "echotc", "echoti", "emulate", "fc", "fg", "float",
    "functions", "getcap", "getln", "history", "integer", "jobs", "kill", "limit", "log", "noglob",
    "popd", "print", "pushd", "pushln", "rehash", "sched", "setcap", "setopt", "stat", "suspend",
    "ttyctl", "unfunction", "unhash", "unlimit", "unsetopt", "vared", "wait", "whence", "where",
    "which", "zcompile", "zformat", "zftp", "zle", "zmodload", "zparseopts", "zprof", "zpty",
    "zregexparse", "zsocket", "zstyle", "ztcp",
)

private val GNU_CORE_UTILS = listOf(
    "chcon", "chgrp", "chown", "chmod", "cp", "dd", "df", "dir", "dircolors", "ln", "ls", "mkdir",
    "mkfifo", "mknod", "mktemp", "mv", "realpath", "rm", "rmdir", "shred", "sync", "touch",
    "truncate", "vdir", "b2sum", "base32", "base64", "cat", "cksum", "comm", "csplit", "cut",
    "expand", "fmt", "fold", "head", "join", "md5sum", "nl", "numfmt", "od", "paste", "ptx", "pr",
    "sha1sum", "sha224sum", "sha256sum", "sha384sum", "sha512sum", "shuf", "sort", "split", "sum",
    "tac", "tail", "tr", "tsort", "unexpand", "uniq", "wc", "arch", "basename", "chroot", "date",
    "dirname", "du", "echo", "env", "expr", "factor",
    // "false" is already a keyword literal.
    "groups", "hostid", "id", "link", "logname", "nice", "nohup", "nproc", "pathchk", "pinky",
    "printenv", "printf", "pwd", "readlink", "runcon", "seq", "sleep", "stat", "stdbuf", "stty",
    "tee", "test", "timeout",
    // "true" is already a keyword literal.
    "tty", "uname", "unlink", "uptime", "users", "who", "whoami", "yes",
)

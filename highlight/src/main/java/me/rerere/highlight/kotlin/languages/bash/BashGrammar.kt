package me.rerere.highlight.kotlin.languages.bash

internal object BashGrammar {
    val keywords = setOf(
        "if", "then", "else", "elif", "fi", "time", "for", "while", "until", "in",
        "do", "done", "case", "esac", "coproc", "function", "select",
    )

    val builtIns = setOf(
        "alias", "bg", "bind", "break", "builtin", "caller", "cd", "command", "compgen",
        "complete", "continue", "declare", "dirs", "disown", "echo", "enable", "eval",
        "exec", "exit", "export", "false", "fc", "fg", "getopts", "hash", "help",
        "history", "jobs", "kill", "let", "local", "logout", "mapfile", "popd", "printf",
        "pushd", "pwd", "read", "readarray", "readonly", "return", "set", "shift",
        "shopt", "source", "suspend", "test", "times", "trap", "true", "type", "typeset",
        "ulimit", "umask", "unalias", "unset", "wait",
        "awk", "basename", "cat", "chmod", "chown", "cp", "curl", "cut", "date", "df",
        "dirname", "du", "env", "find", "grep", "head", "ln", "ls", "mkdir", "mktemp",
        "mv", "readlink", "realpath", "rm", "rmdir", "sed", "sleep", "sort", "sudo",
        "tail", "tar", "tee", "touch", "tr", "uname", "uniq", "wc", "wget", "which",
        "whoami", "xargs",
    )

    val identifierPattern = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")
    val variableNamePattern = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
}

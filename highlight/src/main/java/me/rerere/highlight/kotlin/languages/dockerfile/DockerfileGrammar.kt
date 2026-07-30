package me.rerere.highlight.kotlin.languages.dockerfile

internal object DockerfileGrammar {
    val instructions = setOf(
        "add", "arg", "cmd", "copy", "entrypoint", "env", "expose", "from",
        "healthcheck", "label", "maintainer", "onbuild", "run", "shell",
        "stopsignal", "user", "volume", "workdir",
    )

    val shellInstructions = setOf(
        "add", "cmd", "copy", "entrypoint", "healthcheck", "run", "shell",
        "volume", "workdir",
    )
}

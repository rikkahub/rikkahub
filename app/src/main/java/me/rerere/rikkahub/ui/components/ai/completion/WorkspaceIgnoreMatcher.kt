package me.rerere.rikkahub.ui.components.ai.completion

internal class WorkspaceIgnoreMatcher private constructor(
    private val rules: List<Rule> = DEFAULT_RULES,
) {
    constructor() : this(DEFAULT_RULES)

    fun withGitignore(directory: String, content: String): WorkspaceIgnoreMatcher {
        val basePath = directory.normalizeWorkspacePath()
        val parsed = content
            .lineSequence()
            .mapNotNull { line -> Rule.parse(basePath, line) }
            .toList()
        return if (parsed.isEmpty()) this else WorkspaceIgnoreMatcher(rules + parsed)
    }

    fun isIgnored(path: String, isDirectory: Boolean): Boolean {
        val normalized = path.normalizeWorkspacePath()
        var ignored = false
        rules.forEach { rule ->
            if (rule.matches(normalized, isDirectory)) {
                ignored = !rule.negated
            }
        }
        return ignored
    }

    private data class Rule(
        val basePath: String,
        val pattern: String,
        val regex: Regex,
        val negated: Boolean,
        val directoryOnly: Boolean,
        val anchored: Boolean,
        val hasSlash: Boolean,
    ) {
        fun matches(path: String, isDirectory: Boolean): Boolean {
            if (directoryOnly && !isDirectory) return false
            val relative = path.relativeToBaseOrNull(basePath) ?: return false
            if (relative.isBlank()) return false
            val target = if (anchored || hasSlash) relative else relative.substringAfterLast('/')
            return regex.matches(target)
        }

        companion object {
            fun parse(basePath: String, rawLine: String): Rule? {
                var line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return null
                if (line.startsWith("\\#") || line.startsWith("\\!")) {
                    line = line.drop(1)
                }

                val negated = line.startsWith("!")
                if (negated) line = line.drop(1).trim()
                if (line.isBlank()) return null

                val directoryOnly = line.endsWith("/")
                val anchored = line.startsWith("/")
                val pattern = line
                    .trim('/')
                    .takeIf { it.isNotBlank() }
                    ?: return null
                val hasSlash = pattern.contains('/')
                return Rule(
                    basePath = basePath,
                    pattern = pattern,
                    regex = pattern.toGitignoreRegex(),
                    negated = negated,
                    directoryOnly = directoryOnly,
                    anchored = anchored,
                    hasSlash = hasSlash,
                )
            }
        }
    }

    companion object {
        private val DEFAULT_RULES = listOf(
            "build/",
            ".gradle/",
            ".git/",
            "node_modules/",
            "dist/",
            "out/",
        ).mapNotNull { Rule.parse(basePath = "", rawLine = it) }
    }
}

private fun String.normalizeWorkspacePath(): String =
    replace('\\', '/').trim().trim('/')

private fun String.relativeToBaseOrNull(basePath: String): String? {
    if (basePath.isBlank()) return this
    if (this == basePath) return ""
    val prefix = "$basePath/"
    return if (startsWith(prefix)) removePrefix(prefix) else null
}

private fun String.toGitignoreRegex(): Regex {
    val result = StringBuilder()
    var index = 0
    while (index < length) {
        val char = this[index]
        when (char) {
            '*' -> {
                val nextIsStar = getOrNull(index + 1) == '*'
                if (nextIsStar) {
                    val followedBySlash = getOrNull(index + 2) == '/'
                    if (followedBySlash) {
                        result.append("(?:.*/)?")
                        index += 3
                    } else {
                        result.append(".*")
                        index += 2
                    }
                } else {
                    result.append("[^/]*")
                    index++
                }
            }

            '?' -> {
                result.append("[^/]")
                index++
            }

            else -> {
                if (char in REGEX_SPECIAL_CHARS) result.append('\\')
                result.append(char)
                index++
            }
        }
    }
    return Regex("^$result$")
}

private const val REGEX_SPECIAL_CHARS = "\\.[]{}()+-^$|"

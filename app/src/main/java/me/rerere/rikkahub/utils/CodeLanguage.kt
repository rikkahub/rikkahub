package me.rerere.rikkahub.utils

object CodeLanguage {
    private val extToLanguage = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "js" to "javascript", "ts" to "typescript",
        "css" to "css",
        "html" to "html", "htm" to "html",
        "xml" to "xml",
        "json" to "json",
        "yaml" to "yaml", "yml" to "yaml",
        "toml" to "toml",
        "sh" to "bash", "bash" to "bash",
        "sql" to "sql",
        "c" to "c",
        "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp",
        "h" to "cpp", "hpp" to "cpp",
        "rs" to "rust",
        "go" to "go",
        "swift" to "swift",
        "rb" to "ruby",
        "php" to "php",
        "dart" to "dart",
    )

    fun isCodeFile(fileName: String): Boolean = languageFor(fileName) != null

    fun languageFor(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return extToLanguage[ext]
    }

    fun languageForOrText(fileName: String): String =
        languageFor(fileName) ?: fileName.substringAfterLast('.', "").lowercase().ifEmpty { "text" }
}

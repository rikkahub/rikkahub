package me.rerere.rikkahub.ui.components.richtext

private val SVG_TAG_REGEX = Regex("""<\s*svg\b""", RegexOption.IGNORE_CASE)

internal enum class CodeBlockRenderType {
    HTML,
    SVG,
}

internal data class CodeBlockRenderTarget(
    val normalizedLanguage: String,
    val renderType: CodeBlockRenderType,
)

internal object CodeBlockRenderResolver {
    fun resolve(
        language: String,
        code: String,
    ): CodeBlockRenderTarget? {
        val normalized = normalizeLanguage(language)
        return when (normalized) {
            "html" -> CodeBlockRenderTarget(normalizedLanguage = normalized, renderType = CodeBlockRenderType.HTML)
            "svg" -> CodeBlockRenderTarget(normalizedLanguage = normalized, renderType = CodeBlockRenderType.SVG)
            "xml" -> {
                if (containsSvgMarkup(code)) {
                    CodeBlockRenderTarget(normalizedLanguage = normalized, renderType = CodeBlockRenderType.SVG)
                } else {
                    null
                }
            }

            else -> null
        }
    }

    fun buildHtmlForWebView(
        target: CodeBlockRenderTarget,
        code: String,
    ): String {
        return when (target.renderType) {
            CodeBlockRenderType.HTML,
            CodeBlockRenderType.SVG -> code
        }
    }

    private fun normalizeLanguage(language: String): String {
        if (language.isBlank()) return ""
        val firstToken = language.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
            .takeWhile { ch -> ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '_' || ch == '.' }
        return when (firstToken) {
            "htm", "xhtml" -> "html"
            else -> firstToken
        }
    }

    private fun containsSvgMarkup(code: String): Boolean {
        return SVG_TAG_REGEX.containsMatchIn(code)
    }
}

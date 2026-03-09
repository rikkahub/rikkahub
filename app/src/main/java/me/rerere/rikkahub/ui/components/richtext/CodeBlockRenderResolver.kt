package me.rerere.rikkahub.ui.components.richtext

import java.math.BigDecimal

private val HTML_ROOT_TAG_REGEX = Regex("""^<\s*html\b""", RegexOption.IGNORE_CASE)
private val HTML_OPEN_TAG_REGEX = Regex("""<\s*html\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HEAD_OPEN_TAG_REGEX = Regex("""<\s*head\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HEAD_CLOSE_TAG_REGEX = Regex("""</\s*head\s*>""", RegexOption.IGNORE_CASE)
private val VIEWPORT_META_REGEX =
    Regex("""<meta\b[^>]*name\s*=\s*(["'])viewport\1[^>]*>""", RegexOption.IGNORE_CASE)
private val XML_LEADING_MISC_REGEX =
    Regex("""^\uFEFF?(?:\s|<\?xml[\s\S]*?\?>|<!--[\s\S]*?-->|<!DOCTYPE[\s\S]*?>|<\?[\s\S]*?\?>)*""", RegexOption.IGNORE_CASE)
private val XML_ROOT_TAG_REGEX = Regex("""^<\s*([A-Za-z_][A-Za-z0-9_.:-]*)\b""")
private val CSS_MIN_HEIGHT_REGEX =
    Regex("""(min-height\s*:\s*)([^;{}]*?\d+(?:\.\d+)?vh)(?=\s*[;}])""", RegexOption.IGNORE_CASE)
private val INLINE_MIN_HEIGHT_REGEX =
    Regex("""(min-height\s*:\s*)([^;]*?\d+(?:\.\d+)?vh)""", RegexOption.IGNORE_CASE)
private val INLINE_STYLE_REGEX = Regex("""(style\s*=\s*(["']))([\s\S]*?)(\2)""", RegexOption.IGNORE_CASE)
private val JS_MIN_HEIGHT_ASSIGNMENT_REGEX =
    Regex("""(\.style\.minHeight\s*=\s*(["']))([\s\S]*?)(\2)""", RegexOption.IGNORE_CASE)
private val JS_SET_PROPERTY_MIN_HEIGHT_REGEX =
    Regex(
        """(setProperty\s*\(\s*(["'])min-height\2\s*,\s*(["']))([\s\S]*?)(\3\s*\))""",
        RegexOption.IGNORE_CASE
    )
private val VH_VALUE_REGEX = Regex("""(\d+(?:\.\d+)?)vh\b""", RegexOption.IGNORE_CASE)

internal const val CODE_BLOCK_HEIGHT_BRIDGE_NAME = "RikkaHubCodeBlockBridge"

internal enum class CodeBlockRenderScrollMode {
    AUTO_HEIGHT,
    SCROLLABLE,
}

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
        backgroundColor: String = "#ffffff",
        textColor: String = "#000000",
        scrollMode: CodeBlockRenderScrollMode = CodeBlockRenderScrollMode.AUTO_HEIGHT,
    ): String {
        return when (target.renderType) {
            CodeBlockRenderType.HTML -> buildHtmlDocument(
                content = replaceVhInContent(code),
                backgroundColor = backgroundColor,
                textColor = textColor,
                scrollMode = scrollMode,
            )

            CodeBlockRenderType.SVG -> createRenderShell(
                content = replaceVhInContent(code),
                backgroundColor = backgroundColor,
                textColor = textColor,
                scrollMode = scrollMode,
            )
        }
    }

    private fun normalizeLanguage(language: String): String {
        if (language.isBlank()) return ""
        val firstToken = language.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .firstOrNull()
            .orEmpty()
            .takeWhile { ch ->
                ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '_' || ch == '.' || ch == '/'
            }
        return when (firstToken) {
            "htm", "xhtml", "text/html", "application/xhtml+xml" -> "html"
            "image/svg+xml" -> "svg"
            "application/xml", "text/xml" -> "xml"
            else -> firstToken
        }
    }

    private fun containsSvgMarkup(code: String): Boolean {
        return extractXmlRootTagName(code) == "svg"
    }

    private fun replaceVhInContent(content: String): String {
        var updated = content

        // 1) CSS declarations: min-height: 100vh;
        updated = updated.replace(CSS_MIN_HEIGHT_REGEX) { match ->
            val prefix = match.groupValues[1]
            val value = match.groupValues[2]
            "$prefix${convertVhToViewportVariable(value)}"
        }

        // 2) Inline style attribute: style="min-height: 80vh"
        updated = updated.replace(INLINE_STYLE_REGEX) { match ->
            val styleContent = match.groupValues[3]
            if (!styleContent.contains("min-height", ignoreCase = true) || !styleContent.contains("vh", ignoreCase = true)) {
                return@replace match.value
            }
            val replacedStyle = styleContent.replace(INLINE_MIN_HEIGHT_REGEX) { styleMatch ->
                val stylePrefix = styleMatch.groupValues[1]
                val styleValue = styleMatch.groupValues[2]
                "$stylePrefix${convertVhToViewportVariable(styleValue)}"
            }
            "${match.groupValues[1]}$replacedStyle${match.groupValues[4]}"
        }

        // 3) JavaScript assignment: element.style.minHeight = "100vh"
        updated = updated.replace(JS_MIN_HEIGHT_ASSIGNMENT_REGEX) { match ->
            val value = match.groupValues[3]
            if (!VH_VALUE_REGEX.containsMatchIn(value)) {
                return@replace match.value
            }
            "${match.groupValues[1]}${convertVhToViewportVariable(value)}${match.groupValues[4]}"
        }

        // 4) JavaScript setProperty: style.setProperty('min-height', '100vh')
        updated = updated.replace(JS_SET_PROPERTY_MIN_HEIGHT_REGEX) { match ->
            val value = match.groupValues[4]
            if (!VH_VALUE_REGEX.containsMatchIn(value)) {
                return@replace match.value
            }
            "${match.groupValues[1]}${convertVhToViewportVariable(value)}${match.groupValues[5]}"
        }

        return updated
    }

    private fun convertVhToViewportVariable(value: String): String {
        return VH_VALUE_REGEX.replace(value) { match ->
            val raw = match.groupValues[1]
            val parsed = raw.toDoubleOrNull() ?: return@replace match.value
            if (!parsed.isFinite()) return@replace match.value
            if (parsed == 100.0) {
                "var(--TH-viewport-height)"
            } else {
                val ratio = (parsed / 100.0).toPlainString()
                "calc(var(--TH-viewport-height) * $ratio)"
            }
        }
    }

    private fun Double.toPlainString(): String {
        return BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
    }

    private fun buildHtmlDocument(
        content: String,
        backgroundColor: String,
        textColor: String,
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        return if (looksLikeFullHtmlDocument(content)) {
            injectRenderSupportIntoHtmlDocument(content, scrollMode)
        } else {
            createRenderShell(
                content = content,
                backgroundColor = backgroundColor,
                textColor = textColor,
                scrollMode = scrollMode,
            )
        }
    }

    private fun looksLikeFullHtmlDocument(content: String): Boolean {
        val leadingMisc = XML_LEADING_MISC_REGEX.find(content)?.value.orEmpty()
        return HTML_ROOT_TAG_REGEX.containsMatchIn(content.substring(leadingMisc.length))
    }

    private fun injectRenderSupportIntoHtmlDocument(
        content: String,
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        val headInjection = buildHeadInjection(
            backgroundColor = null,
            textColor = null,
            scrollMode = scrollMode,
            preserveDocumentStyles = true,
            includeViewportMeta = !VIEWPORT_META_REGEX.containsMatchIn(content),
        )

        val headCloseTag = HEAD_CLOSE_TAG_REGEX.find(content)
        if (headCloseTag != null) {
            return insertBefore(content, headCloseTag.range.first, headInjection)
        }

        val headOpenTag = HEAD_OPEN_TAG_REGEX.find(content)
        if (headOpenTag != null) {
            return insertAfter(content, headOpenTag.range.last + 1, headInjection)
        }

        val htmlOpenTag = HTML_OPEN_TAG_REGEX.find(content)
        if (htmlOpenTag != null) {
            return insertAfter(content, htmlOpenTag.range.last + 1, "<head>$headInjection</head>")
        }

        return createRenderShell(
            content = content,
            backgroundColor = "#ffffff",
            textColor = "#000000",
            scrollMode = scrollMode,
        )
    }

    private fun createRenderShell(
        content: String,
        backgroundColor: String = "#ffffff",
        textColor: String = "#000000",
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        val headInjection = buildHeadInjection(
            backgroundColor = backgroundColor,
            textColor = textColor,
            scrollMode = scrollMode,
            preserveDocumentStyles = false,
            includeViewportMeta = true,
        )
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            $headInjection
            </head>
            <body>
            $content
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildHeadInjection(
        backgroundColor: String?,
        textColor: String?,
        scrollMode: CodeBlockRenderScrollMode,
        preserveDocumentStyles: Boolean,
        includeViewportMeta: Boolean,
    ): String {
        val style = if (preserveDocumentStyles) {
            createExistingDocumentStyle(scrollMode)
        } else {
            createFragmentShellStyle(
                backgroundColor = backgroundColor ?: "#ffffff",
                textColor = textColor ?: "#000000",
                scrollMode = scrollMode,
            )
        }

        val viewportMeta = if (includeViewportMeta) {
            """<meta name="viewport" content="width=device-width, initial-scale=1.0">"""
        } else {
            ""
        }

        return buildString {
            append(viewportMeta)
            append(style)
            append(createBridgeScript())
        }
    }

    private fun createFragmentShellStyle(
        backgroundColor: String,
        textColor: String,
        scrollMode: CodeBlockRenderScrollMode,
    ): String {
        val overflowY = when (scrollMode) {
            CodeBlockRenderScrollMode.AUTO_HEIGHT -> "hidden"
            CodeBlockRenderScrollMode.SCROLLABLE -> "auto"
        }
        return """
            <style>
            :root{--TH-viewport-height:100vh;}
            *,*::before,*::after{box-sizing:border-box;}
            html,body{
              margin:0!important;
              padding:0;
              overflow-x:hidden!important;
              overflow-y:$overflowY!important;
              max-width:100%!important;
              background-color:$backgroundColor;
              color:$textColor;
            }
            </style>
        """.trimIndent()
    }

    private fun createExistingDocumentStyle(scrollMode: CodeBlockRenderScrollMode): String {
        val overflowRule = when (scrollMode) {
            CodeBlockRenderScrollMode.AUTO_HEIGHT -> "overflow-x:hidden!important;"
            CodeBlockRenderScrollMode.SCROLLABLE -> "overflow-x:hidden!important;overflow-y:auto!important;"
        }
        return """
            <style>
            :root{--TH-viewport-height:100vh;}
            html,body{$overflowRule}
            </style>
        """.trimIndent()
    }

    private fun createBridgeScript(): String {
        return """
            <script>
            (function() {
              function updateViewportHeight() {
                document.documentElement.style.setProperty('--TH-viewport-height', window.innerHeight + 'px');
              }

              function reportHeight() {
                var body = document.body;
                var doc = document.documentElement;
                var height = body ? body.scrollHeight : 0;

                // Avoid using offsetHeight/doc offsets as primary signal, because they can
                // reflect current viewport height and keep short content artificially tall.
                if (!Number.isFinite(height) || height <= 0) {
                  height = body ? body.getBoundingClientRect().height : 0;
                }
                if (!Number.isFinite(height) || height <= 0) {
                  height = doc ? doc.scrollHeight : 0;
                }
                if (!Number.isFinite(height) || height <= 0) {
                  height = doc ? doc.getBoundingClientRect().height : 0;
                }
                if (!Number.isFinite(height) || height <= 0) return;
                var nextHeight = Math.ceil(height);
                if (window.__RH_LAST_REPORTED_HEIGHT__ === nextHeight) return;
                window.__RH_LAST_REPORTED_HEIGHT__ = nextHeight;
                try {
                  var bridge = window.$CODE_BLOCK_HEIGHT_BRIDGE_NAME;
                  if (bridge && typeof bridge.onContentHeight === 'function') {
                    bridge.onContentHeight(String(nextHeight));
                  }
                } catch (_err) {}
              }

              function scheduleReportHeight() {
                if (typeof window.requestAnimationFrame === 'function') {
                  window.requestAnimationFrame(reportHeight);
                } else {
                  setTimeout(reportHeight, 0);
                }
              }

              if (window.__RH_CODE_BLOCK_OBSERVER_ATTACHED__) {
                updateViewportHeight();
                scheduleReportHeight();
                return;
              }
              window.__RH_CODE_BLOCK_OBSERVER_ATTACHED__ = true;

              function observeHeightChanges() {
                if (typeof ResizeObserver === 'function') {
                  var resizeObserver = new ResizeObserver(function() {
                    scheduleReportHeight();
                  });
                  if (document.documentElement) resizeObserver.observe(document.documentElement);
                  if (document.body) resizeObserver.observe(document.body);
                } else if (typeof MutationObserver === 'function' && document.body) {
                  var mutationObserver = new MutationObserver(function() {
                    scheduleReportHeight();
                  });
                  mutationObserver.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    characterData: true
                  });
                }
              }

              updateViewportHeight();
              window.addEventListener('resize', function() {
                updateViewportHeight();
                scheduleReportHeight();
              });

              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', function() {
                  observeHeightChanges();
                  scheduleReportHeight();
                });
              } else {
                observeHeightChanges();
                scheduleReportHeight();
              }

              window.addEventListener('load', function() {
                scheduleReportHeight();
                setTimeout(scheduleReportHeight, 120);
                setTimeout(scheduleReportHeight, 360);
              });
            })();
            </script>
        """.trimIndent()
    }

    private fun extractXmlRootTagName(code: String): String? {
        val leadingMisc = XML_LEADING_MISC_REGEX.find(code)?.value.orEmpty()
        val remaining = code.substring(leadingMisc.length)
        val match = XML_ROOT_TAG_REGEX.find(remaining) ?: return null
        return match.groupValues[1]
            .substringAfterLast(':')
            .lowercase()
    }

    private fun insertBefore(
        content: String,
        index: Int,
        insertion: String,
    ): String {
        return buildString(content.length + insertion.length) {
            append(content, 0, index)
            append(insertion)
            append(content, index, content.length)
        }
    }

    private fun insertAfter(
        content: String,
        index: Int,
        insertion: String,
    ): String {
        return buildString(content.length + insertion.length) {
            append(content, 0, index)
            append(insertion)
            append(content, index, content.length)
        }
    }
}

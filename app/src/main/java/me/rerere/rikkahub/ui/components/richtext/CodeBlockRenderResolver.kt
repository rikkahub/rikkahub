package me.rerere.rikkahub.ui.components.richtext

import java.math.BigDecimal

private val SVG_TAG_REGEX = Regex("""<\s*svg\b""", RegexOption.IGNORE_CASE)
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
    ): String {
        return when (target.renderType) {
            CodeBlockRenderType.HTML,
            CodeBlockRenderType.SVG -> createRenderShell(
                replaceVhInContent(code),
                backgroundColor = backgroundColor,
                textColor = textColor,
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
            .takeWhile { ch -> ch.isLetterOrDigit() || ch == '+' || ch == '-' || ch == '_' || ch == '.' }
        return when (firstToken) {
            "htm", "xhtml" -> "html"
            else -> firstToken
        }
    }

    private fun containsSvgMarkup(code: String): Boolean {
        return SVG_TAG_REGEX.containsMatchIn(code)
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

    /**
     * Borrowed style direction from JS-Slash-Runner's render iframe shell:
     * - full html document
     * - viewport meta
     * - base reset styles
     * Keep code payload untouched and inject directly into body.
     */
    private fun createRenderShell(
        content: String,
        backgroundColor: String = "#ffffff",
        textColor: String = "#000000",
    ): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
            :root{--TH-viewport-height:100vh;}
            *,*::before,*::after{box-sizing:border-box;}
            html,body{margin:0!important;padding:0;overflow:hidden!important;max-width:100%!important;background-color:$backgroundColor;color:$textColor;}
            </style>
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
            </head>
            <body>
            $content
            </body>
            </html>
        """.trimIndent()
    }
}

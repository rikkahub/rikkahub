package me.rerere.rikkahub.ui.components.richtext

import io.kotest.property.Arb
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File

private const val HOST = "example.com"

class RichTextUriPolicyTest {
    private val identifierPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val directSanitizeInvocationPattern = Regex(
        "^\\s*sanitizeLinkUri\\s*\\(.*\\)\\s*$",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val safeLinkAssignmentPattern = Regex(
        "\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*sanitizeLinkUri\\s*\\(",
    )
    private val badSchemes = listOf(
        "javascript",
        "intent",
        "file",
        "content",
        "data",
        "vbscript",
        "jar",
        "about",
    )

    @Test
    fun `sanitizeLinkUri rejects bad schemes`() {
        runBlocking {
            checkAll(Arb.of(*badSchemes.toTypedArray())) { scheme ->
                assertNull(sanitizeLinkUri("$scheme://$HOST"))
            }
        }
    }

    @Test
    fun `sanitizeLinkUri allows http https and mailto links`() {
        runBlocking {
            checkAll(
                Arb.of(
                    "http://example.com",
                    "https://example.com/a?b=c#f",
                    "mailto:user@example.com",
                )
            ) { value ->
                assertEquals(value, sanitizeLinkUri(value))
            }
        }
    }

    @Test
    fun `isAllowedImageUri allows http and https images only`() {
        runBlocking {
            checkAll(
                Arb.of(
                    "http://example.com/a.png",
                    "https://example.com/a.webp",
                )
            ) { value ->
                assertTrue(isAllowedImageUri(value))
            }
        }
    }

    @Test
    fun `sanitizeLinkUri handles boundary inputs`() {
        assertNull(sanitizeLinkUri(null))
        assertNull(sanitizeLinkUri(""))
        assertNull(sanitizeLinkUri("   "))
        assertEquals("hTtPs://example.com", sanitizeLinkUri("  hTtPs://example.com  "))
        assertNull(sanitizeLinkUri("JaVaScRiPt:alert(1)"))
        assertNull(sanitizeLinkUri("http://example.com/\u0000x"))
        assertNull(sanitizeLinkUri("//$HOST"))
        assertNull(sanitizeLinkUri("/relative"))
        assertNull(sanitizeLinkUri("#anchor"))
        assertNull(sanitizeLinkUri("http:evil"))
        assertNull(sanitizeLinkUri("http:/x"))
        assertNull(sanitizeLinkUri("\tjavascript:payload"))
        assertNull(sanitizeLinkUri("\nhttps://$HOST"))
        assertNull(sanitizeLinkUri("https://x\t"))
        assertEquals("https://evil", sanitizeLinkUri("https://evil"))
        assertNull(sanitizeLinkUri("ht\ttp://$HOST"))
        assertFalse(isAllowedImageUri("\thttps://$HOST"))
        assertFalse(isAllowedImageUri("\nhttps://$HOST"))
        assertFalse(isAllowedImageUri("https://x\t"))
        assertTrue(isAllowedImageUri("https://evil"))
        assertFalse(isAllowedImageUri("ht\ttp://$HOST"))
    }

    @Test
    fun `bad schemes are rejected for both helpers with scheme-prefix payload`() {
        runBlocking {
            checkAll(Arb.of(*badSchemes.toTypedArray())) { scheme ->
                val payload = "$scheme:https://$HOST"
                assertNull(sanitizeLinkUri(payload))
                assertFalse(isAllowedImageUri(payload))
            }
        }
    }

    @Test
    fun `richtext uri construction is guarded by helpers`() {
        val dir = resolveRichtextDir()
        assertTrue(dir.isDirectory)

        val markdown = File(dir, "Markdown.kt").readText()
        val markdownHtmlInline = File(dir, "MarkdownHtmlInline.kt").readText()
        val markdownNew = File(dir, "MarkdownNew.kt").readText()
        val simpleHtml = File(dir, "SimpleHtmlBlock.kt").readText()

        assertLinkAnnotationUrlSinksAreSanitized(fileName = "Markdown.kt", source = markdown)
        assertActionViewIntentSinksAreSanitized(fileName = "Markdown.kt", source = markdown)
        assertZoomableAsyncImageCallsAreGuarded(fileName = "Markdown.kt", source = markdown)
        assertLinkAnnotationUrlSinksAreSanitized(fileName = "MarkdownHtmlInline.kt", source = markdownHtmlInline)
        assertActionViewIntentSinksAreSanitized(fileName = "MarkdownHtmlInline.kt", source = markdownHtmlInline)
        assertZoomableAsyncImageCallsAreGuarded(fileName = "MarkdownHtmlInline.kt", source = markdownHtmlInline)
        assertLinkAnnotationUrlSinksAreSanitized(fileName = "MarkdownNew.kt", source = markdownNew)
        assertActionViewIntentSinksAreSanitized(fileName = "MarkdownNew.kt", source = markdownNew)
        assertZoomableAsyncImageCallsAreGuarded(fileName = "MarkdownNew.kt", source = markdownNew)
        assertLinkAnnotationUrlSinksAreSanitized(fileName = "SimpleHtmlBlock.kt", source = simpleHtml)
        assertActionViewIntentSinksAreSanitized(fileName = "SimpleHtmlBlock.kt", source = simpleHtml)
        assertZoomableAsyncImageCallsAreGuarded(fileName = "SimpleHtmlBlock.kt", source = simpleHtml)
    }

    private fun resolveRichtextDir(): File {
        val rel = "src/main/java/me/rerere/rikkahub/ui/components/richtext"
        val moduleRelative = File(rel)
        if (moduleRelative.isDirectory) return moduleRelative
        val appRelative = File("app/$rel")
        if (appRelative.isDirectory) return appRelative
        return moduleRelative
    }

    private fun assertLinkAnnotationUrlSinksAreSanitized(fileName: String, source: String) {
        val sinkPattern = Regex("LinkAnnotation\\.Url\\s*\\(")
        for (match in sinkPattern.findAll(source)) {
            val openParen = match.range.last
            val arg = extractCallArgument(source, openParen).trim()
            assertSanitizedLinkSinkArg(
                fileName = fileName,
                source = source,
                sinkKind = "LinkAnnotation.Url",
                sinkOffset = match.range.first,
                rawArg = arg,
            )
        }
    }

    private fun assertActionViewIntentSinksAreSanitized(fileName: String, source: String) {
        val sinkPattern = Regex("Intent\\s*\\(\\s*Intent\\.ACTION_VIEW\\s*,")
        for (match in sinkPattern.findAll(source)) {
            val openParen = source.indexOf("(", match.range.first)
            val args = extractCallArgument(source, openParen).trim()
            val separator = args.indexOf(",")
            if (separator <= 0) {
                fail("$fileName:${lineNumber(source, match.range.first)} intent sink has unexpected format: $args")
            }
            val linkArg = args.substring(separator + 1).trim()
            assertSanitizedLinkSinkArg(
                fileName = fileName,
                source = source,
                sinkKind = "ACTION_VIEW intent",
                sinkOffset = match.range.first,
                rawArg = linkArg,
            )
        }
    }

    private fun assertZoomableAsyncImageCallsAreGuarded(fileName: String, source: String) {
        val sinkPattern = Regex("ZoomableAsyncImage\\s*\\(")
        for (match in sinkPattern.findAll(source)) {
            val openParen = match.range.last
            val callArgs = extractCallArgument(source, openParen)
            val modelMatch = Regex("model\\s*=\\s*").find(callArgs) ?: run {
                fail(
                    "$fileName:${lineNumber(source, match.range.first)} "
                        + "ZoomableAsyncImage call is missing model argument",
                )
                continue
            }

            val modelStart = modelMatch.range.last + 1
            val modelExpr = extractTopLevelExpression(callArgs, modelStart)
            if (modelExpr.isBlank()) {
                fail(
                    "$fileName:${lineNumber(source, match.range.first)} "
                        + "ZoomableAsyncImage call has empty model argument",
                )
            }

            val guardedByExpr = findEnclosingImageUriGuardArg(source, match.range.first)
            if (guardedByExpr == null) {
                fail(
                    "$fileName:${lineNumber(source, match.range.first)} "
                        + "ZoomableAsyncImage call is not guarded by isAllowedImageUri",
                )
            } else if (
                !isImageModelExpressionTiedToImageGuard(
                    source = source,
                    modelExprRaw = modelExpr,
                    allowedExprRaw = guardedByExpr,
                    sinkOffset = match.range.first,
                )
            ) {
                fail(
                    "$fileName:${lineNumber(source, match.range.first)} "
                        + "ZoomableAsyncImage model is not tied to the isAllowedImageUri argument",
                )
            }
        }
    }

    private fun assertSanitizedLinkSinkArg(
        fileName: String,
        source: String,
        sinkKind: String,
        sinkOffset: Int,
        rawArg: String,
    ) {
        if (rawArg.isBlank()) {
            fail("$fileName:${lineNumber(source, sinkOffset)} $sinkKind uses blank argument")
        }

        if (isDirectSanitizeInvocation(rawArg)) return

        val base = sanitizeCandidateBase(rawArg) ?: run {
            fail("$fileName:${lineNumber(source, sinkOffset)} $sinkKind uses unsupported argument: $rawArg")
            return
        }

        val safeVars = collectSafeLinkVarsInScope(source, sinkOffset)
        if (!safeVars.contains(base)) {
            fail(
                "$fileName:${lineNumber(source, sinkOffset)} $sinkKind argument `$base` is not one of the variables "
                    + "assigned from sanitizeLinkUri in scope",
            )
        }
    }

    private fun collectSafeLinkVarsInScope(source: String, sinkOffset: Int): Set<String> {
        val vars = linkedSetOf<String>()
        for (scopeStart in enclosingScopeStarts(source, sinkOffset).asReversed()) {
            val scopeSource = source.substring(scopeStart, sinkOffset)
            for (match in safeLinkAssignmentPattern.findAll(scopeSource)) {
                vars.add(match.groupValues[1])
            }
        }
        return vars
    }

    private fun collectSanitizeAssignmentsInScope(
        source: String,
        sinkOffset: Int,
    ): Map<String, String> {
        val assignments = linkedMapOf<String, String>()
        for (scopeStart in enclosingScopeStarts(source, sinkOffset).asReversed()) {
            val scopeSource = source.substring(scopeStart, sinkOffset)
            for (match in safeLinkAssignmentPattern.findAll(scopeSource)) {
                val varName = match.groupValues[1]
                val openParen = match.range.last
                val sanitizedArg = extractCallArgument(scopeSource, openParen).trim()
                if (sanitizedArg.isNotBlank()) {
                    assignments[varName] = normalizeExpression(sanitizedArg)
                }
            }
        }
        return assignments
    }

    private fun findEnclosingImageUriGuardArg(source: String, callOffset: Int): String? {
        val ifPattern = Regex("if\\s*\\((.*?)\\)\\s*\\{", RegexOption.DOT_MATCHES_ALL)
        val isAllowedPattern = Regex("isAllowedImageUri\\s*\\((.*?)\\)")
        for (match in ifPattern.findAll(source)) {
            val condition = match.groupValues[1]
            val guardMatch = isAllowedPattern.find(condition) ?: continue
            val openBrace = match.range.last
            val closeBrace = findMatchingBrace(source, openBrace)
            if (closeBrace >= 0 && callOffset in (openBrace + 1)..closeBrace) {
                return normalizeExpression(guardMatch.groupValues[1])
            }
        }
        return null
    }

    private fun isImageModelExpressionTiedToImageGuard(
        source: String,
        modelExprRaw: String,
        allowedExprRaw: String,
        sinkOffset: Int,
    ): Boolean {
        val modelExpr = normalizeExpression(modelExprRaw)
        val allowedExpr = normalizeExpression(allowedExprRaw)
        if (modelExpr == allowedExpr) return true

        val modelSanitizeArg = extractSanitizeArgFromExpression(modelExpr)
        if (modelSanitizeArg != null && normalizeExpression(modelSanitizeArg) == allowedExpr) return true

        if (isIdentifier(modelExpr)) {
            val assignments = collectSanitizeAssignmentsInScope(source, sinkOffset)
            val assignedFrom = assignments[modelExpr]
            if (assignedFrom == allowedExpr) return true
        }

        return false
    }

    private fun isDirectSanitizeInvocation(rawArg: String): Boolean {
        return directSanitizeInvocationPattern.matches(rawArg)
    }

    private fun sanitizeCandidateBase(rawArg: String): String? {
        val compact = rawArg
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .trimEnd(',')
            .trim()
        if (compact.startsWith("sanitizeLinkUri")) return null
        val withoutToUri = compact
            .removeSuffix(".toUri()")
            .removeSuffix("?.toUri()")
            .trim()
        return identifierPattern.matchEntire(withoutToUri)?.value
    }

    private fun normalizeExpression(raw: String): String {
        return raw.trim().trimEnd(',').replace(Regex("\\s+"), " ")
    }

    private fun isIdentifier(value: String): Boolean {
        return identifierPattern.matchEntire(value) != null
    }

    private fun extractSanitizeArgFromExpression(expression: String): String? {
        val trimmed = expression.trim()
        if (!trimmed.startsWith("sanitizeLinkUri(")) return null
        if (trimmed.count { it == '(' } != trimmed.count { it == ')' }) return null
        val openParen = trimmed.indexOf('(')
        val closeParen = findMatchingParen(trimmed, openParen)
        if (closeParen < 0 || closeParen != trimmed.lastIndex) return null
        return trimmed.substring(openParen + 1, closeParen).trim()
    }

    private fun enclosingScopeStarts(source: String, sinkOffset: Int): List<Int> {
        val braceStack = ArrayDeque<Int>()
        for (i in 0 until sinkOffset) {
            when (source[i]) {
                '{' -> braceStack.addLast(i)
                '}' -> if (braceStack.isNotEmpty()) braceStack.removeLast()
            }
        }
        return braceStack.asReversed()
    }

    private fun findMatchingBrace(source: String, openBraceIndex: Int): Int {
        if (openBraceIndex >= source.length || source[openBraceIndex] != '{') return -1
        var depth = 0
        for (i in openBraceIndex until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun findMatchingParen(source: String, openParenIndex: Int): Int {
        if (openParenIndex >= source.length || source[openParenIndex] != '(') return -1
        var depth = 0
        for (i in openParenIndex until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return i
                    }
                }
            }
        }
        return -1
    }

    private fun extractCallArgument(source: String, openParenIndex: Int): String {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openParenIndex until source.length) {
            val ch = source[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                    continue
                }
                if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return source.substring(openParenIndex + 1, i)
                }
            }
        }
        fail("Failed to parse call argument near offset $openParenIndex")
        return ""
    }

    private fun extractTopLevelExpression(source: String, start: Int): String {
        var depth = 0
        for (i in start until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                ',' -> if (depth == 0) return source.substring(start, i).trim().trimEnd(',')
            }
        }
        return source.substring(start).trim().trimEnd(',')
    }

    private fun lineNumber(source: String, offset: Int): Int =
        source.substring(0, offset).count { it == '\n' } + 1
}

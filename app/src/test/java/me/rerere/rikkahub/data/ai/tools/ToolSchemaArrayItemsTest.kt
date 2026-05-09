package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression test for GitHub issue #2 (body) — Gemini API rejects function declarations
 * whose `array`-type parameters lack an `items` field. This bit `mcp_add` / `mcp_update`
 * (`headers`) and `subagent_dispatch` (`tools`) in v2.1.16; users on Gemini providers
 * couldn't get a model response at all because every request failed schema validation.
 *
 * Strategy: scan every `LocalTools` / MCP / sub-agent / workflow tool source file,
 * find every `put("type", "array")` site, walk the enclosing `buildJsonObject { ... }`
 * brace pair, and assert the same block also contains a top-level `put("items", ...)`.
 *
 * Static-analysis style. Does not exercise the live tool factories (which need
 * Context + DataStore + Koin) but catches the exact regex shape the bug emits.
 */
class ToolSchemaArrayItemsTest {

    private val toolDirs: List<String> = listOf(
        "src/main/java/me/rerere/rikkahub/data/ai/tools/local",
        "src/main/java/me/rerere/rikkahub/data/ai/tools",
        "src/main/java/me/rerere/rikkahub/data/ai/mcp/control",
        "src/main/java/me/rerere/rikkahub/subagent",
        "src/main/java/me/rerere/rikkahub/workflow",
    )

    private fun resolveModuleRoot(): File {
        // JUnit's working dir is usually the module root. Fall back to walking up if
        // tests get invoked from the repo root.
        val cwdPath = System.getProperty("user.dir") ?: error("user.dir is not set")
        val cwd = File(cwdPath)
        if (File(cwd, "src/main").isDirectory) return cwd
        val maybeApp = File(cwd, "app")
        if (File(maybeApp, "src/main").isDirectory) return maybeApp
        error("Could not locate the :app module root from cwd=$cwd")
    }

    private fun collectKotlinFiles(): List<File> {
        val root = resolveModuleRoot()
        return toolDirs
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
    }

    /**
     * For a given file, return a list of (line-number, snippet) pairs where a
     * `put("type", "array")` declaration is present in a `buildJsonObject { ... }`
     * block that does NOT also contain a `put("items"` call. Walks brace depth to
     * locate the enclosing block for each match.
     */
    private fun findArrayBlocksMissingItems(file: File): List<Pair<Int, String>> {
        val text = file.readText()
        val findings = mutableListOf<Pair<Int, String>>()
        val arrayPattern = Regex("""put\s*\(\s*"type"\s*,\s*"array"\s*\)""")
        for (match in arrayPattern.findAll(text)) {
            val matchOffset = match.range.first
            // Walk backward to find the opening `buildJsonObject {` whose `{` is the
            // innermost unmatched opener at this point.
            val openIdx = findEnclosingBuildJsonObjectOpenBrace(text, matchOffset)
            if (openIdx < 0) continue  // not inside a buildJsonObject — skip
            val closeIdx = findMatchingCloseBrace(text, openIdx)
            if (closeIdx < 0) continue
            val block = text.substring(openIdx, closeIdx + 1)
            // Only the top-level (direct-child) puts in this block matter — deeper
            // nested put("items"...) on siblings would be a false positive.
            if (!hasTopLevelItemsPut(block)) {
                val lineNo = text.substring(0, matchOffset).count { it == '\n' } + 1
                findings += lineNo to match.value
            }
        }
        return findings
    }

    /** Find the `{` of the nearest enclosing `buildJsonObject {` (or `buildJsonArray {`). */
    private fun findEnclosingBuildJsonObjectOpenBrace(text: String, fromOffset: Int): Int {
        var depth = 0
        var i = fromOffset
        while (i > 0) {
            val c = text[i]
            when (c) {
                '}' -> depth++
                '{' -> {
                    if (depth == 0) {
                        // Look back for the keyword that opens this brace.
                        val before = text.substring(maxOf(0, i - 40), i).trimEnd()
                        if (before.endsWith("buildJsonObject") || before.endsWith("buildJsonArray")) {
                            return i
                        }
                        // Some other `{ ... }` (lambda, when, etc.) — keep walking out.
                        depth = 0
                    } else {
                        depth--
                    }
                }
            }
            i--
        }
        return -1
    }

    private fun findMatchingCloseBrace(text: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    /**
     * Strip nested `{ ... }` ranges then look for `put("items"` so we only see
     * direct-child puts of the current block.
     */
    private fun hasTopLevelItemsPut(block: String): Boolean {
        val sb = StringBuilder()
        var depth = 0
        for (c in block) {
            when (c) {
                '{' -> { if (depth == 0) sb.append(' ') else sb.append(' '); depth++ }
                '}' -> { depth--; sb.append(' ') }
                else -> sb.append(if (depth <= 1) c else ' ')
            }
        }
        return Regex("""put\s*\(\s*"items"""").containsMatchIn(sb)
    }

    @Test
    fun `every tool array property declares items so Gemini accepts the function declaration`() {
        val files = collectKotlinFiles()
        assertTrue("No tool source files found — test cwd may be wrong", files.isNotEmpty())
        val violations = files
            .map { it to findArrayBlocksMissingItems(it) }
            .filter { it.second.isNotEmpty() }
        if (violations.isNotEmpty()) {
            val report = buildString {
                appendLine("Found `array`-type tool parameters that don't declare `items`.")
                appendLine("Gemini API will reject the function declaration. Fix by adding")
                appendLine("`put(\"items\", buildJsonObject { put(\"type\", \"<element-type>\") })`")
                appendLine("inside the same buildJsonObject block.")
                appendLine()
                for ((file, sites) in violations) {
                    appendLine("- ${file.path}")
                    for ((line, snippet) in sites) {
                        appendLine("    line $line: $snippet")
                    }
                }
            }
            error(report)
        }
    }

    // --- self-tests for the walker so a future broken refactor of the helper itself
    //     gets caught instead of silently turning the regression test into a no-op. ---

    @Test
    fun `walker flags an array block missing items`() {
        val tmp = File.createTempFile("schema_missing_", ".kt")
        tmp.writeText("""
            fun bad() = buildJsonObject {
                put("headers", buildJsonObject {
                    put("type", "array")
                })
            }
        """.trimIndent())
        try {
            assertEquals(1, findArrayBlocksMissingItems(tmp).size)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `walker accepts an array block with items`() {
        val tmp = File.createTempFile("schema_ok_", ".kt")
        tmp.writeText("""
            fun ok() = buildJsonObject {
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "string") })
                })
            }
        """.trimIndent())
        try {
            assertEquals(0, findArrayBlocksMissingItems(tmp).size)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `walker treats sibling array blocks independently`() {
        val tmp = File.createTempFile("schema_mixed_", ".kt")
        tmp.writeText("""
            fun mixed() = buildJsonObject {
                put("ok", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject { put("type", "integer") })
                })
                put("bad", buildJsonObject {
                    put("type", "array")
                })
            }
        """.trimIndent())
        try {
            val findings = findArrayBlocksMissingItems(tmp)
            assertEquals("expected only the `bad` block to be flagged", 1, findings.size)
        } finally {
            tmp.delete()
        }
    }
}

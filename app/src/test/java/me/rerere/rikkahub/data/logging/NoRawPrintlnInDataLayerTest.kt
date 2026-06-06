package me.rerere.rikkahub.data.logging

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard: the production data layer must route diagnostics through the
 * central logging policy (android.util.Log), never through raw print or
 * Throwable.printStackTrace, which dump to System.out/System.err with no tag,
 * no severity, and bypass redaction. This is the "static grep" check #98 asks
 * for, scoped to app/src/main/java/me/rerere/rikkahub/data only.
 */
class NoRawPrintlnInDataLayerTest {

    private val forbidden = listOf(
        "println" to Regex("""\bprintln\s*\("""),
        "printStackTrace" to Regex("""\.printStackTrace\s*\("""),
        "System.out" to Regex("""\bSystem\.out\b"""),
        "System.err" to Regex("""\bSystem\.err\b"""),
    )

    @Test
    fun `data layer has no raw print or printStackTrace`() {
        val dataDir = resolveDataDir()
        assertTrue(
            "Could not locate the data source dir (CWD=${File("").absolutePath}); " +
                "test would otherwise pass vacuously",
            dataDir.isDirectory
        )

        val violations = mutableListOf<String>()
        dataDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                var inBlockComment = false
                file.readLines().forEachIndexed { index, raw ->
                    val line = stripComments(raw, inBlockComment).let { (text, stillInBlock) ->
                        inBlockComment = stillInBlock
                        text
                    }
                    if (line.isBlank()) return@forEachIndexed
                    forbidden.forEach { (name, regex) ->
                        if (regex.containsMatchIn(line)) {
                            violations += "${file.path}:${index + 1}: $name -> ${raw.trim()}"
                        }
                    }
                }
            }

        assertTrue(
            "Forbidden raw logging in data layer:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * Removes line- and block-comment regions so the scan only inspects live
     * code. Returns the comment-stripped line plus whether a block comment is
     * still open at end-of-line. Conservative: string literals are not parsed,
     * so a `//` inside a string would be over-stripped — acceptable for a guard
     * that only needs to avoid false positives on commented-out code.
     */
    private fun stripComments(raw: String, startInBlock: Boolean): Pair<String, Boolean> {
        val sb = StringBuilder()
        var i = 0
        var inBlock = startInBlock
        while (i < raw.length) {
            if (inBlock) {
                if (i + 1 < raw.length && raw[i] == '*' && raw[i + 1] == '/') {
                    inBlock = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (i + 1 < raw.length && raw[i] == '/' && raw[i + 1] == '*') {
                inBlock = true
                i += 2
                continue
            }
            if (i + 1 < raw.length && raw[i] == '/' && raw[i + 1] == '/') {
                break
            }
            sb.append(raw[i])
            i++
        }
        return sb.toString() to inBlock
    }

    private fun resolveDataDir(): File {
        val rel = "src/main/java/me/rerere/rikkahub/data"
        val moduleRelative = File(rel)
        if (moduleRelative.isDirectory) return moduleRelative
        val repoRootRelative = File("app/$rel")
        if (repoRootRelative.isDirectory) return repoRootRelative
        return moduleRelative
    }
}

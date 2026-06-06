package me.rerere.rikkahub.ui.pages

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard: the ui.pages layer must route diagnostics through the central
 * logging policy (android.util.Log), never through raw print or
 * Throwable.printStackTrace, which dump to System.out/System.err with no tag,
 * no severity, and bypass redaction. This mirrors NoRawPrintInUiComponentsTest
 * and NoRawPrintlnInDataLayerTest (the "static grep" check #98 asks for),
 * scoped to the app/.../ui/pages sources. Commented-out prints (e.g. in
 * ChatList.kt) are ignored by the comment-aware scanner.
 */
class NoRawPrintInUiPagesTest {

    private val forbidden = listOf(
        "println" to Regex("""\bprintln\s*\("""),
        "printStackTrace" to Regex("""\.printStackTrace\s*\("""),
        "System.out" to Regex("""\bSystem\.out\b"""),
        "System.err" to Regex("""\bSystem\.err\b"""),
    )

    private val scopedDirs = listOf(
        "src/main/java/me/rerere/rikkahub/ui/pages",
    )

    @Test
    fun `ui pages have no raw print or printStackTrace`() {
        val dirs = resolveScopedDirs()
        assertTrue(
            "Could not locate any scoped source dir (CWD=${File("").absolutePath}); " +
                "test would otherwise pass vacuously",
            dirs.isNotEmpty()
        )

        val violations = mutableListOf<String>()
        dirs.forEach { dir ->
            dir.walkTopDown()
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
        }

        assertTrue(
            "Forbidden raw logging in ui pages:\n" + violations.joinToString("\n"),
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

    private fun resolveScopedDirs(): List<File> {
        return scopedDirs.mapNotNull { rel ->
            val moduleRelative = File(rel)
            if (moduleRelative.isDirectory) return@mapNotNull moduleRelative
            val repoRootRelative = File("app/$rel")
            if (repoRootRelative.isDirectory) return@mapNotNull repoRootRelative
            null
        }
    }
}

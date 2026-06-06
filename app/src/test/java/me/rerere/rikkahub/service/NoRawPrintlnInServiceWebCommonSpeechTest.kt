package me.rerere.rikkahub.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the final logging slice (#98): the service, web, common, and
 * speech production sources must route diagnostics through the central logging
 * policy (android.util.Log), never through raw print or Throwable.printStackTrace,
 * which dump to System.out/System.err with no tag, no severity, and bypass
 * redaction. This is the "static grep" check #98 asks for, scoped to:
 *   - app/src/main/java/me/rerere/rikkahub/service
 *   - app/src/main/java/me/rerere/rikkahub/web
 *   - common/src/main
 *   - speech/src/main
 */
class NoRawPrintlnInServiceWebCommonSpeechTest {

    private val forbidden = listOf(
        "println" to Regex("""\bprintln\s*\("""),
        "printStackTrace" to Regex("""\.printStackTrace\s*\("""),
        "System.out" to Regex("""\bSystem\.out\b"""),
        "System.err" to Regex("""\bSystem\.err\b"""),
    )

    @Test
    fun `service web common speech have no raw print or printStackTrace`() {
        val roots = resolveRoots()

        val violations = mutableListOf<String>()
        roots.forEach { root ->
            assertTrue(
                "Could not locate a guarded source dir (CWD=${File("").absolutePath}): " +
                    "$root; test would otherwise pass vacuously",
                root.isDirectory
            )
            root.walkTopDown()
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
            "Forbidden raw logging in service/web/common/speech:\n" + violations.joinToString("\n"),
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

    /**
     * Resolves the four guarded roots. The unit test runs with the app module as
     * CWD, so service/web live under `src/main/...` (with an `app/...` repo-root
     * fallback), while common/speech are sibling modules reachable via `../`
     * (with a repo-root fallback for when CWD is the repo root).
     */
    private fun resolveRoots(): List<File> = listOf(
        firstExisting(
            "src/main/java/me/rerere/rikkahub/service",
            "app/src/main/java/me/rerere/rikkahub/service",
        ),
        firstExisting(
            "src/main/java/me/rerere/rikkahub/web",
            "app/src/main/java/me/rerere/rikkahub/web",
        ),
        firstExisting(
            "../common/src/main",
            "common/src/main",
        ),
        firstExisting(
            "../speech/src/main",
            "speech/src/main",
        ),
    )

    private fun firstExisting(vararg candidates: String): File {
        candidates.forEach { c ->
            val f = File(c)
            if (f.isDirectory) return f
        }
        return File(candidates.first())
    }
}

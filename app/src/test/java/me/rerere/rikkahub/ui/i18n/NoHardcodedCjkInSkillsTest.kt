package me.rerere.rikkahub.ui.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for the skills localization slice (#191): the scoped skills
 * sources (the two ViewModels plus the page) must resolve every user-facing
 * string through the resource system (context.getString / stringResource),
 * never embed a CJK literal at the call site, so non-Chinese locales render
 * translated text. The import-error path is in scope too: the `error(...)`
 * messages thrown inside the import helpers escape into launchEmitting's
 * onError, becoming SkillsEvent.ImportFailed(message) shown verbatim by the
 * page — so those strings are user-facing, not dev-only.
 *
 * The scan strips `//` and `/* */` comments before inspecting each line, so any
 * intentionally-kept Chinese inline comment in these files does NOT trip the
 * guard — only live code is checked. A CJK char (CJK Unified Ideographs block
 * U+4E00..U+9FFF) surviving in stripped code is a violation.
 *
 * Scoped to exactly the three files this slice owns:
 *   - ui/pages/extensions/SkillsVM.kt
 *   - ui/pages/extensions/SkillDetailVM.kt
 *   - ui/pages/extensions/SkillsPage.kt
 */
class NoHardcodedCjkInSkillsTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun `scoped skills sources have no hardcoded cjk literals`() {
        val files = resolveFiles()

        val violations = mutableListOf<String>()
        files.forEach { file ->
            assertTrue(
                "Could not locate a scoped source file (CWD=${File("").absolutePath}): " +
                    "${file.path}; test would otherwise pass vacuously",
                file.isFile
            )
            var inBlockComment = false
            file.readLines().forEachIndexed { index, raw ->
                val line = stripComments(raw, inBlockComment).let { (text, stillInBlock) ->
                    inBlockComment = stillInBlock
                    text
                }
                if (line.isBlank()) return@forEachIndexed
                if (cjk.containsMatchIn(line)) {
                    violations += "${file.path}:${index + 1}: ${raw.trim()}"
                }
            }
        }

        assertTrue(
            "Hardcoded CJK literals in scoped skills sources " +
                "(extract via locale-tui + context.getString/stringResource):\n" +
                violations.joinToString("\n"),
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
     * Resolves the three scoped files. The unit test runs with the app module as
     * CWD, so sources live under `src/main/...`, with an `app/src/main/...`
     * repo-root fallback for when CWD is the repo root.
     */
    private fun resolveFiles(): List<File> = listOf(
        "ui/pages/extensions/SkillsVM.kt",
        "ui/pages/extensions/SkillDetailVM.kt",
        "ui/pages/extensions/SkillsPage.kt",
    ).map { rel ->
        firstExisting(
            "src/main/java/me/rerere/rikkahub/$rel",
            "app/src/main/java/me/rerere/rikkahub/$rel",
        )
    }

    private fun firstExisting(vararg candidates: String): File {
        candidates.forEach { c ->
            val f = File(c)
            if (f.isFile) return f
        }
        return File(candidates.first())
    }
}

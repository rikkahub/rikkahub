package me.rerere.rikkahub.ui.pages.debug

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guard for issue #191 (debug slice): the developer-only Debug screen
 * (DebugPage.kt + DebugVM.kt) must carry no Chinese / CJK literals. None of these
 * strings are parsed at runtime — they are toast text, button labels, a dev crash
 * message, conversation titles, and randomized filler — so plain English is a pure
 * no-behavior-change cleanup. This mirrors the existing static-source-guard
 * convention in NoRawPrintlnInServiceWebCommonSpeechTest and is the unit/JVM kind
 * CI runs (./gradlew testDebugUnitTest) — no emulator, no network.
 */
class NoChineseLiteralsInDebugPageTest {

    // Han ideographs + CJK Ext A + CJK symbols/punctuation + full-width forms.
    private val cjk = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf\\u3000-\\u303f\\uff00-\\uffef]")

    @Test
    fun `debug page and view model have no CJK literals`() {
        val files = listOf(
            firstExisting(
                "src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugPage.kt",
                "app/src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugPage.kt",
            ),
            firstExisting(
                "src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugVM.kt",
                "app/src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugVM.kt",
            ),
        )

        val violations = mutableListOf<String>()
        files.forEach { file ->
            assertTrue(
                "Could not locate a guarded debug source (CWD=${File("").absolutePath}): " +
                    "${file.path}; test would otherwise pass vacuously",
                file.isFile
            )
            file.readLines().forEachIndexed { index, line ->
                if (cjk.containsMatchIn(line)) {
                    violations += "${file.path}:${index + 1}: ${line.trim()}"
                }
            }
        }

        assertTrue(
            "CJK literals remain in the developer-only debug screen:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
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

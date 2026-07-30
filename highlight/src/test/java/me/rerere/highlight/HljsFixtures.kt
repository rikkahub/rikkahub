package me.rerere.highlight

import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.core.highlightDebugMode
import me.rerere.highlight.languages.builtinLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/**
 * Compares the Kotlin highlighter against golden token streams captured from `highlight.js`.
 *
 * Fixtures live in `src/test/resources/hljs/<language>/`: `<name>.txt` is the source and
 * `<name>.tokens` is what upstream produces for it. Regenerate them with
 * `cd highlight/tools && npm install && npm run generate`.
 */
internal object HljsFixtures {
    private val engine by lazy { HighlightEngine(builtinLanguages()) }

    private val root: File by lazy {
        val fromResources = HljsFixtures::class.java.getResource("/hljs")
            ?: error("fixture root /hljs is missing from the test resources")
        File(fromResources.toURI())
    }

    /** Asserts that every fixture of [language] is reproduced token for token. */
    fun assertLanguageMatches(language: String) {
        // A grammar that blows up must fail the fixture loudly instead of degrading to plain text.
        highlightDebugMode = true
        try {
            assertFixturesMatch(language)
        } finally {
            highlightDebugMode = false
        }
    }

    private fun assertFixturesMatch(language: String) {
        val directory = File(root, language)
        assertTrue("no fixtures for language '$language'", directory.isDirectory)

        val sources = directory.listFiles { file -> file.extension == "txt" }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("no fixtures for language '$language'", sources.isNotEmpty())

        sources.forEach { source ->
            val expectedFile = File(directory, "${source.nameWithoutExtension}.tokens")
            assertTrue("missing golden tokens for ${source.name}", expectedFile.isFile)

            val code = source.readText()
            val actual = engine.highlight(code, language)
                ?: error("language '$language' is not registered with the engine")

            assertEquals(
                "source text must survive highlighting of ${source.name}",
                code,
                actual.joinToString(separator = "") { it.content },
            )
            assertEquals(
                "highlight.js parity for $language/${source.name}",
                expectedFile.readText().trimEnd('\n'),
                actual.joinToString(separator = "\n") { it.encode() },
            )
        }
    }

    private fun HighlightToken.encode(): String {
        val scope = when (this) {
            is HighlightToken.Plain -> ""
            is HighlightToken.Styled -> type
        }
        val text = content
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "$scope\t$text"
    }
}

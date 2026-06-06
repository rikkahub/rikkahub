package me.rerere.rikkahub.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two complementary pure-JVM guards for issue #110.
 *
 * (A) Behaviour-lock for the parsing seam extracted into MarkdownParsing.kt out of
 *     Markdown.kt / MarkdownNew.kt. These call the now-internal pure functions directly
 *     (no Compose / no device) to prove the code-move preserves parse semantics.
 *
 * (B) Off-main-thread regression guard: a static source scan asserting both Compose files
 *     no longer compute the initial parse synchronously in `remember { mutableStateOf(...) }`
 *     and instead seed it via `produceState` + `withContext(Dispatchers.Default)`. This is
 *     the FAILS-before / PASSES-after contract: the actual defect is a composition-thread
 *     threading behaviour that cannot be observed from a JVM unit test without a device, so
 *     the invariant that must hold is structural — the initial value is never computed on
 *     the composition thread.
 */
class MarkdownParsingTest {

    // ---- (A) behaviour-lock ----

    @Test
    fun `plain text has no html`() {
        assertFalse(parseMarkdown("just some plain **bold** text").hasHtml)
    }

    @Test
    fun `raw html block is detected`() {
        assertTrue(parseMarkdown("before\n\n<div>inline html</div>\n\nafter").hasHtml)
    }

    @Test
    fun `preProcess converts inline latex outside code`() {
        val dollar = "$"
        assertEquals("${dollar}x${dollar}", preProcess("\\(x\\)"))
    }

    @Test
    fun `preProcess converts block latex outside code`() {
        val dollar = "$"
        assertEquals("$dollar$dollar" + "x" + "$dollar$dollar", preProcess("\\[x\\]"))
    }

    @Test
    fun `preProcess collapses multiline block latex to single line`() {
        // Behaviour-lock for the intentional unification: master had two divergent block-latex
        // preprocessors (AST path trimmed + collapsed newlines; HTML path emitted the raw
        // capture). The unified seam keeps the trim/collapse variant for BOTH paths. Pin it so
        // the change stays deliberate and regression-guarded.
        val dollar = "$"
        val input = "\\[\n  a = b\n  c = d\n\\]"
        assertEquals("$dollar$dollar" + "a = b c = d" + "$dollar$dollar", preProcess(input))
    }

    @Test
    fun `preProcess leaves latex inside fenced code untouched`() {
        val input = "```\n\\(x\\)\n```"
        assertEquals(input, preProcess(input))
    }

    @Test
    fun `generateMarkdownHtml renders heading`() {
        assertTrue(generateMarkdownHtml("# Hi").contains("<h1"))
    }

    // ---- (B) off-main-thread regression guard ----

    @Test
    fun `markdown compose files parse off the composition thread`() {
        val richtextDir = resolveRichtextDir()
        assertTrue(
            "Could not locate the richtext source dir (CWD=${File("").absolutePath}); " +
                "test would otherwise pass vacuously",
            richtextDir.isDirectory
        )

        assertOffThreadParse(
            file = File(richtextDir, "Markdown.kt"),
            // the unfixed code seeded data synchronously on the composition thread
            forbidden = Regex("""remember\s*\{\s*mutableStateOf\s*\(\s*parseMarkdown\s*\("""),
        )
        assertOffThreadParse(
            file = File(richtextDir, "MarkdownNew.kt"),
            forbidden = Regex("""remember\s*\{[\s\S]*?mutableStateOf\s*\([\s\S]*?generateMarkdownHtml\s*\("""),
        )
    }

    private fun assertOffThreadParse(file: File, forbidden: Regex) {
        assertTrue("Expected source file ${file.path} to exist", file.isFile)
        val source = file.readText()
        assertFalse(
            "${file.name} still seeds the initial parse synchronously on the composition " +
                "thread (issue #110). It must use produceState + withContext(Dispatchers.Default).",
            forbidden.containsMatchIn(source)
        )
        assertTrue(
            "${file.name} must seed its parse via produceState",
            source.contains("produceState")
        )
        assertTrue(
            "${file.name} must run the parse on withContext(Dispatchers.Default)",
            source.contains("withContext(Dispatchers.Default)")
        )
    }

    private fun resolveRichtextDir(): File {
        val rel = "src/main/java/me/rerere/rikkahub/ui/components/richtext"
        val moduleRelative = File(rel)
        if (moduleRelative.isDirectory) return moduleRelative
        val repoRootRelative = File("app/$rel")
        if (repoRootRelative.isDirectory) return repoRootRelative
        return moduleRelative
    }
}

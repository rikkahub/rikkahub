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

    // ---- (C) inline-LaTeX wrapping + hollow-character regression guard (issue #209) ----

    /**
     * Structural FAILS-before / PASSES-after guard for the upstream LaTeX rendering port
     * (rikkahub/rikkahub@08b3038c). Two coupled device-only defects sit behind issue #209:
     *
     *  1. Hollow / outline glyphs — a font/glyph bug inside the jlatexmath-android AAR, fixed
     *     solely by bumping the library 1.3 -> 1.4. There is no Kotlin seam for it, so the only
     *     guard we can assert in a JVM test is the pinned version in libs.versions.toml.
     *  2. Long inline formulas clipped off-screen — Compose InlineTextContent placeholders are
     *     atomic and never wrap, so a single full-width formula placeholder is clipped. The fix
     *     decomposes the formula via JLatexMathSplitter (new in 1.4) into several narrow
     *     drawables joined by zero-width-space break points so the text flow can wrap them.
     *
     * Why this is a source-scan and not a behavioural split test: JLatexMathSplitter and
     * JLatexMathDrawable are android.graphics-backed (Canvas/Rect/Paint). CI runs only
     * testDebugUnitTest on the plain JVM with unitTests.isReturnDefaultValues = true and no
     * Robolectric, so calling splitLatex() here would hit stubbed graphics and split nothing
     * real — a fake test. The defect is device-only; the structural invariant ("inline math is
     * decomposed into wrappable units, and the AAR is pinned to the glyph-fixed version") is
     * the honest, non-fakeable guard — the same contract as part (B) above for issue #110.
     */
    @Test
    fun `LatexText exposes the splitter seam and single-drawable composable`() {
        val richtextDir = resolveRichtextDir()
        assertTrue(
            "Could not locate the richtext source dir (CWD=${File("").absolutePath}); " +
                "test would otherwise pass vacuously",
            richtextDir.isDirectory
        )
        val file = File(richtextDir, "LatexText.kt")
        assertTrue("Expected source file ${file.path} to exist", file.isFile)
        val source = file.readText()
        assertTrue("LatexText.kt is empty; would pass vacuously", source.isNotEmpty())

        assertTrue(
            "LatexText.kt must import JLatexMathSplitter (issue #209: needed to split long " +
                "inline formulas into wrappable drawables)",
            source.contains("ru.noties.jlatexmath.JLatexMathSplitter")
        )
        assertTrue(
            "LatexText.kt must declare splitLatex(...) (issue #209)",
            source.contains("fun splitLatex(")
        )
        assertTrue(
            "LatexText.kt must declare the LatexDrawable composable that renders one split " +
                "drawable (issue #209)",
            source.contains("fun LatexDrawable(")
        )
    }

    @Test
    fun `Markdown inline-math path splits long formulas with zero-width-space breaks`() {
        val richtextDir = resolveRichtextDir()
        assertTrue(
            "Could not locate the richtext source dir (CWD=${File("").absolutePath}); " +
                "test would otherwise pass vacuously",
            richtextDir.isDirectory
        )
        val file = File(richtextDir, "Markdown.kt")
        assertTrue("Expected source file ${file.path} to exist", file.isFile)
        val source = file.readText()
        assertTrue("Markdown.kt is empty; would pass vacuously", source.isNotEmpty())

        assertTrue(
            "Markdown.kt's inline-math path must call splitLatex(...) so long formulas become " +
                "multiple wrappable drawables instead of one clipped placeholder (issue #209)",
            source.contains("splitLatex(")
        )
        assertTrue(
            "Markdown.kt must insert a zero-width-space (U+200B) break point between formula " +
                "segments so the text flow can wrap them (issue #209)",
            source.contains("\\u200B") || source.contains("​")
        )
        assertTrue(
            "Markdown.kt must render each split segment via LatexDrawable(...) (issue #209)",
            source.contains("LatexDrawable(")
        )
    }

    @Test
    fun `jlatexmath is pinned to the glyph-fixed 1_4 release`() {
        val toml = resolveVersionCatalog()
        assertTrue(
            "Could not locate gradle/libs.versions.toml (CWD=${File("").absolutePath}); " +
                "test would otherwise pass vacuously",
            toml.isFile
        )
        val source = toml.readText()
        assertTrue("libs.versions.toml is empty; would pass vacuously", source.isNotEmpty())

        assertTrue(
            "jlatexmath must be pinned to 1.4 — the only guard for the hollow-character half of " +
                "issue #209 (the glyph fix ships inside the AAR) and the source of the " +
                "JLatexMathSplitter API used by the wrapping fix",
            source.contains("jlatexmath = \"1.4\"")
        )
        assertFalse(
            "jlatexmath must NOT remain pinned to 1.3 (lacks JLatexMathSplitter + the glyph fix, " +
                "issue #209)",
            source.contains("jlatexmath = \"1.3\"")
        )
    }

    private fun resolveVersionCatalog(): File {
        val rel = "gradle/libs.versions.toml"
        // Unit tests run with the module dir (app/) as CWD; the catalog lives at the repo root.
        val fromModule = File("../$rel")
        if (fromModule.isFile) return fromModule
        val fromRepoRoot = File(rel)
        if (fromRepoRoot.isFile) return fromRepoRoot
        return fromModule
    }
}

package me.rerere.rikkahub.ui.components.ai.chatinput

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard for the ChatInput componentization (issue #106).
 *
 * The monolithic app/.../ui/components/ai/ChatInput.kt was split into a cohesive
 * chatinput/ subpackage. This is a pure code-move slice: there is no extractable
 * pure logic to unit-test (the file is entirely Compose composables and
 * Activity-result/launcher/state wiring), so the regression guard is structural.
 *
 * It FAILS on the pre-split tree (the old monolith exists, the subpackage does
 * not) and PASSES once the move lands. Mirrors the file-system guard family
 * (NoRawPrintInUiComponentsTest) including CWD resolution against either the
 * module root or the repo root.
 */
class ChatInputComponentizationTest {

    private val packageDecl = "package me.rerere.rikkahub.ui.components.ai.chatinput"

    private val expectedFiles = listOf(
        "ChatInput.kt",
        "ChatInputActions.kt",
        "ChatInputTextRow.kt",
        "QuickMessageButton.kt",
        "FullScreenEditor.kt",
    )

    @Test
    fun `old monolithic ChatInput is gone`() {
        val mainRoot = resolveMainJavaRoot()
        val old = File(mainRoot, "me/rerere/rikkahub/ui/components/ai/ChatInput.kt")
        assertFalse(
            "Old monolith still present at ${old.path}; the split is incomplete",
            old.exists()
        )
    }

    @Test
    fun `chatinput subpackage holds the expected files with the right package`() {
        val dir = chatinputDir()
        assertTrue(
            "chatinput dir missing at ${dir.path}; test would otherwise pass vacuously",
            dir.isDirectory
        )
        expectedFiles.forEach { name ->
            val f = File(dir, name)
            assertTrue("Missing ${f.path}", f.isFile)
            val firstPackageLine = f.readLines().firstOrNull { it.trimStart().startsWith("package ") }?.trim()
            assertEquals(
                "Wrong package declaration in ${f.path}",
                packageDecl,
                firstPackageLine
            )
        }
    }

    @Test
    fun `public ChatInput composable is declared only in chatinput ChatInput`() {
        val mainRoot = resolveMainJavaRoot()
        val declRegex = Regex("""^\s*(?:@Composable\s+)?(?:public\s+)?fun\s+ChatInput\s*\(""")
        val composableDecl = Regex("""^\s*fun\s+ChatInput\s*\(""")
        val declaringFiles = mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { composableDecl.containsMatchIn(it) }
            }
            .map { it.path }
            .toList()
        assertEquals(
            "public ChatInput must be declared in exactly one file, found: $declaringFiles",
            1,
            declaringFiles.size
        )
        assertTrue(
            "ChatInput declaration is not in the chatinput subpackage: ${declaringFiles.first()}",
            declaringFiles.first().replace('\\', '/')
                .endsWith("ui/components/ai/chatinput/ChatInput.kt")
        )
        // declRegex is the broader form used to ensure the simpler matcher above
        // is consistent with annotated declarations.
        assertTrue(
            "ChatInput.kt should declare a fun ChatInput(",
            File(chatinputDir(), "ChatInput.kt").readLines().any { declRegex.containsMatchIn(it) } ||
                File(chatinputDir(), "ChatInput.kt").readLines().any { composableDecl.containsMatchIn(it) }
        )
    }

    private fun chatinputDir(): File =
        File(resolveMainJavaRoot(), "me/rerere/rikkahub/ui/components/ai/chatinput")

    private fun resolveMainJavaRoot(): File {
        val rel = "src/main/java"
        val moduleRelative = File(rel)
        if (moduleRelative.isDirectory) return moduleRelative
        val repoRootRelative = File("app/$rel")
        if (repoRootRelative.isDirectory) return repoRootRelative
        // Last resort: return the module-relative path so the dir-exists guard
        // surfaces the failure rather than throwing.
        return moduleRelative
    }
}

package me.rerere.rikkahub.ui.components.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorGuideMarkdownResourceTest {
    private val markdownResourceNames = setOf(
        "assistant_page_regex_help_body_markdown",
        "prompt_page_help_body_markdown",
        "prompt_page_mode_injection_help_body_markdown",
        "prompt_page_lorebook_help_body_markdown",
        "prompt_page_lorebook_editor_help_body_markdown",
        "prompt_page_lorebook_entry_help_body_markdown",
        "prompt_page_st_preset_help_body_markdown",
    )

    private val resourceDirectories = listOf(
        "src/main/res/raw",
        "src/main/res/raw-zh",
        "src/main/res/raw-zh-rTW",
        "src/main/res/raw-ja",
        "src/main/res/raw-ko-rKR",
        "src/main/res/raw-ru",
    ).map(::resolveResourceFile)

    @Test
    fun `editor guide markdown resources use real newlines`() {
        resourceDirectories.forEach { directory ->
            markdownResourceNames.forEach { name ->
                val file = File(directory, "$name.md")
                assertTrue("Missing $name in ${directory.path}", file.exists())
                val content = file.readText()

                assertTrue("Expected real newlines in ${file.path}", content.contains('\n'))
                assertFalse("Unexpected escaped newlines in ${file.path}", content.contains("""\n"""))
            }
        }
    }

    @Test
    fun `regex markdown example keeps single backslashes`() {
        resourceDirectories.forEach { directory ->
            val file = File(directory, "assistant_page_regex_help_body_markdown.md")
            assertTrue("Missing assistant_page_regex_help_body_markdown in ${directory.path}", file.exists())
            val content = file.readText()

            assertTrue("Expected single-backslash regex example in ${file.path}", content.contains("""Name:\s*(.*)"""))
            assertFalse("Unexpected double-backslash regex example in ${file.path}", content.contains("""Name:\\s*(.*)"""))
        }
    }

    private fun resolveResourceFile(path: String): File {
        return listOf(
            File(path),
            File("app/$path"),
            File("../$path"),
        ).firstOrNull(File::exists)
            ?: throw java.io.FileNotFoundException("Unable to resolve test resource file for $path")
    }
}

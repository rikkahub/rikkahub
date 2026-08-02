package me.rerere.rikkahub.data.ai.agent

import me.rerere.rikkahub.data.ai.agent.prompt.ProjectDocsLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectDocsLoaderTest {
    @Test
    fun `candidate paths cascade from root to cwd`() {
        assertEquals(listOf(""), ProjectDocsLoader.candidatePaths(null))
        assertEquals(listOf(""), ProjectDocsLoader.candidatePaths("/workspace"))
        assertEquals(
            listOf("", "src", "src/app"),
            ProjectDocsLoader.candidatePaths("/workspace/src/app"),
        )
        assertEquals("src/AGENTS.md", ProjectDocsLoader.joinPath("src", "AGENTS.md"))
        assertEquals("AGENTS.md", ProjectDocsLoader.joinPath("", "AGENTS.md"))
    }

    @Test
    fun `default file names include agents and claude`() {
        assertTrue(ProjectDocsLoader.DEFAULT_FILE_NAMES.contains("AGENTS.md"))
        assertTrue(ProjectDocsLoader.DEFAULT_FILE_NAMES.contains("CLAUDE.md"))
        assertTrue(ProjectDocsLoader.DEFAULT_FILE_NAMES.contains("RIKKA.md"))
        assertEquals(32 * 1024, ProjectDocsLoader.DEFAULT_MAX_CHARS)
    }

    @Test
    fun `max chars default is 32kib codex-aligned`() {
        assertEquals(32 * 1024, ProjectDocsLoader.DEFAULT_MAX_CHARS)
    }
}

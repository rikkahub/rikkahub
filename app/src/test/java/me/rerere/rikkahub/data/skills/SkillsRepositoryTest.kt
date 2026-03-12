package me.rerere.rikkahub.data.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.tools.termux.TermuxRunCommandRequest

class SkillsRepositoryTest {
    @Test
    fun `toRefreshingCatalogState should clear cached entries and errors`() {
        val initial = SkillsCatalogState(
            workdir = "/old",
            rootPath = "/old/skills",
            entries = listOf(
                SkillCatalogEntry(
                    directoryName = "old-skill",
                    path = "/old/skills/old-skill",
                    name = "old-skill",
                    description = "Old description",
                )
            ),
            invalidEntries = listOf(
                SkillInvalidEntry(
                    directoryName = "broken-skill",
                    path = "/old/skills/broken-skill",
                    reason = SkillInvalidReason.MissingSkillFile,
                )
            ),
            error = "Previous failure",
            refreshedAt = 123L,
        )

        val loading = initial.toRefreshingCatalogState(
            workdir = "/new",
            rootPath = "/new/skills",
        )

        assertEquals("/new", loading.workdir)
        assertEquals("/new/skills", loading.rootPath)
        assertTrue(loading.entries.isEmpty())
        assertTrue(loading.invalidEntries.isEmpty())
        assertTrue(loading.isLoading)
        assertNull(loading.error)
        assertEquals(123L, loading.refreshedAt)
    }

    @Test
    fun `discoverCatalogEntries should keep valid entries when one skill file is unreadable`() = runBlocking {
        val result = discoverCatalogEntries(
            directories = listOf(
                SkillDirectoryDescriptor(
                    directoryName = "alpha",
                    path = "/skills/alpha",
                    hasSkillFile = true,
                ),
                SkillDirectoryDescriptor(
                    directoryName = "broken",
                    path = "/skills/broken",
                    hasSkillFile = true,
                ),
            ),
            readSkillFile = { path ->
                when (path) {
                    "/skills/alpha" -> {
                        """
                        ---
                        name: alpha
                        description: Valid skill
                        ---
                        """.trimIndent()
                    }

                    "/skills/broken" -> error("Permission denied")
                    else -> error("Unexpected path: $path")
                }
            },
        )

        assertEquals(
            listOf(
                SkillCatalogEntry(
                    directoryName = "alpha",
                    path = "/skills/alpha",
                    name = "alpha",
                    description = "Valid skill",
                )
            ),
            result.entries,
        )
        assertEquals(1, result.invalidEntries.size)
        assertEquals("broken", result.invalidEntries.single().directoryName)
        assertEquals("/skills/broken", result.invalidEntries.single().path)
        assertEquals(
            SkillInvalidReason.FailedToRead("Permission denied"),
            result.invalidEntries.single().reason,
        )
    }

    @Test
    fun `buildSkillCommandRequest should always use background Termux execution`() {
        val request = buildSkillCommandRequest(
            script = "echo ok",
            workdir = "/data/data/com.termux/files/home",
            label = "RikkaHub list local skills",
        )

        assertEquals(
            TermuxRunCommandRequest(
                commandPath = "/data/data/com.termux/files/usr/bin/bash",
                arguments = listOf("-lc", "echo ok"),
                workdir = "/data/data/com.termux/files/home",
                background = true,
                timeoutMs = 30_000L,
                label = "RikkaHub list local skills",
            ),
            request,
        )
    }
}

package me.rerere.rikkahub.data.skills

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
                    reason = "Missing SKILL.md",
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
            "Failed to read SKILL.md: Permission denied",
            result.invalidEntries.single().reason,
        )
    }
}

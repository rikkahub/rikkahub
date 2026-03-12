package me.rerere.rikkahub.data.skills

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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

    @Test
    fun `sanitizeSkillDirectoryName should normalize unsupported characters`() {
        assertEquals("my-skill-v2", sanitizeSkillDirectoryName(" My Skill V2! "))
        assertEquals("skill-import", sanitizeSkillDirectoryName("技能包", fallback = "skill-import"))
    }

    @Test
    fun `buildSkillMarkdown should roundtrip quoted frontmatter values`() {
        val markdown = buildSkillMarkdown(
            name = "Alice \"Helper\"",
            description = "Line one",
            body = "",
        )

        val parsed = parseSkillFrontmatter(markdown)
        assertTrue(parsed is SkillFrontmatterParseResult.Success)
        parsed as SkillFrontmatterParseResult.Success
        assertEquals("Alice \"Helper\"", parsed.frontmatter.name)
        assertEquals("Line one", parsed.frontmatter.description)
        assertTrue(markdown.contains("# Instructions"))
    }

    @Test
    fun `normalizeSkillArchiveEntryPath should reject traversal`() {
        val result = runCatching {
            normalizeSkillArchiveEntryPath("../danger.sh")
        }

        assertTrue(result.isFailure)
    }

    @Test
    fun `collapseSkillArchiveContainerLayers should strip common outer folder`() {
        val collapsed = collapseSkillArchiveContainerLayers(
            ParsedSkillArchive(
                directories = linkedSetOf("skills", "skills/demo", "skills/demo/scripts"),
                files = listOf(
                    SkillArchiveFile("skills/demo/SKILL.md", "---\nname: demo\ndescription: ok\n---".toByteArray()),
                    SkillArchiveFile("skills/demo/scripts/run.sh", "echo ok".toByteArray()),
                ),
            )
        )

        assertEquals(setOf("demo", "demo/scripts"), collapsed.directories)
        assertEquals(
            listOf("demo/SKILL.md", "demo/scripts/run.sh"),
            collapsed.files.map { it.path },
        )
    }

    @Test
    fun `buildSkillImportPlan should wrap root files and suffix conflicting directory names`() {
        val archive = ParsedSkillArchive(
            directories = linkedSetOf("scripts"),
            files = listOf(
                SkillArchiveFile(
                    path = "SKILL.md",
                    bytes = buildSkillMarkdown(
                        name = "Demo Skill",
                        description = "Imported",
                        body = "",
                    ).toByteArray()
                ),
                SkillArchiveFile(
                    path = "scripts/run.sh",
                    bytes = "echo ok".toByteArray(),
                ),
            ),
        )

        val plan = buildSkillImportPlan(
            archive = archive,
            suggestedDirectoryName = "demo-skill",
            existingDirectoryNames = setOf("demo-skill"),
        )

        assertEquals(listOf("demo-skill-2"), plan.topLevelDirectories)
        assertEquals(
            setOf("demo-skill-2", "demo-skill-2/scripts"),
            plan.directories,
        )
        assertEquals(
            setOf("demo-skill-2/SKILL.md", "demo-skill-2/scripts/run.sh"),
            plan.files.map { it.path }.toSet(),
        )
    }

    @Test
    fun `parseSkillArchive should ignore metadata files and flatten outer directory`() {
        val output = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("__MACOSX/"))
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("skills/demo/SKILL.md"))
                zip.write(buildSkillMarkdown("Demo", "Imported", "").toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("skills/demo/.DS_Store"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
        }
        val archiveBytes = output.toByteArray()

        val parsed = parseSkillArchive(ByteArrayInputStream(archiveBytes))

        assertEquals(listOf("demo/SKILL.md"), parsed.files.map { it.path })
        assertEquals(setOf("demo"), parsed.directories)
    }
}

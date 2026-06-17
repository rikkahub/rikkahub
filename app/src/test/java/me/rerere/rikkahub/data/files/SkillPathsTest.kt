package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillPathsTest {
    @Test
    fun `parse supports CRLF frontmatter`() {
        val content = "---\r\nname: test-skill\r\ndescription: test\r\n---\r\n\r\nbody"

        val frontmatter = SkillFrontmatterParser.parse(content)

        assertEquals("test-skill", frontmatter["name"])
        assertEquals("test", frontmatter["description"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun `resolve skill dir rejects traversal and nested names`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()

        try {
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "../upload"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo/bar"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo\\bar"))
            assertNotNull(SkillPaths.resolveSkillDir(skillsRoot, "valid-skill"))
        } finally {
            skillsRoot.deleteRecursively()
        }
    }

    @Test
    fun `resolve skill file rejects sibling prefix escape`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()
        val skillDir = File(skillsRoot, "foo").apply { mkdirs() }
        File(skillsRoot, "foobar").apply { mkdirs() }

        try {
            val safeFile = SkillPaths.resolveSkillFile(skillDir, "notes.md")
            val escapedFile = SkillPaths.resolveSkillFile(skillDir, "../foobar/secret.md")

            assertEquals(File(skillDir, "notes.md").canonicalFile, safeFile)
            assertNull(escapedFile)
        } finally {
            skillsRoot.deleteRecursively()
        }
    }

    // Backup-restore reuses resolveSkillFile to guard the upload folder against zip-slip:
    // a malicious backup entry like "upload/../../databases/rikka_hub.db" must not escape.
    @Test
    fun `resolve file rejects absolute and deep traversal (upload-restore zip-slip)`() {
        val root = Files.createTempDirectory("upload-root").toFile()
        val uploadDir = File(root, "upload").apply { mkdirs() }

        try {
            assertNotNull(SkillPaths.resolveSkillFile(uploadDir, "photo.png"))
            assertNull(SkillPaths.resolveSkillFile(uploadDir, "../../databases/rikka_hub.db"))
            assertNull(SkillPaths.resolveSkillFile(uploadDir, "/data/local/tmp/evil"))
        } finally {
            root.deleteRecursively()
        }
    }
}

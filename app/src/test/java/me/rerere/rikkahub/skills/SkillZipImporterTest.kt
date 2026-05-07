package me.rerere.rikkahub.skills

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pure-JVM tests for [SkillZipImporter]. Each test builds a zip in-memory, pipes it into
 * [SkillZipImporter.extractZipToDir], asserts on the result, then cleans up its temp dir.
 * No Robolectric / no Android runtime dependency — these run on the host JVM.
 */
class SkillZipImporterTest {

    private lateinit var destDir: File

    @Before
    fun setup() {
        destDir = Files.createTempDirectory("skill-zip-test").toFile()
    }

    @After
    fun teardown() {
        runCatching { destDir.deleteRecursively() }
    }

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipBytes(name: String, content: String): ByteArray =
        buildZip(listOf(name to content.toByteArray(Charsets.UTF_8)))

    private val sampleSkillMd = """
        ---
        name: zip-test-skill
        description: Smoke-test skill packaged in a zip.
        ---

        # Zip Test Skill

        Body.
    """.trimIndent()

    // 1) Happy path — single SKILL.md zip extracts cleanly.
    @Test fun `happy path - flat zip with SKILL_md returns destDir`() {
        val zip = zipBytes("SKILL.md", sampleSkillMd)
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected success, got $result", result.isSuccess)
        val skillDir = result.getOrNull()
        assertNotNull(skillDir)
        assertEquals(destDir.canonicalPath, skillDir!!.canonicalPath)
        val skillMd = skillDir.resolve("SKILL.md")
        assertTrue("SKILL.md should exist after extract", skillMd.exists())
        assertTrue(skillMd.readText().contains("zip-test-skill"))
    }

    @Test fun `happy path - nested zip with subdir containing SKILL_md returns subdir`() {
        val zip = buildZip(listOf(
            "my-skill/" to ByteArray(0),
            "my-skill/SKILL.md" to sampleSkillMd.toByteArray(Charsets.UTF_8),
            "my-skill/README.md" to "secondary doc".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected success, got $result", result.isSuccess)
        val skillDir = result.getOrNull()!!
        assertEquals(File(destDir, "my-skill").canonicalPath, skillDir.canonicalPath)
        assertTrue(skillDir.resolve("SKILL.md").exists())
        assertTrue(skillDir.resolve("README.md").exists())
    }

    // 2) Path traversal `../etc/passwd` rejected.
    @Test fun `path traversal - relative ___ entry rejected`() {
        val zip = buildZip(listOf(
            "../etc/passwd" to "ROOT".toByteArray(Charsets.UTF_8),
            "SKILL.md" to sampleSkillMd.toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected PathTraversal error, got $err", err is SkillZipError.PathTraversal)
        // destDir should be cleaned up.
        assertFalse("destDir should have been deleted on failure", destDir.exists())
    }

    @Test fun `path traversal - nested relative dotdot escape rejected`() {
        val zip = buildZip(listOf(
            "ok/../../../etc/passwd" to "ROOT".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SkillZipError.PathTraversal)
    }

    // 3) Path traversal absolute `/tmp/x` rejected.
    @Test fun `path traversal - absolute path rejected`() {
        val zip = buildZip(listOf(
            "/tmp/x" to "ROOT".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SkillZipError.PathTraversal)
    }

    // 4) >200 entries rejected.
    @Test fun `entry-count cap - over 200 entries rejected`() {
        val entries = (0 until 201).map { i ->
            "file_$i.txt" to "x".toByteArray(Charsets.UTF_8)
        }
        val zip = buildZip(entries)
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected TooLarge error, got $err", err is SkillZipError.TooLarge)
        assertFalse("destDir should be cleaned", destDir.exists())
    }

    // 5) Missing SKILL.md returns specific error.
    @Test fun `missing SKILL_md returns MissingSkillMd error`() {
        val zip = buildZip(listOf(
            "README.md" to "no skill here".toByteArray(Charsets.UTF_8),
            "LICENSE" to "Apache-2.0".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SkillZipError.MissingSkillMd)
        assertFalse("destDir should be cleaned on missing SKILL.md", destDir.exists())
    }

    @Test fun `uncompressed-size cap - over 20MB rejected`() {
        // 21 entries of 1 MB each = 21 MB, just over the 20 MB cap.
        val oneMb = ByteArray(1024 * 1024) { 'A'.code.toByte() }
        val entries = (0 until 21).map { "blob_$it.bin" to oneMb }
        val zip = buildZip(entries)
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected TooLarge error, got $err", err is SkillZipError.TooLarge)
    }

    @Test fun `case-insensitive SKILL_md location works`() {
        val zip = buildZip(listOf(
            "skill.md" to sampleSkillMd.toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected success, got $result", result.isSuccess)
    }

    // Audit-pass adversarial coverage for path-traversal robustness.

    @Test fun `path traversal - dot-slash dotdot escape rejected`() {
        val zip = buildZip(listOf(
            "legit/./../../etc/passwd" to "ROOT".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SkillZipError.PathTraversal)
    }

    @Test fun `path traversal - leading backslash rejected`() {
        val zip = buildZip(listOf(
            "\\etc\\passwd" to "ROOT".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SkillZipError.PathTraversal)
    }

    @Test fun `path traversal - URL-encoded entry name does NOT decode through ZipEntry`() {
        // The zip spec stores entry names as raw UTF-8; ZipEntry.getName does NOT URL-
        // decode. So `..%2f..%2fetc%2fpasswd` is a single literal segment that
        // canonicalises inside destDir + ".." literal subdir, not an escape. Verify the
        // happy path: it extracts without rejection (since it's not actually traversing).
        val zip = buildZip(listOf(
            "skill/SKILL.md" to sampleSkillMd.toByteArray(Charsets.UTF_8),
            "skill/odd%2fname.txt" to "literal".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected success, got $result", result.isSuccess)
    }

    @Test fun `empty entry name rejected`() {
        val zip = buildZip(listOf(
            "" to "x".toByteArray(Charsets.UTF_8),
            "SKILL.md" to sampleSkillMd.toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        assertTrue("expected failure, got $result", result.isFailure)
        assertTrue(result.exceptionOrNull() is SkillZipError.PathTraversal)
    }

    @Test fun `entries with spaces in legitimate filenames are accepted`() {
        // Real-world GitHub-shaped zips often have spaces in nested filenames; these
        // must NOT trip the up-front rejection. The canonical-path check is the safety
        // floor; spaces alone are legitimate.
        val zip = buildZip(listOf(
            "rikka skill/SKILL.md" to sampleSkillMd.toByteArray(Charsets.UTF_8),
            "rikka skill/notes file.txt" to "with space".toByteArray(Charsets.UTF_8),
        ))
        val result = SkillZipImporter.extractZipToDir(ByteArrayInputStream(zip), destDir)
        // Note: current code rejects ANY entry containing a NUL byte (correct) but the
        // up-front filter does not reject spaces. This test pins that contract: legit
        // filenames with spaces extract successfully.
        // If a future change re-introduces a spaces-rejection, this test fails loud.
        assertTrue(
            "legitimate filenames with spaces must extract; got ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        val skillDir = result.getOrNull()!!
        assertTrue(skillDir.resolve("SKILL.md").exists())
        assertTrue(skillDir.resolve("notes file.txt").exists())
    }
}

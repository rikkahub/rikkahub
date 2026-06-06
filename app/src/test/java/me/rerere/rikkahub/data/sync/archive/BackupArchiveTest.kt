package me.rerere.rikkahub.data.sync.archive

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.sync.toArchiveSelection
import me.rerere.rikkahub.data.sync.webdav.toArchiveSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    private class FakeBackupArchiveEnvironment(temp: File) : BackupArchiveEnvironment {
        override val cacheDir: File = File(temp, "cache").apply { mkdirs() }
        override val filesDir: File = File(temp, "files").apply { mkdirs() }
        private val dbDir: File = File(temp, "databases").apply { mkdirs() }

        var settingsJson: String = "{}"
        var restoreCalls: Int = 0
        var lastRestoredJson: String? = null

        override fun databaseFile(name: String): File = File(dbDir, name)

        override suspend fun readSettingsJson(): String = settingsJson

        override suspend fun restoreSettingsJson(json: String) {
            restoreCalls++
            lastRestoredJson = json
        }
    }

    private fun zipWith(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun zipEntryNames(file: File): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(file.inputStream()).use { zip ->
            var e: ZipEntry?
            while (zip.nextEntry.also { e = it } != null) {
                e?.let { names += it.name }
                zip.closeEntry()
            }
        }
        return names
    }

    // --- SafeZipPaths.isSafeEntryName ---

    @Test
    fun `isSafeEntryName rejects traversal absolute backslash and empty segments`() {
        assertFalse(SafeZipPaths.isSafeEntryName("upload/../../evil"))
        assertFalse(SafeZipPaths.isSafeEntryName("/absolute"))
        assertFalse(SafeZipPaths.isSafeEntryName("upload/..\\evil"))
        assertFalse(SafeZipPaths.isSafeEntryName(".."))
        assertFalse(SafeZipPaths.isSafeEntryName(""))
        assertFalse(SafeZipPaths.isSafeEntryName("a//b"))

        assertTrue(SafeZipPaths.isSafeEntryName("upload/file.png"))
        assertTrue(SafeZipPaths.isSafeEntryName("skills/foo/bar.md"))
    }

    // --- SafeZipPaths.resolveChild ---

    @Test
    fun `resolveChild keeps safe names inside root and rejects escapes`() {
        val root = Files.createTempDirectory("root").toFile()
        File(root.parentFile, "${root.name}X").apply { mkdirs() }
        try {
            val inside = SafeZipPaths.resolveChild(root, "a/b.txt")
            assertNotNull(inside)
            assertTrue(inside!!.canonicalPath.startsWith(root.canonicalPath + File.separator))

            assertNull(SafeZipPaths.resolveChild(root, "../../evil"))
            // sibling-prefix escape: a sibling dir whose name starts with root's name
            assertNull(SafeZipPaths.resolveChild(root, "../${root.name}X/secret"))
        } finally {
            root.deleteRecursively()
        }
    }

    // --- Restorer rejects traversal end-to-end (the regression) ---

    @Test
    fun `restore rejects traversal upload entries and keeps legit ones inside root`() = runBlocking {
        val temp = Files.createTempDirectory("restore").toFile()
        try {
            val env = FakeBackupArchiveEnvironment(temp)
            val zip = File(temp, "backup.zip")
            zipWith(
                zip,
                listOf(
                    "${FileFolders.UPLOAD}/../../evil.txt" to "pwned".toByteArray(),
                    "/etc/evil" to "pwned".toByteArray(),
                    "${FileFolders.UPLOAD}/ok.txt" to "good".toByteArray(),
                ),
            )

            val report = BackupArchiveRestorer(env).restore(
                zip,
                BackupArchiveSelection(includeDatabase = false, includeFiles = true),
            )

            val uploadRoot = File(env.filesDir, FileFolders.UPLOAD)
            // legit file landed inside the upload root
            assertTrue(File(uploadRoot, "ok.txt").exists())
            assertEquals("good", File(uploadRoot, "ok.txt").readText())

            // no file escaped the upload root
            val escapedParent = File(env.filesDir.parentFile, "evil.txt")
            assertFalse(escapedParent.exists())
            assertFalse(File(temp.parentFile, "evil.txt").exists())

            assertTrue(report.skipped.contains("${FileFolders.UPLOAD}/../../evil.txt"))
            assertTrue(report.restored.contains("${FileFolders.UPLOAD}/ok.txt"))
        } finally {
            temp.deleteRecursively()
        }
    }

    // --- NUL-byte entry must be skipped, not abort the whole restore ---

    @Test
    fun `isSafeEntryName rejects NUL and control characters`() {
        assertFalse(SafeZipPaths.isSafeEntryName("upload/evil\u0000.txt"))
        assertFalse(SafeZipPaths.isSafeEntryName("upload/evil\u0007.txt"))
    }

    @Test
    fun `resolveChild returns null for NUL entry instead of throwing`() {
        val root = Files.createTempDirectory("nulroot").toFile()
        try {
            assertNull(SafeZipPaths.resolveChild(root, "evil\u0000.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `restore skips NUL-byte upload entry and still restores sibling legit entry`() = runBlocking {
        val temp = Files.createTempDirectory("nulrestore").toFile()
        try {
            val env = FakeBackupArchiveEnvironment(temp)
            val zip = File(temp, "backup.zip")
            zipWith(
                zip,
                listOf(
                    "${FileFolders.UPLOAD}/evil\u0000.txt" to "pwned".toByteArray(),
                    "${FileFolders.UPLOAD}/ok.txt" to "good".toByteArray(),
                ),
            )

            // Must not throw: a single hostile entry cannot abort the restore.
            val report = BackupArchiveRestorer(env).restore(
                zip,
                BackupArchiveSelection(includeDatabase = false, includeFiles = true),
            )

            val uploadRoot = File(env.filesDir, FileFolders.UPLOAD)
            assertTrue(File(uploadRoot, "ok.txt").exists())
            assertEquals("good", File(uploadRoot, "ok.txt").readText())

            assertTrue(report.skipped.contains("${FileFolders.UPLOAD}/evil\u0000.txt"))
            assertTrue(report.restored.contains("${FileFolders.UPLOAD}/ok.txt"))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun `restore skips NUL-byte skill entry and still restores sibling legit entry`() = runBlocking {
        val temp = Files.createTempDirectory("nulskillrestore").toFile()
        try {
            val env = FakeBackupArchiveEnvironment(temp)
            val zip = File(temp, "backup.zip")
            zipWith(
                zip,
                listOf(
                    "${FileFolders.SKILLS}/foo/evil\u0000.txt" to "pwned".toByteArray(),
                    "${FileFolders.SKILLS}/foo/SKILL.md" to "good".toByteArray(),
                ),
            )

            // Must not throw: a NUL-byte skill entry cannot abort the restore.
            val report = BackupArchiveRestorer(env).restore(
                zip,
                BackupArchiveSelection(includeDatabase = false, includeFiles = true),
            )

            val skillDir = File(env.filesDir, "${FileFolders.SKILLS}/foo")
            assertTrue(File(skillDir, "SKILL.md").exists())
            assertEquals("good", File(skillDir, "SKILL.md").readText())

            assertTrue(report.skipped.contains("${FileFolders.SKILLS}/foo/evil\u0000.txt"))
            assertTrue(report.restored.contains("${FileFolders.SKILLS}/foo/SKILL.md"))
        } finally {
            temp.deleteRecursively()
        }
    }

    // --- Builder includes expected entries by selection (locks format) ---

    @Test
    fun `build includes backward-compatible entry names per selection`() = runBlocking {
        val temp = Files.createTempDirectory("build").toFile()
        try {
            val env = FakeBackupArchiveEnvironment(temp)
            // seed db + upload/skills/fonts
            env.databaseFile(BackupArchiveLayout.DB_NAME).writeText("db")
            File(env.filesDir, FileFolders.UPLOAD).apply { mkdirs() }
                .let { File(it, "a.png").writeText("x") }
            File(env.filesDir, "${FileFolders.SKILLS}/myskill").apply { mkdirs() }
                .let { File(it, "SKILL.md").writeText("s") }
            File(env.filesDir, FileFolders.FONTS).apply { mkdirs() }
                .let { File(it, "f.ttf").writeText("t") }

            val full = BackupArchiveBuilder(env).build(
                BackupArchiveSelection(includeDatabase = true, includeFiles = true),
            )
            val names = zipEntryNames(full)
            assertTrue(names.contains("settings.json"))
            assertTrue(names.contains("rikka_hub.db"))
            assertTrue(names.contains("upload/a.png"))
            assertTrue(names.contains("skills/myskill/SKILL.md"))
            assertTrue(names.contains("fonts/f.ttf"))

            val minimal = BackupArchiveBuilder(env).build(
                BackupArchiveSelection(includeDatabase = false, includeFiles = false),
            )
            assertEquals(listOf("settings.json"), zipEntryNames(minimal))
        } finally {
            temp.deleteRecursively()
        }
    }

    // --- Selection mapping ---

    @Test
    fun `config maps to archive selection correctly`() {
        val s3Full = me.rerere.rikkahub.data.sync.s3.S3Config(
            items = listOf(
                me.rerere.rikkahub.data.sync.s3.S3Config.BackupItem.DATABASE,
                me.rerere.rikkahub.data.sync.s3.S3Config.BackupItem.FILES,
            ),
        ).toArchiveSelection()
        assertTrue(s3Full.includeDatabase)
        assertTrue(s3Full.includeFiles)

        val s3DbOnly = me.rerere.rikkahub.data.sync.s3.S3Config(
            items = listOf(me.rerere.rikkahub.data.sync.s3.S3Config.BackupItem.DATABASE),
        ).toArchiveSelection()
        assertTrue(s3DbOnly.includeDatabase)
        assertFalse(s3DbOnly.includeFiles)

        val webDavFull = me.rerere.rikkahub.data.datastore.WebDavConfig(
            items = listOf(
                me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.DATABASE,
                me.rerere.rikkahub.data.datastore.WebDavConfig.BackupItem.FILES,
            ),
        ).toArchiveSelection()
        assertTrue(webDavFull.includeDatabase)
        assertTrue(webDavFull.includeFiles)
    }

    // --- Settings restored once ---

    @Test
    fun `restore invokes settings restore exactly once with entry bytes`() = runBlocking {
        val temp = Files.createTempDirectory("settings").toFile()
        try {
            val env = FakeBackupArchiveEnvironment(temp)
            val zip = File(temp, "backup.zip")
            zipWith(zip, listOf("settings.json" to "{\"k\":1}".toByteArray()))

            BackupArchiveRestorer(env).restore(
                zip,
                BackupArchiveSelection(includeDatabase = false, includeFiles = false),
            )

            assertEquals(1, env.restoreCalls)
            assertEquals("{\"k\":1}", env.lastRestoredJson)
        } finally {
            temp.deleteRecursively()
        }
    }
}

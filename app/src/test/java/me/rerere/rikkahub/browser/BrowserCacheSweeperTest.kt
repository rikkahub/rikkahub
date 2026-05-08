package me.rerere.rikkahub.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Coverage for [BrowserCacheSweeper]. Verifies the keepLast-N policy keeps the newest
 * files, the missing-directory case is a no-op, and a small-population call is also a
 * no-op (so the sweep is cheap on early sessions).
 *
 * Uses a real temp directory and the file-based `sweep(File, ...)` overload — no
 * Android Context, no Mockito, no Robolectric. All logic runs on the JVM.
 */
class BrowserCacheSweeperTest {

    private lateinit var fakeCacheDir: File

    @Before fun setUp() {
        fakeCacheDir = Files.createTempDirectory("browser-cache-sweeper-test").toFile()
    }

    @After fun tearDown() {
        fakeCacheDir.deleteRecursively()
    }

    @Test fun `sweep keeps newest N files in browser-stream subdir`() {
        val streamDir = File(fakeCacheDir, "browser-stream").apply { mkdirs() }
        // 30 files, mtime monotonically increasing so the "newest 20" set is well-defined.
        val now = System.currentTimeMillis()
        (0 until 30).forEach { i ->
            File(streamDir, "stream-$i.png").apply {
                writeText("x")
                setLastModified(now - (29 - i) * 1000L)  // file 29 is newest
            }
        }

        BrowserCacheSweeper.sweep(fakeCacheDir, keepLast = 20)

        val remaining = streamDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        assertEquals("kept count", 20, remaining.size)
        // The 20 newest are files 10..29.
        for (i in 10..29) {
            assertTrue("file-$i should remain", "stream-$i.png" in remaining)
        }
        for (i in 0..9) {
            assertTrue("file-$i should be deleted", "stream-$i.png" !in remaining)
        }
    }

    @Test fun `sweep handles missing directories without throwing`() {
        // No `browser-stream/` or `browser-shots/` exists yet — sweep must be a no-op.
        BrowserCacheSweeper.sweep(fakeCacheDir, keepLast = 20)
        assertTrue("cacheDir is still empty",
            fakeCacheDir.listFiles().orEmpty().none {
                it.name == "browser-stream" || it.name == "browser-shots"
            })
    }

    @Test fun `sweep does nothing when file count is below threshold`() {
        val shotsDir = File(fakeCacheDir, "browser-shots").apply { mkdirs() }
        repeat(5) { i ->
            File(shotsDir, "shot-$i.png").writeText("x")
        }
        BrowserCacheSweeper.sweep(fakeCacheDir, keepLast = 20)
        assertEquals("all 5 files preserved", 5, shotsDir.listFiles().orEmpty().size)
    }

    @Test fun `sweep cleans both browser-stream and browser-shots`() {
        // One subdir over threshold, one under. Verify both are visited and only the
        // over-threshold one trims.
        val streamDir = File(fakeCacheDir, "browser-stream").apply { mkdirs() }
        val shotsDir = File(fakeCacheDir, "browser-shots").apply { mkdirs() }
        val now = System.currentTimeMillis()
        repeat(25) { i ->
            File(streamDir, "stream-$i.png").apply {
                writeText("x")
                setLastModified(now - (24 - i) * 1000L)
            }
        }
        repeat(3) { i ->
            File(shotsDir, "shot-$i.png").writeText("x")
        }

        BrowserCacheSweeper.sweep(fakeCacheDir, keepLast = 10)

        assertEquals("stream trimmed to 10", 10, streamDir.listFiles().orEmpty().size)
        assertEquals("shots untouched", 3, shotsDir.listFiles().orEmpty().size)
    }
}

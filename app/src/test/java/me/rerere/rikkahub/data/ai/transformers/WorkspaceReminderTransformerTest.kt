package me.rerere.rikkahub.data.ai.transformers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WorkspaceReminderTransformerTest {

    @Test
    fun `os-release should prefer pretty name`() {
        val content = """
            NAME="Alpine Linux"
            ID=alpine
            VERSION_ID=3.24.1
            PRETTY_NAME="Alpine Linux v3.24"
            HOME_URL="https://alpinelinux.org/"
        """.trimIndent()
        assertEquals("Alpine Linux v3.24", parseOsReleaseName(content))
    }

    @Test
    fun `os-release should fall back to name and version`() {
        val content = """
            # comment
            NAME='Ubuntu'
            VERSION_ID="24.04"
        """.trimIndent()
        assertEquals("Ubuntu 24.04", parseOsReleaseName(content))
    }

    @Test
    fun `os-release without name should return null`() {
        assertNull(parseOsReleaseName("ID=unknown\nnot a key value line\n"))
    }

    @Test
    fun `os-release name should be sanitized and truncated`() {
        val name = parseOsReleaseName("PRETTY_NAME=\"Distro\u0007 ${"x".repeat(200)}\"")!!
        assertEquals(80, name.length)
        assertEquals("Distro x", name.take(8))
    }

    @Test
    fun `remote environment should be loaded once per config`() = runTest {
        val cache = RemoteEnvironmentCache()
        var loads = 0
        val load = suspend { RemoteEnvironment("/bin/bash", "Debian ${++loads}") }

        assertEquals("Debian 1", cache.get("w", "config-a", load).getOrThrow().distro)
        assertEquals("Debian 1", cache.get("w", "config-a", load).getOrThrow().distro)
        // 连接配置改了, 可能已经换了一台机器
        assertEquals("Debian 2", cache.get("w", "config-b", load).getOrThrow().distro)
        assertEquals(2, loads)
    }

    @Test
    fun `remote failure should be reused until it expires`() = runTest {
        var now = 0L
        val cache = RemoteEnvironmentCache(failureTtlMillis = 60_000, now = { now })
        var loads = 0
        val failing = suspend {
            loads++
            throw IOException("unreachable")
        }

        assertTrue(cache.get("w", "config", failing).isFailure)
        now = 59_999
        assertTrue(cache.get("w", "config", failing).isFailure)
        assertEquals(1, loads)

        now = 60_000
        val recovered = cache.get("w", "config") { RemoteEnvironment("/bin/sh", null) }
        assertEquals("/bin/sh", recovered.getOrThrow().shell)
    }
}

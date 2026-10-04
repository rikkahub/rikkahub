package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}

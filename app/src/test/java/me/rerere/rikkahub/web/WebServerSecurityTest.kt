package me.rerere.rikkahub.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebServerSecurityTest {
    @Test
    fun `default startup listens on localhost`() {
        val options = WebServerStartupOptions()

        assertEquals(WEB_SERVER_LOOPBACK_HOST, options.host())
        assertNull(options.validationError())
    }

    @Test
    fun `LAN startup requires JWT authentication`() {
        val error = WebServerStartupOptions(
            localhostOnly = false,
            jwtEnabled = false,
            accessPassword = "password",
        ).validationError()

        assertEquals("LAN access requires JWT authentication to be enabled", error)
    }

    @Test
    fun `LAN startup requires a non-blank access password`() {
        val error = WebServerStartupOptions(
            localhostOnly = false,
            jwtEnabled = true,
            accessPassword = "   ",
        ).validationError()

        assertEquals("LAN access requires a non-empty access password", error)
    }

    @Test
    fun `security rejection clears persisted web server enabled state`() {
        val enabled = WebServerStartupOptions(
            localhostOnly = false,
            jwtEnabled = false,
            accessPassword = "password",
        ).webServerEnabledAfterValidation(enabled = true)

        assertEquals(false, enabled)
    }

    @Test
    fun `valid startup retains persisted web server enabled state`() {
        val enabled = WebServerStartupOptions().webServerEnabledAfterValidation(enabled = true)

        assertEquals(true, enabled)
    }
}

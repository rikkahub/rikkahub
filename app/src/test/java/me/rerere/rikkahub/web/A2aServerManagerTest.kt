package me.rerere.rikkahub.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aServerManagerTest {

    @Test
    fun `a2a rejects an active web server on the same port`() {
        assertTrue(
            a2aConflictsWithWebServer(
                a2aPort = 9000,
                webServerState = WebServerState(isRunning = true, port = 9000),
            )
        )
        assertFalse(
            a2aConflictsWithWebServer(
                a2aPort = 9000,
                webServerState = WebServerState(isRunning = true, port = 8080),
            )
        )
        assertFalse(
            a2aConflictsWithWebServer(
                a2aPort = 9000,
                webServerState = WebServerState(isRunning = false, port = 9000),
            )
        )
    }
}

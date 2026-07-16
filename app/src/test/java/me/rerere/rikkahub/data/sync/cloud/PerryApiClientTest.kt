package me.rerere.rikkahub.data.sync.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class PerryApiClientTest {
    @Test
    fun resolve_joinsRelativePath() {
        assertEquals(
            "http://192.168.3.44:8787/v1/workspaces",
            PerryApiClient.resolve("http://192.168.3.44:8787", "/v1/workspaces"),
        )
    }

    @Test
    fun resolve_preservesAbsoluteWorkspaceUrl() {
        val url = "http://192.168.3.44:8787/v1/workspaces/id/files?path=%2Fworkspace"
        assertEquals(url, PerryApiClient.resolve("http://192.168.3.44:8787", url))
    }
}

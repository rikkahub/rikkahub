package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebViewContentCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `stores large content outside navigation state`() {
        val content = "<html>${"preview".repeat(50_000)}</html>"

        val id = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(64, id.length)
        assertEquals(content, WebViewContentCache.load(temporaryFolder.root, id))
    }

    @Test
    fun `reuses the same cache entry for identical content`() {
        val content = "<html>preview</html>"

        val firstId = WebViewContentCache.store(temporaryFolder.root, content)
        val secondId = WebViewContentCache.store(temporaryFolder.root, content)

        assertEquals(firstId, secondId)
        assertEquals(1, temporaryFolder.root.resolve("webview_content").listFiles()?.size)
    }

    @Test
    fun `rejects invalid cache ids`() {
        assertNull(WebViewContentCache.load(temporaryFolder.root, "../content"))
    }
}

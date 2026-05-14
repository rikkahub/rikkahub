package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [normalizeBrowserQuery] — the pure address-bar input normaliser that
 * decides between "navigate to a URL" and "run a search query". It is the single place
 * the foreground address bar and the LLM's manual-navigation path agree on, so a
 * regression here silently changes where the browser lands.
 *
 * Pure Kotlin: no WebView, no Android Context — runs entirely on the JVM.
 */
class NormalizeBrowserQueryTest {

    @Test fun `absolute http and https URLs pass through unchanged`() {
        assertEquals("http://example.com", normalizeBrowserQuery("http://example.com"))
        assertEquals("https://example.com/path?q=1", normalizeBrowserQuery("https://example.com/path?q=1"))
    }

    @Test fun `file about and data schemes pass through unchanged`() {
        assertEquals("file:///sdcard/x.html", normalizeBrowserQuery("file:///sdcard/x.html"))
        assertEquals("about:blank", normalizeBrowserQuery("about:blank"))
        assertEquals("data:text/html,<h1>hi</h1>", normalizeBrowserQuery("data:text/html,<h1>hi</h1>"))
    }

    @Test fun `scheme detection is case-insensitive but preserves original casing`() {
        // The lower-cased copy is only used for the startsWith check — the returned value
        // is the trimmed original, so a mixed-case scheme is not rewritten.
        assertEquals("HTTPS://Example.com", normalizeBrowserQuery("HTTPS://Example.com"))
    }

    @Test fun `bare host gets https prefix`() {
        assertEquals("https://example.com", normalizeBrowserQuery("example.com"))
        assertEquals("https://foo.bar/baz", normalizeBrowserQuery("foo.bar/baz"))
    }

    @Test fun `host with port gets https prefix`() {
        assertEquals("https://192.168.1.1:8080", normalizeBrowserQuery("192.168.1.1:8080"))
    }

    @Test fun `multi-word input becomes a DuckDuckGo search`() {
        val result = normalizeBrowserQuery("hello world")
        assertTrue("expected a DuckDuckGo search URL, got $result",
            result.startsWith("https://duckduckgo.com/?q="))
        // Space must be URL-encoded.
        assertTrue("query should be url-encoded", result.contains("hello+world") || result.contains("hello%20world"))
    }

    @Test fun `single dotless word becomes a search not a host`() {
        val result = normalizeBrowserQuery("kotlin")
        assertTrue("dotless single word should search, got $result",
            result.startsWith("https://duckduckgo.com/?q="))
    }

    @Test fun `dotted token containing a space still searches`() {
        // Contains a dot but also a space — the !contains(' ') guard means it is NOT a host.
        val result = normalizeBrowserQuery("what is example.com")
        assertTrue("dotted phrase with spaces should search, got $result",
            result.startsWith("https://duckduckgo.com/?q="))
    }

    @Test fun `empty and whitespace-only input falls back to about blank`() {
        assertEquals("about:blank", normalizeBrowserQuery(""))
        assertEquals("about:blank", normalizeBrowserQuery("   "))
    }

    @Test fun `surrounding whitespace is trimmed before classification`() {
        assertEquals("https://example.com", normalizeBrowserQuery("  example.com  "))
        assertEquals("http://example.com", normalizeBrowserQuery("  http://example.com  "))
    }
}

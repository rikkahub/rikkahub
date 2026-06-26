package me.rerere.rikkahub.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure coverage for the device-unique mDNS naming (A2A discovery, Option A). The Android-coupled read
 * ([deviceMdnsIdentity]) is exercised on-device; these are the slug/label/instance/TXT invariants that
 * make two devices (even the same model) collision-free.
 */
class MdnsDeviceNamingTest {

    private val labelCharset = Regex("^[a-z0-9-]+$")

    @Test
    fun `modelSlug lowercases and keeps only a-z0-9-hyphen`() {
        assertEquals("rmx3636", modelSlug("RMX3636"))
        assertEquals("pixel-8-pro", modelSlug("Pixel 8 Pro"))
        assertEquals("sm-g998b", modelSlug("SM_G998B"))
    }

    @Test
    fun `modelSlug collapses and trims hyphens, never empty`() {
        assertEquals("a-b", modelSlug("  a@@@b  "))
        assertEquals("device", modelSlug("###"))
        assertEquals("device", modelSlug(""))
        // weird unicode / punctuation degrade to hyphen-separated ascii, no leading/trailing hyphen
        val s = modelSlug("Galaxy™ (S24)")
        assertTrue(s.matches(labelCharset))
        assertFalse(s.startsWith("-")); assertFalse(s.endsWith("-"))
    }

    @Test
    fun `deviceIdHash is stable, 12 hex, and handles blank`() {
        val a = deviceIdHash("abc123")
        assertEquals("idempotent for the same id", a, deviceIdHash("abc123"))
        assertEquals(12, a.length)
        assertTrue(a.matches(Regex("^[0-9a-f]{12}$")))
        assertEquals("unknown", deviceIdHash(null))
        assertEquals("unknown", deviceIdHash("   "))
        // different ids -> different hashes (overwhelmingly)
        assertFalse(deviceIdHash("abc123") == deviceIdHash("abc124"))
    }

    @Test
    fun `mdnsHostLabel composes a valid, device-tagged label`() {
        val label = mdnsHostLabel(prefix = "poci-a2a", model = "rmx3636", idHash = "a1b2c3d4e5f6")
        assertEquals("poci-a2a-rmx3636-a1b2c3d4e5f6", label)
        assertTrue(label.matches(labelCharset))
        assertFalse(label.startsWith("-")); assertFalse(label.endsWith("-"))
    }

    @Test
    fun `mdnsHostLabel stays within 63 chars and PRESERVES the hash (only the model is truncated)`() {
        val label = mdnsHostLabel(prefix = "poci-a2a", model = "x".repeat(200), idHash = "a1b2c3d4e5f6")
        assertTrue("label must be <= 63 chars", label.length <= 63)
        assertTrue(label.matches(labelCharset))
        assertFalse("truncation must not leave a trailing hyphen", label.endsWith("-"))
        assertTrue("the uniqueness hash must survive truncation", label.endsWith("a1b2c3d4e5f6"))
    }

    // Regression: a very long model must NOT let truncation drop the hash, or two such devices collide.
    @Test
    fun `two long-model devices stay distinct because the hash is never truncated away`() {
        val a = mdnsHostLabel(prefix = "poci-a2a", model = "x".repeat(80), idHash = "aaaaaaaaaaaa")
        val b = mdnsHostLabel(prefix = "poci-a2a", model = "x".repeat(80), idHash = "bbbbbbbbbbbb")
        assertFalse("same long model + different hash must yield different labels", a == b)
        assertTrue(a.endsWith("aaaaaaaaaaaa"))
        assertTrue(b.endsWith("bbbbbbbbbbbb"))
    }

    // The hash survives even a pathologically long PREFIX (not just a long model) — the contract holds for
    // ANY input, so the helper can never silently make two devices collide.
    @Test
    fun `the hash survives even a too-long prefix`() {
        val label = mdnsHostLabel(prefix = "p".repeat(60), model = "x", idHash = "abcdef123456")
        assertTrue("label must be <= 63 chars", label.length <= 63)
        assertTrue("the hash is preserved as the suffix", label.endsWith("abcdef123456"))
        assertTrue(label.matches(labelCharset))
        assertFalse(label.endsWith("-"))
    }

    @Test
    fun `serviceInstanceName carries the human device name and the kind`() {
        assertEquals("Poci A2A (Sebastian's RMX3636)", serviceInstanceName("A2A", "Sebastian's RMX3636"))
        assertEquals("Poci A2A (device)", serviceInstanceName("A2A", ""))
        // web vs a2a are always distinguishable for the same device
        assertFalse(serviceInstanceName("Web", "phone") == serviceInstanceName("A2A", "phone"))
    }

    @Test
    fun `a2aTxtRecord advertises paths and hints but never a token`() {
        val txt = a2aTxtRecord()
        assertEquals("/.well-known/agent-card.json", txt["path"])
        assertEquals("/a2a", txt["a2a_path"])
        assertEquals("a2a", txt["poci_kind"])
        assertEquals("bearer", txt["auth"])
        // discovery hints must never carry the secret itself
        val keys = txt.keys.joinToString(",").lowercase()
        assertFalse(keys.contains("token"))
        assertFalse(keys.contains("secret"))
        assertFalse(keys.contains("password"))
    }
}

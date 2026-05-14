package me.rerere.rikkahub.data.ai.tools.local

import me.rerere.rikkahub.data.storage.StorageVolumeGrantStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 25 — ExternalStorage tooling. Covers the pure pieces: [ContentUriSafetyGuard]
 * structural validation, the authority classifier, and the [StorageVolumeGrantStore]
 * serialize / deserialize round-trip. The SAF picker flow + DocumentFile traversal need
 * instrumented tests.
 */
class ExternalStorageToolsTest {

    // ---------- ContentUriSafetyGuard ----------

    @Test fun `isContentUri detects content scheme`() {
        assertTrue(ContentUriSafetyGuard.isContentUri("content://com.android.externalstorage.documents/tree/x"))
        assertEquals(false, ContentUriSafetyGuard.isContentUri("file:///sdcard/x"))
        assertEquals(false, ContentUriSafetyGuard.isContentUri("/sdcard/x"))
        assertEquals(false, ContentUriSafetyGuard.isContentUri(null))
    }

    @Test fun `safety guard rejects blank`() {
        assertNotNull(ContentUriSafetyGuard.check(null))
        assertNotNull(ContentUriSafetyGuard.check(""))
    }

    @Test fun `safety guard rejects non-content scheme`() {
        val v = ContentUriSafetyGuard.check("file:///sdcard/x")
        assertNotNull(v)
        assertEquals("path_blocked", v!!.code)
    }

    @Test fun `safety guard rejects missing authority`() {
        assertNotNull(ContentUriSafetyGuard.check("content:///tree/x"))
    }

    @Test fun `safety guard accepts well-formed content uri`() {
        assertNull(
            ContentUriSafetyGuard.check(
                "content://com.android.externalstorage.documents/tree/primary%3ADownload"
            )
        )
    }

    @Test fun `safety guard has no authority allowlist - any provider passes structural check`() {
        // The OS grant model is the gate, not an allowlist. A made-up authority still
        // passes the structural check.
        assertNull(ContentUriSafetyGuard.check("content://com.some.random.provider/tree/abc"))
    }

    // ---------- classifyAuthority ----------

    @Test fun `classifyAuthority maps known providers`() {
        assertEquals("volume_root", classifyAuthority("com.android.externalstorage.documents"))
        assertEquals("downloads", classifyAuthority("com.android.providers.downloads.documents"))
        assertEquals("cloud", classifyAuthority("com.google.android.apps.docs.storage"))
        assertEquals("cloud", classifyAuthority("com.dropbox.android.documents"))
        assertEquals("cloud", classifyAuthority("com.microsoft.skydrive.content.metadata"))
        assertEquals("other", classifyAuthority("com.weird.unknown.provider"))
    }

    // ---------- StorageVolumeGrantStore serialize round-trip ----------

    @Test fun `grant store round-trips a single grant`() {
        val grants = listOf(
            StorageVolumeGrantStore.Grant(
                contentUri = "content://com.android.externalstorage.documents/tree/usb",
                displayName = "USB drive",
                authority = "com.android.externalstorage.documents",
            )
        )
        val serialized = StorageVolumeGrantStore.serialize(grants)
        val back = StorageVolumeGrantStore.deserialize(serialized)
        assertEquals(grants, back)
    }

    @Test fun `grant store round-trips multiple grants`() {
        val grants = listOf(
            StorageVolumeGrantStore.Grant("content://a/tree/1", "Volume A", "auth.a"),
            StorageVolumeGrantStore.Grant("content://b/tree/2", "Cloud B", "auth.b"),
            StorageVolumeGrantStore.Grant("content://c/tree/3", "Downloads", "auth.c"),
        )
        val back = StorageVolumeGrantStore.deserialize(StorageVolumeGrantStore.serialize(grants))
        assertEquals(grants, back)
    }

    @Test fun `grant store deserialize handles empty`() {
        assertTrue(StorageVolumeGrantStore.deserialize("").isEmpty())
    }

    @Test fun `grant store skips malformed lines`() {
        // A line with the wrong field count is dropped, not crashed on. Build the garbage
        // input from the same separators the store uses so the test stays in sync.
        val lineSep = ""
        val good = StorageVolumeGrantStore.Grant("content://a/tree/1", "A", "auth.a")
        val serialized = StorageVolumeGrantStore.serialize(listOf(good))
        val withGarbage = serialized + lineSep + "garbage-line-no-separators"
        val back = StorageVolumeGrantStore.deserialize(withGarbage)
        assertEquals(listOf(good), back)
    }
}

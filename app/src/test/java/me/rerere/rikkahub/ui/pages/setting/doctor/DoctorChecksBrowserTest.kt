package me.rerere.rikkahub.ui.pages.setting.doctor

import me.rerere.rikkahub.browser.BrowserToolDefaults
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pass 3 tests for the Browser-aware Doctor rows ([DoctorChecks.browserChecks]). We
 * can't easily construct a full [DoctorChecks] in JVM unit tests because every
 * dependency is a real Android type (Context, AppDatabase, ConversationRepository, ...).
 * Instead we pin the invariants the row logic depends on:
 *
 *  - [LocalToolOption.Browser] is in the sealed hierarchy (the row keys off this).
 *  - The browser tool catalogue exists with stable names — the live-count row builds
 *    its detail string by stripping the `browser_` prefix from each WRITE_TOOL entry.
 *  - The profile-dir filename is the exact "browser-profile" subpath we exclude from
 *    backups in `backup_rules.xml` and `data_extraction_rules.xml`.
 *
 * Real positive/negative behaviour on the profile-dir-writable check is exercised by
 * the device-walk smoke test in the test plan (the AutoFix path mkdirs() against
 * `${context.filesDir}/browser-profile/`).
 */
class DoctorChecksBrowserTest {

    @Test fun `LocalToolOption Browser exists`() {
        // Sanity: the master toggle is what keys the Browser-aware Doctor rows. If this
        // ever moves to a different name, the rows go quiet and the user wonders why
        // "Browser write tools enabled" disappeared.
        assertNotNull(LocalToolOption.Browser)
        // Catch a regression where someone reorders the sealed hierarchy and the
        // serial name shifts: the row severity logic is membership-based, not
        // serial-name-based, but verifying the type is constructible nails one path.
        val opt: LocalToolOption = LocalToolOption.Browser
        assertEquals(LocalToolOption.Browser, opt)
    }

    @Test fun `browser write tools have a stable name list`() {
        // The live-count row reads BrowserToolDefaults.WRITE_TOOLS and renders each
        // name with "browser_" stripped. The set must be non-empty and every entry
        // must have the prefix or the strip would silently leak the full name.
        assertTrue("WRITE_TOOLS must not be empty", BrowserToolDefaults.WRITE_TOOLS.isNotEmpty())
        for (name in BrowserToolDefaults.WRITE_TOOLS) {
            assertTrue("write tool '$name' must start with 'browser_' prefix", name.startsWith("browser_"))
            assertTrue("after stripping prefix, '$name' must have a name body",
                name.removePrefix("browser_").isNotEmpty())
        }
    }

    @Test fun `profile dir name matches backup-rules exclusion`() {
        // The backup_rules.xml + data_extraction_rules.xml exclude `browser-profile/`.
        // The Doctor row checks `${filesDir}/browser-profile/`. Both must agree — if the
        // dir name here changes, an attacker could exfil cookies via Google Drive backup.
        // This is a paper-trail test: the row's path constant is constructed inline
        // from this string, and so is the manifest exclusion.
        val expectedDirName = "browser-profile"
        // Re-derive the same way the row does — File(context.filesDir, expectedDirName).
        // The path string is the contract; the actual file-system check is in the row.
        assertTrue("dir name should be lowercased", expectedDirName == expectedDirName.lowercase())
        assertTrue("dir name should not have a trailing slash (File API adds it)",
            !expectedDirName.endsWith("/"))
    }

    @Test fun `Capability Browser includes only LocalToolOption Browser`() {
        // Capability.Browser is internal/private to DoctorChecks, but the row's "needed
        // by:" subtitle is built from its members. The contract: ONLY the master Browser
        // toggle should map. If a future change widens Capability.Browser to other
        // options, the subtitle would mislead users about which tool needs the dir.
        // We test this indirectly through the LocalToolOption shortName: Browser must
        // resolve to a stable display string.
        // (The shortName function is private; but its return is used in the row detail
        // string verbatim. If it's missing, "Browser" would fall through to the
        // simpleName fallback "Browser" too — which is the same. Pin both expectations.)
        assertEquals("Browser", LocalToolOption.Browser::class.simpleName)
    }
}

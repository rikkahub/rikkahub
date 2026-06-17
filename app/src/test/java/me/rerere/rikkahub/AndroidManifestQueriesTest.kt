package me.rerere.rikkahub

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Manifest package-visibility regression for the app-launch tools (`list_app`/`open_app`).
 *
 * On Android 11+ (API 30) package visibility is filtered: without an explicit `<queries>` entry,
 * `PackageManager.queryIntentActivities(ACTION_MAIN/CATEGORY_LAUNCHER)` and
 * `getLaunchIntentForPackage(...)` only see the caller's own package, so `list_app` returns an empty
 * list and `open_app` cannot resolve another app on either flavor. The fix is a `<queries><intent>`
 * filter for ACTION_MAIN + CATEGORY_LAUNCHER — NOT the broad `QUERY_ALL_PACKAGES` permission, which is
 * Play-policy sensitive on the `play` flavor.
 *
 * Parsed structurally (DOM) rather than by substring so a LAUNCHER category that lives in an
 * `<activity><intent-filter>` (it already does, for the launcher entry) cannot satisfy the assertion —
 * only a real `<queries><intent>` LAUNCHER filter counts.
 */
class AndroidManifestQueriesTest {

    private val manifestFile = File("src/main/AndroidManifest.xml")
    private val sideloadManifestFile = File("src/sideload/AndroidManifest.xml")

    @Test
    fun queries_block_declares_launcher_intent_for_app_launch_tools() {
        assertTrue("AndroidManifest.xml not found at ${manifestFile.absolutePath}", manifestFile.exists())

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val queriesNodes = doc.getElementsByTagName("queries")
        assertTrue("manifest has no <queries> block", queriesNodes.length > 0)

        var hasLauncherQuery = false
        for (q in 0 until queriesNodes.length) {
            val intents = (queriesNodes.item(q) as Element).getElementsByTagName("intent")
            for (i in 0 until intents.length) {
                val intent = intents.item(i) as Element
                val actions = intent.getElementsByTagName("action")
                val categories = intent.getElementsByTagName("category")
                val hasMain = (0 until actions.length).any {
                    (actions.item(it) as Element).getAttribute("android:name") ==
                        "android.intent.action.MAIN"
                }
                val hasLauncher = (0 until categories.length).any {
                    (categories.item(it) as Element).getAttribute("android:name") ==
                        "android.intent.category.LAUNCHER"
                }
                if (hasMain && hasLauncher) hasLauncherQuery = true
            }
        }
        assertTrue(
            "<queries> must declare an <intent> with ACTION_MAIN + CATEGORY_LAUNCHER so list_app/open_app " +
                "see launchable packages on both flavors",
            hasLauncherQuery,
        )
    }

    @Test
    fun manifest_does_not_request_query_all_packages() {
        assertTrue("AndroidManifest.xml not found at ${manifestFile.absolutePath}", manifestFile.exists())

        // Assert on the <uses-permission> DOM nodes, not raw text — a comment that *names*
        // QUERY_ALL_PACKAGES (to document why it is deliberately absent) must not trip this guard.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifestFile)
        val perms = doc.getElementsByTagName("uses-permission")
        val requested = (0 until perms.length).map {
            (perms.item(it) as Element).getAttribute("android:name")
        }
        assertFalse(
            "QUERY_ALL_PACKAGES must NOT be requested in the MAIN manifest — the play flavor is " +
                "Play-policy sensitive; the broad permission is sideload-only (declared in the " +
                "sideload manifest), and list_app relies on a <queries><intent> LAUNCHER filter only",
            requested.any { it.endsWith("QUERY_ALL_PACKAGES") },
        )
    }

    @Test
    fun sideload_manifest_requests_query_all_packages_for_the_picker() {
        // The sideload flavor opts into broad package visibility so the automation scope PICKER can
        // list every installed package incl. system ones. It lives ONLY in the sideload manifest (the
        // play merge keeps the narrow main visibility), so the two flavors diverge by manifest, not code.
        assertTrue(
            "sideload AndroidManifest.xml not found at ${sideloadManifestFile.absolutePath}",
            sideloadManifestFile.exists(),
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(sideloadManifestFile)
        val perms = doc.getElementsByTagName("uses-permission")
        val requested = (0 until perms.length).map {
            (perms.item(it) as Element).getAttribute("android:name")
        }
        assertTrue(
            "the sideload flavor must request QUERY_ALL_PACKAGES so the scope picker can enumerate " +
                "all installed packages (incl. system)",
            requested.any { it.endsWith("QUERY_ALL_PACKAGES") },
        )
    }
}

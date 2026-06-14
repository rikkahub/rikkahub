package me.rerere.rikkahub.automation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Trust-copy guard for the automation surfaces (Part B / B1).
 *
 * The accessibility-automation kernel dispatches WRITE verbs today: ui_tap
 * (ACTION_CLICK on a resolved node), ui_set_text (TYPE_INTO), ui_scroll
 * (ACTION_SCROLL_*), and ui_global (performGlobalAction BACK/HOME/RECENTS).
 * The user-facing copy and the manifest/config comments were minted in the
 * #187 read-only era and still tell the user the service "cannot tap, type, or
 * scroll" and is "read-only". That is a dishonest trust claim -- the user grants
 * the service believing it cannot act, while it can.
 *
 * This guard pins the automation surfaces named in the acceptance:
 *   - strings.xml automation_a11y_service_description
 *   - strings.xml assistant_page_ui_automation_desc
 *   - res/xml/accessibility_service_config.xml (description + leading comment)
 *   - AndroidManifest.xml (the accessibility-service comment)
 * No surface may carry a stale read-only claim ("read-only", "read only",
 * "cannot tap", "no tapping"). The replacement copy must affirmatively state
 * that tap/type/scroll/global navigation may occur.
 *
 * Pure JVM file-content assertion (no Android resource compilation needed), so
 * it runs on the CI unit-test floor and fails RED until the copy is corrected.
 */
class AutomationTrustCopyTest {

    private val staleClaims = listOf(
        "read-only",
        "read only",
        "cannot tap",
        "no tapping",
    )

    @Test
    fun `automation surfaces carry no stale read-only claim`() {
        val violations = mutableListOf<String>()
        automationSurfaces().forEach { file ->
            assertTrue(
                "Could not locate automation surface (CWD=${File("").absolutePath}): " +
                    "${file.path}; the guard would otherwise pass vacuously",
                file.isFile,
            )
            file.readLines().forEachIndexed { index, raw ->
                val lower = raw.lowercase()
                staleClaims.forEach { claim ->
                    if (lower.contains(claim)) {
                        violations += "${file.path}:${index + 1}: ${raw.trim()}"
                    }
                }
            }
        }

        assertTrue(
            "Stale read-only trust claims remain on the automation surfaces -- the " +
                "service dispatches tap/type/scroll/global navigation, so the copy must " +
                "say so:\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `user-facing automation strings state that the service may act`() {
        val strings = firstExisting(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
        assertTrue(
            "Could not locate strings.xml (CWD=${File("").absolutePath})",
            strings.isFile,
        )
        val text = strings.readText()

        listOf(
            "automation_a11y_service_description",
            "assistant_page_ui_automation_desc",
        ).forEach { name ->
            val value = extractString(text, name)
            assertTrue("Missing string $name", value != null)
            val lower = value!!.lowercase()
            assertTrue(
                "String $name must affirmatively state the service can act " +
                    "(tap/type/scroll/global navigation); got: $value",
                lower.contains("tap") && lower.contains("scroll"),
            )
        }
    }

    private fun extractString(xml: String, name: String): String? =
        Regex(
            "<string name=\"$name\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)?.groupValues?.get(1)

    private fun automationSurfaces(): List<File> = listOf(
        firstExisting(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        ),
        firstExisting(
            "src/main/res/xml/accessibility_service_config.xml",
            "app/src/main/res/xml/accessibility_service_config.xml",
        ),
        firstExisting(
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
        ),
    )

    private fun firstExisting(vararg candidates: String): File {
        candidates.forEach { c ->
            val f = File(c)
            if (f.isFile) return f
        }
        return File(candidates.first())
    }
}

package me.rerere.rikkahub.browser

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.NULL_CONTEXT
import me.rerere.rikkahub.data.ai.tools.local.browserBackTool
import me.rerere.rikkahub.data.ai.tools.local.browserClickTool
import me.rerere.rikkahub.data.ai.tools.local.browserCurrentUrlTool
import me.rerere.rikkahub.data.ai.tools.local.browserDoneTool
import me.rerere.rikkahub.data.ai.tools.local.browserEvalJsTool
import me.rerere.rikkahub.data.ai.tools.local.browserGetTextTool
import me.rerere.rikkahub.data.ai.tools.local.browserOpenTool
import me.rerere.rikkahub.data.ai.tools.local.browserPressKeyTool
import me.rerere.rikkahub.data.ai.tools.local.browserScrollTool
import me.rerere.rikkahub.data.ai.tools.local.browserSelectTool
import me.rerere.rikkahub.data.ai.tools.local.browserSubmitTool
import me.rerere.rikkahub.data.ai.tools.local.browserTypeTool
import me.rerere.rikkahub.data.ai.tools.local.browserWaitForTool
import me.rerere.rikkahub.data.ai.tools.local.createBrowserTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pass 2 unit-test coverage for the 17 browser tools. Every test runs against an UNBOUND
 * BrowserController — no live Activity, no WebView. This is intentional:
 *
 *   - Args-validation paths must reject malformed input BEFORE any controller dispatch.
 *   - Tools whose args ARE valid must short-circuit to the standard
 *     `browser_not_open` envelope rather than crashing on a null WebView.
 *
 * Live-WebView behaviour (selector_not_found vs success, post_click readyState wait,
 * screenshot encoding) is exercised by the device-walking smoke test in Pass 3, not here.
 */
class BrowserToolsTest {

    @After fun tearDown() {
        // Defence-in-depth: force the controller back to a clean state so a stray bind
        // from one test can't leak into the next. (No public reset; the only paths that
        // mutate the volatile WeakReference are bind/unbind, both of which take a WebView.
        // Since we never bind in this suite, this is a no-op — kept for documentation.)
        BrowserController.clearTaskWindow()
    }

    private fun execText(tool: me.rerere.ai.core.Tool, argsJson: String): String = runBlocking {
        val parts = tool.execute(Json.parseToJsonElement(argsJson))
        (parts.first { it is UIMessagePart.Text } as UIMessagePart.Text).text
    }

    // ---- Missing required args ---------------------------------------------------------

    @Test fun `browser_open rejects missing url`() {
        val out = execText(browserOpenTool(NULL_CONTEXT), "{}")
        assertTrue("expected missing_url envelope, got: $out", out.contains("missing_url"))
    }

    @Test fun `browser_open rejects blank url`() {
        val out = execText(browserOpenTool(NULL_CONTEXT), """{"url":""}""")
        assertTrue("expected missing_url envelope, got: $out", out.contains("missing_url"))
    }

    @Test fun `browser_click rejects missing selector`() {
        val out = execText(browserClickTool(), "{}")
        assertTrue("expected missing_selector envelope, got: $out", out.contains("missing_selector"))
    }

    @Test fun `browser_type rejects missing selector`() {
        val out = execText(browserTypeTool(), """{"text":"hi"}""")
        assertTrue(out.contains("missing_selector"))
    }

    @Test fun `browser_type rejects missing text`() {
        val out = execText(browserTypeTool(), """{"selector":"#q"}""")
        assertTrue(out.contains("missing_text"))
    }

    @Test fun `browser_scroll rejects missing direction`() {
        val out = execText(browserScrollTool(), "{}")
        assertTrue(out.contains("missing_direction"))
    }

    @Test fun `browser_scroll rejects unknown direction`() {
        val out = execText(browserScrollTool(), """{"direction":"sideways"}""")
        assertTrue("expected error envelope, got: $out", out.contains("missing_direction"))
    }

    @Test fun `browser_submit rejects missing selector`() {
        val out = execText(browserSubmitTool(), "{}")
        assertTrue(out.contains("missing_selector"))
    }

    @Test fun `browser_select rejects missing selector and value`() {
        val outNoSel = execText(browserSelectTool(), """{"value":"x"}""")
        val outNoVal = execText(browserSelectTool(), """{"selector":"#s"}""")
        assertTrue(outNoSel.contains("missing_selector"))
        assertTrue(outNoVal.contains("missing_value"))
    }

    @Test fun `browser_press_key rejects missing key`() {
        val out = execText(browserPressKeyTool(), "{}")
        assertTrue(out.contains("missing_key"))
    }

    @Test fun `browser_press_key trims oversized key arg`() {
        // The factory caps the key at 32 chars before splicing into the JS payload.
        // We can't directly observe the cap at the args-validation layer (it falls
        // through to withController which then short-circuits), but the tool MUST NOT
        // crash on a 4 KB string and MUST hit the not-open envelope.
        val giant = "K".repeat(4000)
        val out = execText(browserPressKeyTool(), """{"key":"$giant"}""")
        assertTrue("oversized key should fall through to not-open, got: $out",
            out.contains("browser_not_open"))
    }

    @Test fun `browser_eval_js rejects missing code`() {
        val out = execText(browserEvalJsTool(), "{}")
        assertTrue(out.contains("missing_code"))
    }

    @Test fun `browser_wait_for rejects missing selector`() {
        val out = execText(browserWaitForTool(), "{}")
        assertTrue(out.contains("missing_selector"))
    }

    @Test fun `browser_done rejects missing summary`() {
        val out = execText(browserDoneTool(), "{}")
        assertTrue(out.contains("missing_summary"))
    }

    // ---- not-bound short circuit -------------------------------------------------------

    @Test fun `read tools short-circuit to not_open when controller unbound`() {
        // current_url, get_text, back, forward, wait_for ALL go through withController
        // first thing — they MUST return browser_not_open rather than NPE on a null WebView.
        // (browser_open is the exception: it tries to launch the Activity, so we don't
        // exercise its valid-url path here.)
        for (out in listOf(
            execText(browserCurrentUrlTool(), "{}"),
            execText(browserGetTextTool(), """{"selector":"body"}"""),
            execText(browserBackTool(), "{}"),
            execText(browserWaitForTool(), """{"selector":".loaded"}"""),
        )) {
            assertTrue("expected browser_not_open, got: $out", out.contains("browser_not_open"))
        }
    }

    @Test fun `write tools short-circuit to not_open when controller unbound`() {
        for (out in listOf(
            execText(browserClickTool(), """{"selector":"#go"}"""),
            execText(browserTypeTool(), """{"selector":"#q","text":"hello"}"""),
            execText(browserScrollTool(), """{"direction":"down"}"""),
            execText(browserSelectTool(), """{"selector":"#s","value":"a"}"""),
            execText(browserPressKeyTool(), """{"key":"Enter"}"""),
            execText(browserEvalJsTool(), """{"code":"1+1"}"""),
        )) {
            assertTrue("expected browser_not_open, got: $out", out.contains("browser_not_open"))
        }
    }

    @Test fun `browser_done does not require open browser`() {
        // browser_done is the loop sentinel — even if the controller is gone we still
        // want to clear our local task-timer state so a stale timer doesn't keep firing
        // browser_task_timeout on the NEXT browser_open. Returns success without bind.
        val out = execText(browserDoneTool(), """{"summary":"all good"}""")
        assertTrue("expected success envelope, got: $out", out.contains("\"success\":true"))
    }

    // ---- Catalog wiring ----------------------------------------------------------------

    @Test fun `createBrowserTool returns a Tool for every entry in ALL_TOOLS`() {
        for (name in BrowserToolDefaults.ALL_TOOLS) {
            val t = createBrowserTool(name, NULL_CONTEXT)
            assertNotNull("createBrowserTool returned null for '$name'", t)
            assertEquals("tool factory for '$name' produced wrong name", name, t!!.name)
        }
    }

    @Test fun `createBrowserTool returns null for unknown name`() {
        val t = createBrowserTool("not_a_browser_tool", NULL_CONTEXT)
        assertNull(t)
    }

    @Test fun `default enabled map covers all 17 tools`() {
        // Every tool MUST have a default. A missing key would fall through to `false`,
        // which would silently disable a tool the user expected to be on. The reverse
        // (a default for a name not in ALL_TOOLS) wouldn't break anything but suggests
        // a typo, so we check both directions.
        assertEquals(BrowserToolDefaults.ALL_TOOLS.toSet(), BrowserToolDefaults.DEFAULT_ENABLED.keys)
        // Read tools default ON
        for (n in BrowserToolDefaults.READ_TOOLS) {
            assertEquals("$n should default ON", true, BrowserToolDefaults.DEFAULT_ENABLED[n])
        }
        // Write tools default OFF
        for (n in BrowserToolDefaults.WRITE_TOOLS) {
            assertEquals("$n should default OFF", false, BrowserToolDefaults.DEFAULT_ENABLED[n])
        }
    }

    @Test fun `task window starts unbounded and accepts startTaskWindow`() {
        // Sanity check the controller's task-window helpers — Pass 2 added these and
        // the tool factories rely on them. Within-window must default true (no task
        // started) and stay true for at least 1 ms after a fresh startTaskWindow().
        BrowserController.clearTaskWindow()
        assertTrue(BrowserController.isWithinTaskWindow())
        BrowserController.startTaskWindow()
        assertTrue(BrowserController.isWithinTaskWindow())
        BrowserController.clearTaskWindow()
        assertTrue(BrowserController.isWithinTaskWindow())
    }
}

package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HARDLINE coverage for the Pass 2 `browser_eval_js` arm. Two responsibilities:
 *
 *   1. The shell deny-list (rm -rf /, dd, mkfs, fork bomb, kill -1, shutdown family,
 *      base64-d-piped-to-shell) MUST also fire when the model embeds the same shape
 *      inside JS (e.g. via `fetch('/api/run', {body: 'rm -rf /'})`).
 *
 *   2. JS-specific hardline patterns — cookie writes, eval, Function constructor,
 *      string-form setTimeout/setInterval, data: script injection.
 *
 * Negative cases prove the false-positive surface is bounded: function-literal
 * setTimeout, string literals that mention "eval" without invoking it, and bare
 * cookie reads are NOT blocked.
 */
class HardlineCommandGuardBrowserTest {

    // ---- Shell deny-list re-use ---------------------------------------------------

    @Test fun `shell rm -rf inside JS payload is blocked`() {
        // The model could try to smuggle a shell command via fetch / sendBeacon /
        // navigator.* — even a literal string of `rm -rf /` should refuse to run, so
        // a copy-paste-from-an-untrusted-page payload can't reach an authenticated API.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"fetch('/api/run', {method:'POST', body:'rm -rf /'})"}"""
        )
        assertNotNull("shell deny-list pattern should still block inside JS payloads", reason)
    }

    @Test fun `shell rm -rf etc inside JS payload is blocked`() {
        // Same pattern, but for /etc descendants — proves the system-dirs branch fires
        // on string-literal-inside-JS the same way it does at command position.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"fetch('/api/run', {method:'POST', body:'rm -rf /etc/passwd'})"}"""
        )
        assertNotNull(reason)
    }

    // ---- JS-specific hardline ----------------------------------------------------

    @Test fun `document_cookie write is blocked`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"document.cookie = 'session=abc'"}"""
        )
        assertNotNull("document.cookie= must hit the JS hardline arm", reason)
        assertTrue("expected hardline:js_cookie_write reason, got '$reason'", reason!!.contains("cookie_write"))
    }

    @Test fun `eval call is blocked`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"eval('alert(1)')"}"""
        )
        assertNotNull(reason)
        assertTrue("expected hardline:js_eval, got '$reason'", reason!!.contains("js_eval"))
    }

    @Test fun `new Function constructor is blocked`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"var f = new Function('return 1+1');"}"""
        )
        assertNotNull(reason)
        assertTrue("expected hardline:js_function_constructor, got '$reason'", reason!!.contains("function_constructor"))
    }

    @Test fun `setTimeout with string body is blocked`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"setTimeout(\"foo()\", 1000)"}"""
        )
        assertNotNull("setTimeout('string', ...) is stealth-eval", reason)
        assertTrue(reason!!.contains("settimeout_string"))
    }

    @Test fun `setInterval with string body is blocked`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"setInterval('tick()', 500)"}"""
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("setinterval_string"))
    }

    @Test fun `script src data URI is blocked`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"document.body.innerHTML = '<script src=\"data:text/javascript,alert(1)\"></script>'"}"""
        )
        assertNotNull(reason)
        assertTrue("expected hardline:js_script_data_uri, got '$reason'", reason!!.contains("script_data_uri"))
    }

    // ---- Negative cases — must NOT false-positive --------------------------------

    @Test fun `setTimeout with function literal is allowed`() {
        // Arrow-function callbacks are the safe form — the browser doesn't re-parse
        // the argument, so the dynamic-eval risk doesn't apply. Must NOT block.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"setTimeout(() => foo(), 1000)"}"""
        )
        assertNull("function-literal setTimeout should not be blocked, got '$reason'", reason)
    }

    @Test fun `console log mentioning eval as a string is allowed`() {
        // The string "eval" in a literal does not invoke eval(); blocking this would
        // be too aggressive and trip on docs / log statements. Verify the regex anchors
        // on the function-call shape (`eval(` with optional whitespace) not the bare word.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"console.log('eval is not called here')"}"""
        )
        assertNull("string literal containing 'eval' should not be blocked, got '$reason'", reason)
    }

    @Test fun `reading document_cookie is allowed`() {
        // The arm only blocks WRITES (`document.cookie = ...`). Reads are useful when
        // a page passes auth via cookies that the model needs to inspect.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"return document.cookie"}"""
        )
        assertNull("cookie reads should not be blocked, got '$reason'", reason)
    }

    @Test fun `safe DOM read is allowed`() {
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"document.querySelector('h1').innerText"}"""
        )
        assertNull(reason)
    }

    @Test fun `empty code returns null`() {
        // Empty string isn't a hardline match; the missing-arg envelope is the tool's
        // job (BrowserTools.kt) — HARDLINE just no-ops.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":""}"""
        )
        assertNull(reason)
    }

    // ---- Mixed: shell-shaped string in JS still blocks (interaction with shell list)

    @Test fun `dd to raw block device inside JS payload is blocked`() {
        // dd-to-/dev/sd* doesn't need a CMD_POS anchor (it's `\bdd\b…\bof=/dev/sd*`),
        // so the same string embedded in a JS fetch body still trips HARDLINE.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"fetch('/run', {body:'dd if=/dev/zero of=/dev/sda bs=1M'})"}"""
        )
        assertNotNull("dd-to-block-device pattern inside JS payload must still trip", reason)
    }

    @Test fun `cookie write with weird whitespace and case is still blocked`() {
        // Defence-in-depth: extra whitespace around the dot and the equals sign,
        // plus mixed case. The IGNORE_CASE flag + `\s*` in the pattern should cover this.
        val reason = HardlineCommandGuard.checkTool(
            "browser_eval_js",
            """{"code":"DOCUMENT . cookie  =  'x=1'"}"""
        )
        assertNotNull("whitespace-and-case-laundered cookie write should still hit", reason)
    }

    // ---- Make sure non-browser tools aren't accidentally affected ---------------

    @Test fun `eval_javascript tool is unchanged by browser_eval_js arm`() {
        // Pre-existing eval_javascript (QuickJS, sandboxed) explicitly returns null in
        // checkToolParsed. Adding the browser_eval_js arm must not touch its behaviour.
        val reason = HardlineCommandGuard.checkTool(
            "eval_javascript",
            """{"code":"document.cookie = 'x'"}"""
        )
        assertEquals("eval_javascript path should remain unchecked", null, reason)
    }
}

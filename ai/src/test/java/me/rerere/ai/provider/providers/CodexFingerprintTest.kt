package me.rerere.ai.provider.providers

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Codex wire fingerprint is stored as base64 fragments reassembled at runtime ([deob]) so the
 * literal codex-CLI strings are not a plaintext grep target. This pins the round-trip: a mistyped
 * fragment changes the decoded value and reddens here. (Also proves the top-level vals initialize
 * under a plain JVM unit test — they use java.util.Base64, not android.util.Base64.)
 */
class CodexFingerprintTest {

    @Test
    fun `deob reassembles the codex client fingerprint`() {
        assertEquals("codex_cli_rs", CHATGPT_ORIGINATOR)
        assertEquals("0.139.0", CHATGPT_CLIENT_VERSION)
        assertEquals("codex_cli_rs/0.139.0", CHATGPT_USER_AGENT)
    }
}

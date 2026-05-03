package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Test

class FingerprintToolTest {

    // Success path requires BiometricManager + activity launch — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `verify_fingerprint throws when title is missing`() {
        // Tool calls error("title is required") -> IllegalStateException, before any Context call.
        val tool = fingerprintTool(NULL_CONTEXT, BiometricResultBuffer())
        execTool(tool, """{}""")
    }
}

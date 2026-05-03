package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Test

class SmsInboxToolTest {

    // Success path requires ContentResolver — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `search_sms throws when query is missing`() {
        // Tool calls error("query is required") -> IllegalStateException, before any Context call.
        val tool = searchSmsTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }
}

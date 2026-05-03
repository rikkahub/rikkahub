package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Test

class ContactsToolTest {

    // Success path requires ContentResolver — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `search_contacts throws when query is missing`() {
        // Tool calls error("query is required") -> IllegalStateException, before any Context call.
        val tool = searchContactsTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }
}

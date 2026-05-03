package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Test

class NotificationToolTest {

    // Success path requires NotificationManagerCompat — instrumented test required.

    @Test(expected = IllegalStateException::class)
    fun `post_notification throws when title is missing`() {
        // Tool calls error("title is required") -> IllegalStateException
        val tool = notificationTool(NULL_CONTEXT)
        execTool(tool, """{}""")
    }
}

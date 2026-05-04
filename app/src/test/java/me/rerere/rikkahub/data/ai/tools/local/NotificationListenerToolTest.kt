package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationListenerToolTest {

    @Test
    fun `list_recent_notifications returns service-not-bound when offline`() {
        val tool = listRecentNotificationsTool()
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected not-bound envelope, got: $result",
            result.contains("notification_listener_not_bound")
        )
    }

    @Test
    fun `list_active_notifications returns service-not-bound when offline`() {
        val tool = listActiveNotificationsTool()
        val result = execTool(tool, """{}""")
        assertTrue(result.contains("notification_listener_not_bound"))
    }

    @Test
    fun `dismiss_notification rejects missing key`() {
        val tool = dismissNotificationTool()
        val result = execTool(tool, """{}""")
        assertTrue(
            "expected key-required error, got: $result",
            result.contains("notification_key is required")
        )
    }

    @Test
    fun `dismiss_notification returns not-bound when offline with valid key`() {
        val tool = dismissNotificationTool()
        val result = execTool(tool, """{"notification_key":"test"}""")
        assertTrue(result.contains("notification_listener_not_bound"))
    }

    @Test
    fun `notification_action_click rejects when neither index nor title provided`() {
        val tool = notificationActionClickTool()
        val result = execTool(tool, """{"notification_key":"test"}""")
        assertTrue(
            "expected action-required error, got: $result",
            result.contains("either action_index or action_title is required")
        )
    }

    @Test
    fun `notification_action_click rejects missing key`() {
        val tool = notificationActionClickTool()
        val result = execTool(tool, """{"action_index":0}""")
        assertTrue(result.contains("notification_key is required"))
    }
}

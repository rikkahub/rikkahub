package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationActionTitleTest {
    @Test
    fun `notification action titles match after trimming both sides`() {
        assertTrue(matchesNotificationActionTitle(" Reply ", "reply"))
        assertTrue(matchesNotificationActionTitle("Mark as read", " mark AS READ "))
    }

    @Test
    fun `blank notification action title requests do not match`() {
        assertFalse(matchesNotificationActionTitle("Reply", " "))
        assertFalse(matchesNotificationActionTitle(null, "Reply"))
    }
}

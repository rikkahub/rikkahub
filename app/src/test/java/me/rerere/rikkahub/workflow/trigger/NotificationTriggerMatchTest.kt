package me.rerere.rikkahub.workflow.trigger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 10.5 — regex notification filter matching. Covers [NotificationTriggerFamily.regexFind]:
 * valid patterns match via find(), and an uncompilable pattern fails safe (no match, no throw).
 */
class NotificationTriggerMatchTest {

    @Test fun `regex matches anywhere in input via find`() {
        assertTrue(NotificationTriggerFamily.regexFind("order #\\d+", "Your order #4271 shipped"))
        assertTrue(NotificationTriggerFamily.regexFind("(?i)alarm", "ALARM going off"))
    }

    @Test fun `regex that does not match returns false`() {
        assertFalse(NotificationTriggerFamily.regexFind("^Alarm", "Reminder: Alarm later"))
        assertFalse(NotificationTriggerFamily.regexFind("\\d{5}", "no digits here"))
    }

    @Test fun `invalid regex fails safe as no match instead of throwing`() {
        // Unbalanced bracket / paren would throw PatternSyntaxException if not guarded.
        assertFalse(NotificationTriggerFamily.regexFind("[unclosed", "anything"))
        assertFalse(NotificationTriggerFamily.regexFind("a(b", "anything"))
    }
}

package me.rerere.common.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Regression guard for issue #99: redactAndTruncate must never echo body content
// (bucket names, object keys, paths, server messages) into a log string. It emits
// length-only metadata.
class RedactAndTruncateTest {

    @Test
    fun `sync error body content never appears in redacted output`() {
        val errorBody =
            "<Error><Key>backups/secret-user-file.zip</Key>" +
                "<BucketName>my-private-bucket</BucketName>" +
                "<Message>Access Denied for /home/user/secret</Message></Error>"

        val redacted = redactAndTruncate(errorBody)

        assertFalse(redacted.contains("secret-user-file"))
        assertFalse(redacted.contains("my-private-bucket"))
        assertFalse(redacted.contains("backups/"))
        assertFalse(redacted.contains("Access Denied"))
        assertFalse(redacted.contains("/home/user/secret"))
    }

    @Test
    fun `redacted output carries only length metadata`() {
        val errorBody = "0123456789"
        assertEquals("<redacted body: 10 chars>", redactAndTruncate(errorBody))
        assertTrue(redactAndTruncate("anything").contains("chars"))
    }

    @Test
    fun `null and blank inputs return a safe placeholder`() {
        assertEquals("<no body>", redactAndTruncate(null))
        assertEquals("<no body>", redactAndTruncate(""))
        assertEquals("<no body>", redactAndTruncate("   "))
    }
}

package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatUtilTest {
    @Test
    fun `allows agc camera configs as text documents`() {
        assertTrue(isAllowedFileType("camera-config.agc", "application/octet-stream"))
        assertTrue(isAllowedFileType("CAMERA-CONFIG.AGC", "application/octet-stream"))
    }

    @Test
    fun `still rejects unknown binary file types`() {
        assertFalse(isAllowedFileType("archive.unknown", "application/octet-stream"))
    }
}

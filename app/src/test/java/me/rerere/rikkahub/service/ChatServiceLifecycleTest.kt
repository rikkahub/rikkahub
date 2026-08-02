package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatServiceLifecycleTest {
    @Test
    fun `delete lifecycle marker rejects every new generation boundary`() {
        assertFalse(
            canInstallGenerationBoundary(
                isDeleting = true,
                isDeleted = false,
                isCurrentSession = true,
            )
        )
        assertFalse(
            canInstallGenerationBoundary(
                isDeleting = false,
                isDeleted = true,
                isCurrentSession = true,
            )
        )
        assertFalse(
            canInstallGenerationBoundary(
                isDeleting = false,
                isDeleted = false,
                isCurrentSession = false,
            )
        )
        assertTrue(
            canInstallGenerationBoundary(
                isDeleting = false,
                isDeleted = false,
                isCurrentSession = true,
            )
        )
    }

    @Test
    fun `restore tombstone lifts only after durable row and old session detachment`() {
        assertFalse(canPublishRestoredConversation(rowIsDurable = false, oldSessionIsDetached = true))
        assertFalse(canPublishRestoredConversation(rowIsDurable = true, oldSessionIsDetached = false))
        assertTrue(canPublishRestoredConversation(rowIsDurable = true, oldSessionIsDetached = true))
    }
}

package me.rerere.rikkahub.data.sync.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPullProgressTrackerTest {
    @Test
    fun dismiss_hidesFuturePullsForThisTrackerInstance() {
        val tracker = CloudPullProgressTracker()

        tracker.beginConversationPull()
        assertTrue(tracker.state.value.isPullingConversations)
        tracker.dismissForSession()
        tracker.endConversationPull()
        tracker.beginConversationPull()

        assertTrue(tracker.state.value.dismissedForSession)
        assertTrue(tracker.state.value.isPullingConversations)

        val nextProcessTracker = CloudPullProgressTracker()
        assertFalse(nextProcessTracker.state.value.dismissedForSession)
        assertEquals(0, nextProcessTracker.state.value.conversationPullCount)
    }
}

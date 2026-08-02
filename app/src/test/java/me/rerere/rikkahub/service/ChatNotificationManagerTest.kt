package me.rerere.rikkahub.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNotificationManagerTest {
    @Test
    fun `terminal from replaced run cannot close current live update`() {
        assertFalse(
            shouldAcceptGenerationEnded(
                latestRunId = "run-b",
                latestPhaseEpoch = 4L,
                endedRunId = "run-a",
                endedPhaseEpoch = 3L,
            ),
        )
    }

    @Test
    fun `older phase cannot close a newer continuation`() {
        assertFalse(shouldAcceptGenerationEnded("run", 4L, "run", 3L))
    }

    @Test
    fun `new continuation terminal requires its started ownership boundary`() {
        assertFalse(shouldAcceptGenerationEnded("run", 3L, "run", 4L))
        assertTrue(shouldAcceptGenerationEnded("run", 4L, "run", 4L))
    }
}

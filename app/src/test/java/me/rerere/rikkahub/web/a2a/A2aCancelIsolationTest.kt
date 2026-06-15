package me.rerere.rikkahub.web.a2a

import kotlinx.coroutines.Job
import me.rerere.rikkahub.service.shouldStopA2aJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2aCancelIsolationTest {

    @Test
    fun `expected job identity is required before stopping generation`() {
        val taskA = Job()
        val taskB = Job()

        assertTrue(shouldStopA2aJob(currentJob = taskA, expectedJob = taskA))
        assertFalse(shouldStopA2aJob(currentJob = taskB, expectedJob = taskA))
        assertFalse(shouldStopA2aJob(currentJob = null, expectedJob = taskA))
    }
}
